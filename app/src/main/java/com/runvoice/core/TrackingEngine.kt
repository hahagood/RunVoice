package com.runvoice.core

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LocationSample(
    val wallTimeMillis: Long,
    val elapsedRealtimeMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val speedMetersPerSecond: Float?,
    val motionMoving: Boolean?
)

enum class TrackingDisposition { Accepted, Ignored }

enum class TrackingAlert {
    HighSpeedStarted,
    LocationJumpStarted,
    Recovered
}

data class TrackingDecision(
    val disposition: TrackingDisposition,
    val reason: String,
    val deltaMeters: Float,
    val totalDistanceMeters: Float,
    val segmentDistanceMeters: Float,
    val paceSecondsPerKm: Int,
    val stationaryDetected: Boolean,
    val alert: TrackingAlert? = null
)

data class TrackingConfig(
    val maxAccuracyMeters: Float = 20f,
    val minSpeedMetersPerSecond: Float = 0.5f,
    val maxSpeedMetersPerSecond: Float = 7f,
    val maxJumpMeters: Float = 100f,
    val rejectedChainMaxSpeedMetersPerSecond: Float = 15f,
    val rejectedChainMinSamples: Int = 4,
    val rejectedChainMinDurationMillis: Long = 3_000L,
    val trackingAlertDelayMillis: Long = 5_000L,
    val gpsMovingStepMeters: Float = 3f,
    val confirmationDistanceMeters: Float = 40f,
    val confirmationDisplacementMeters: Float = 25f,
    val confirmationDurationMillis: Long = 15_000L,
    val confirmationSpeedWindowSize: Int = 5,
    val confirmationMinStableSamples: Int = 4,
    val confirmationMaxSpeedSpread: Float = 2.5f,
    val confirmationMaxSpeedRatio: Float = 3f,
    val paceSegmentMeters: Float = 100f,
    val paceBufferSize: Int = 5
)

/** Pure GPS decision engine. Android location delivery and CSV recording are adapters around it. */
class TrackingEngine(private val config: TrackingConfig = TrackingConfig()) {
    private var lastSample: LocationSample? = null
    private var totalDistance = 0f
    private var segmentDistance = 0f
    private var segmentStartElapsedMillis = 0L
    private val paceBuffer = ArrayDeque<Int>(config.paceBufferSize)
    private var paceSecondsPerKm = 0

    private var stationaryDetected = false
    private var gpsMovementOverride = false
    private var stationaryAnchor: LocationSample? = null
    private var pendingStart: LocationSample? = null
    private var pendingDistance = 0f
    private val pendingSpeeds = ArrayDeque<Float>(config.confirmationSpeedWindowSize)

    private var trackingIssue: TrackingIssue? = null
    private var trackingIssueStartedAtMillis = 0L
    private var trackingIssueAnnounced = false
    private var rejectedChainStart: LocationSample? = null
    private var rejectedChainLast: LocationSample? = null
    private var rejectedChainSamples = 0
    private var followingRejectedChain = false

    fun reset() {
        lastSample = null
        totalDistance = 0f
        segmentDistance = 0f
        segmentStartElapsedMillis = 0L
        paceBuffer.clear()
        paceSecondsPerKm = 0
        clearStationaryLock()
        clearTrackingIssue()
    }

    fun resumeTracking() {
        lastSample = null
        segmentDistance = 0f
        segmentStartElapsedMillis = 0L
        clearStationaryLock()
        clearTrackingIssue()
    }

    fun process(sample: LocationSample): TrackingDecision {
        if (!sample.latitude.isFinite() || !sample.longitude.isFinite() ||
            !sample.accuracyMeters.isFinite() || sample.accuracyMeters > config.maxAccuracyMeters
        ) {
            return ignored("accuracy_gt_20m")
        }

        val previous = lastSample
        if (previous == null) {
            lastSample = sample
            segmentStartElapsedMillis = sample.elapsedRealtimeMillis
            clearStationaryLock()
            return accepted("seed_point", 0f)
        }

        val elapsedMillis = sample.elapsedRealtimeMillis - previous.elapsedRealtimeMillis
        if (elapsedMillis <= 0L) return ignored("non_monotonic_location_time")

        val distance = distanceMeters(previous, sample)
        if (!distance.isFinite()) return ignored("invalid_distance")
        if (distance > config.maxJumpMeters) {
            return rejectAndTrackRecovery(sample, TrackingIssue.LocationJump, "jump_gt_100m", distance)
        }

        val derivedSpeed = distance / (elapsedMillis / 1000f)
        if (!derivedSpeed.isFinite() || derivedSpeed > config.maxSpeedMetersPerSecond) {
            return rejectAndTrackRecovery(sample, TrackingIssue.HighSpeed, "speed_above_7_mps", distance)
        }

        val recoveryAlert = finishTrackingIssueIfNeeded()
        // Valid samples advance the local GPS chain. Rejected samples are handled by a separate
        // quarantine chain so a sustained anomaly cannot leave this anchor permanently stale.
        lastSample = sample
        val wasStationary = stationaryDetected
        val gpsIndicatesMovement = (sample.speedMetersPerSecond ?: derivedSpeed) >= config.minSpeedMetersPerSecond ||
            distance >= config.gpsMovingStepMeters
        var distanceToAdd = distance
        var reason = "distance_accumulated"

        if (sample.motionMoving == false || wasStationary) {
            if (!gpsMovementOverride && stationaryAnchor == null) stationaryAnchor = previous

            if (!gpsIndicatesMovement) {
                resetStationaryConfirmation()
                stationaryDetected = true
                return ignored(
                    if (wasStationary && sample.motionMoving != false) "stationary_locked_gps_still" else "stationary_gps_still",
                    distance,
                    recoveryAlert
                )
            }

            if (!gpsMovementOverride) {
                if (derivedSpeed !in config.minSpeedMetersPerSecond..config.maxSpeedMetersPerSecond) {
                    resetStationaryConfirmation()
                    stationaryDetected = true
                    return ignored(
                        if (wasStationary && sample.motionMoving != false) {
                            "stationary_locked_rejected_resume_speed"
                        } else {
                            "stationary_rejected_resume_speed"
                        },
                        distance,
                        recoveryAlert
                    )
                }

                if (pendingStart == null) pendingStart = previous
                pendingDistance += distance
                addPendingSpeed(derivedSpeed)
                val start = requireNotNull(pendingStart)
                val confirmed = sample.elapsedRealtimeMillis - start.elapsedRealtimeMillis >= config.confirmationDurationMillis &&
                    pendingDistance >= config.confirmationDistanceMeters &&
                    distanceMeters(start, sample) >= config.confirmationDisplacementMeters &&
                    hasStableResumeSpeed()

                if (!confirmed) {
                    stationaryDetected = true
                    val stable = hasStableResumeSpeed()
                    return ignored(
                        when {
                            !stable && wasStationary && sample.motionMoving != false -> "stationary_locked_waiting_for_stable_gps_speed"
                            !stable -> "stationary_waiting_for_stable_gps_speed"
                            wasStationary && sample.motionMoving != false -> "stationary_locked_waiting_for_gps_confirmation"
                            else -> "stationary_waiting_for_gps_confirmation"
                        },
                        distance,
                        recoveryAlert
                    )
                }

                val resumedDistance = stationaryAnchor?.let { distanceMeters(it, sample) } ?: pendingDistance
                if (!resumedDistance.isFinite() || resumedDistance < 0f || resumedDistance > config.maxJumpMeters) {
                    resetStationaryConfirmation()
                    stationaryAnchor = sample
                    stationaryDetected = true
                    return ignored("stationary_resume_distance_above_limit", distance, recoveryAlert)
                }
                gpsMovementOverride = true
                stationaryDetected = false
                distanceToAdd = resumedDistance
                clearPendingMovement()
                stationaryAnchor = null
            }
            reason = "gps_confirmed_movement"
        } else {
            stationaryDetected = false
            clearStationaryLock()
            if (derivedSpeed < config.minSpeedMetersPerSecond) {
                return ignored("speed_below_0_5_mps", distance, recoveryAlert)
            }
        }

        if (!distanceToAdd.isFinite() || distanceToAdd < 0f || distanceToAdd > config.maxJumpMeters) {
            return ignored("invalid_accepted_distance", distance, recoveryAlert)
        }

        totalDistance += distanceToAdd
        segmentDistance += distanceToAdd
        updatePace(sample.elapsedRealtimeMillis)
        return accepted(reason, distanceToAdd, recoveryAlert)
    }

    /**
     * Keeps suspicious samples out of distance totals while following them in quarantine. Once
     * several consecutive samples form a physically plausible chain, the local anchor is rebased
     * without adding the uncertain gap. This prevents a speed rejection from becoming an endless
     * `jump_gt_100m` rejection as the runner continues moving away from the old accepted point.
     */
    private fun rejectAndTrackRecovery(
        sample: LocationSample,
        issue: TrackingIssue,
        reason: String,
        distanceFromAcceptedAnchor: Float
    ): TrackingDecision {
        if (trackingIssue != issue) beginTrackingIssue(issue, sample)

        val chainPrevious = rejectedChainLast
        val continuesPlausibleChain = chainPrevious != null && isPlausibleRejectedStep(chainPrevious, sample)
        if (!continuesPlausibleChain) {
            rejectedChainStart = sample
            rejectedChainSamples = 1
            followingRejectedChain = false
        } else {
            rejectedChainSamples++
        }
        rejectedChainLast = sample

        val chainStart = rejectedChainStart
        val chainDuration = if (chainStart == null) 0L else {
            sample.elapsedRealtimeMillis - chainStart.elapsedRealtimeMillis
        }
        val confirmedRejectedChain = !followingRejectedChain &&
            rejectedChainSamples >= config.rejectedChainMinSamples &&
            chainDuration >= config.rejectedChainMinDurationMillis
        if (confirmedRejectedChain) {
            followingRejectedChain = true
        }

        if (followingRejectedChain) {
            lastSample = sample
            segmentDistance = 0f
            segmentStartElapsedMillis = sample.elapsedRealtimeMillis
            clearStationaryLock()
        }

        val alert = trackingIssueAlert(
            nowMillis = sample.elapsedRealtimeMillis,
            force = confirmedRejectedChain && issue == TrackingIssue.LocationJump
        )
        val diagnosticReason = if (followingRejectedChain) "${reason}_reanchored" else reason
        return ignored(diagnosticReason, distanceFromAcceptedAnchor, alert)
    }

    private fun isPlausibleRejectedStep(previous: LocationSample, current: LocationSample): Boolean {
        val elapsedMillis = current.elapsedRealtimeMillis - previous.elapsedRealtimeMillis
        if (elapsedMillis <= 0L) return false
        val distance = distanceMeters(previous, current)
        if (!distance.isFinite() || distance > config.maxJumpMeters) return false
        val speed = distance / (elapsedMillis / 1000f)
        return speed.isFinite() && speed <= config.rejectedChainMaxSpeedMetersPerSecond
    }

    private fun beginTrackingIssue(issue: TrackingIssue, sample: LocationSample) {
        trackingIssue = issue
        trackingIssueStartedAtMillis = sample.elapsedRealtimeMillis
        trackingIssueAnnounced = false
        rejectedChainStart = sample
        rejectedChainLast = null
        rejectedChainSamples = 0
        followingRejectedChain = false
    }

    private fun trackingIssueAlert(nowMillis: Long, force: Boolean = false): TrackingAlert? {
        if (trackingIssueAnnounced ||
            (!force && nowMillis - trackingIssueStartedAtMillis < config.trackingAlertDelayMillis)
        ) {
            return null
        }
        trackingIssueAnnounced = true
        return when (trackingIssue) {
            TrackingIssue.HighSpeed -> TrackingAlert.HighSpeedStarted
            TrackingIssue.LocationJump -> TrackingAlert.LocationJumpStarted
            null -> null
        }
    }

    private fun finishTrackingIssueIfNeeded(): TrackingAlert? {
        val alert = if (trackingIssueAnnounced) TrackingAlert.Recovered else null
        clearTrackingIssue()
        return alert
    }

    private fun clearTrackingIssue() {
        trackingIssue = null
        trackingIssueStartedAtMillis = 0L
        trackingIssueAnnounced = false
        rejectedChainStart = null
        rejectedChainLast = null
        rejectedChainSamples = 0
        followingRejectedChain = false
    }

    private fun updatePace(nowElapsedMillis: Long) {
        if (segmentDistance < config.paceSegmentMeters || segmentStartElapsedMillis <= 0L) return
        val seconds = (nowElapsedMillis - segmentStartElapsedMillis) / 1000.0
        if (seconds > 0.0) {
            val speed = segmentDistance / seconds
            if (speed in config.minSpeedMetersPerSecond..config.maxSpeedMetersPerSecond) {
                if (paceBuffer.size >= config.paceBufferSize) paceBuffer.removeFirst()
                paceBuffer.addLast((1000.0 / speed).toInt())
                val sorted = paceBuffer.sorted()
                paceSecondsPerKm = if (sorted.size % 2 == 1) {
                    sorted[sorted.size / 2]
                } else {
                    (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
                }
            }
        }
        segmentDistance = 0f
        segmentStartElapsedMillis = nowElapsedMillis
    }

    private fun accepted(reason: String, delta: Float, alert: TrackingAlert? = null) = TrackingDecision(
        TrackingDisposition.Accepted,
        reason,
        delta,
        totalDistance,
        segmentDistance,
        paceSecondsPerKm,
        stationaryDetected,
        alert
    )

    private fun ignored(reason: String, delta: Float = 0f, alert: TrackingAlert? = null) = TrackingDecision(
        TrackingDisposition.Ignored,
        reason,
        delta,
        totalDistance,
        segmentDistance,
        paceSecondsPerKm,
        stationaryDetected,
        alert
    )

    private fun resetStationaryConfirmation() {
        gpsMovementOverride = false
        clearPendingMovement()
    }

    private fun clearPendingMovement() {
        pendingStart = null
        pendingDistance = 0f
        pendingSpeeds.clear()
    }

    private fun clearStationaryLock() {
        stationaryDetected = false
        resetStationaryConfirmation()
        stationaryAnchor = null
    }

    private fun addPendingSpeed(speed: Float) {
        if (pendingSpeeds.size >= config.confirmationSpeedWindowSize) pendingSpeeds.removeFirst()
        pendingSpeeds.addLast(speed)
    }

    private fun hasStableResumeSpeed(): Boolean {
        if (pendingSpeeds.size < config.confirmationMinStableSamples) return false
        val min = pendingSpeeds.minOrNull() ?: return false
        val max = pendingSpeeds.maxOrNull() ?: return false
        return min > 0f && max - min <= config.confirmationMaxSpeedSpread && max / min <= config.confirmationMaxSpeedRatio
    }

    private fun distanceMeters(first: LocationSample, second: LocationSample): Float {
        val lat1 = first.latitude * PI / 180.0
        val lat2 = second.latitude * PI / 180.0
        val dLat = lat2 - lat1
        val dLon = (second.longitude - first.longitude) * PI / 180.0
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return (2.0 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))).toFloat()
    }

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0
    }

    private enum class TrackingIssue { HighSpeed, LocationJump }
}
