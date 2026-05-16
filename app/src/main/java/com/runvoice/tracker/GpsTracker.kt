package com.runvoice.tracker

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class GpsTracker(context: Context, private val motionDetector: MotionDetector? = null) {

    companion object {
        private const val MIN_SPEED_MPS = 0.5  // below = standing still drift
        private const val MAX_SPEED_MPS = 7.0  // above = GPS jump (25 km/h)
        private const val GPS_MOVING_SPEED_MPS = 0.5f
        private const val GPS_MOVING_STEP_DISTANCE_M = 3f
        private const val GPS_CONFIRMATION_DISTANCE_M = 40f
        private const val GPS_CONFIRMATION_DISPLACEMENT_M = 25f
        private const val GPS_CONFIRMATION_DURATION_MS = 15_000L
        private const val PACE_BUFFER_SIZE = 5
    }

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    private val traceRecorder = GpsTraceRecorder(context)

    private val _distanceMeters = MutableStateFlow(0f)
    val distanceMeters = _distanceMeters.asStateFlow()

    private val _paceSecondsPerKm = MutableStateFlow(0)
    val paceSecondsPerKm = _paceSecondsPerKm.asStateFlow()

    private val _stationaryDetected = MutableStateFlow(false)
    val stationaryDetected = _stationaryDetected.asStateFlow()

    private var lastLocation: Location? = null
    private var totalDistance = 0f

    // For pace calculation: keep recent segment data
    private var segmentDistance = 0f
    private var segmentStartTime = 0L

    // Median filter buffer for pace smoothing
    private val paceBuffer = ArrayDeque<Int>(PACE_BUFFER_SIZE)

    private var gpsMovementOverride = false
    private var pendingStationaryDistance = 0f
    private var pendingStationaryStartTime = 0L
    private var pendingStationaryStartLocation: Location? = null

    private val locationRequest = LocationRequest.Builder(
        Priority.PRIORITY_HIGH_ACCURACY, 2000L
    ).setMinUpdateIntervalMillis(1000L).build()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { loc ->
                if (loc.accuracy > 20f) {
                    traceRecorder.record(
                        location = loc,
                        motionState = motionDetector?.isMoving?.value,
                        decision = "ignored",
                        reason = "accuracy_gt_20m",
                        deltaMeters = 0f,
                        totalDistanceMeters = totalDistance,
                        segmentDistanceMeters = segmentDistance,
                        paceSecondsPerKm = _paceSecondsPerKm.value
                    )
                    return@forEach
                }
                processLocation(loc)
            }
        }
    }

    private fun processLocation(loc: Location) {
        val prev = lastLocation
        lastLocation = loc

        if (prev == null) {
            segmentStartTime = loc.time
            resetStationaryGpsConfirmation()
            _stationaryDetected.value = false
            traceRecorder.record(
                location = loc,
                motionState = motionDetector?.isMoving?.value,
                decision = "accepted",
                reason = "seed_point",
                deltaMeters = 0f,
                totalDistanceMeters = totalDistance,
                segmentDistanceMeters = segmentDistance,
                paceSecondsPerKm = _paceSecondsPerKm.value
            )
            return
        }

        val d = prev.distanceTo(loc)
        // Ignore unreasonably large jumps (> 100m in ~2-3s = >120 km/h)
        if (d > 100f) {
            resetStationaryGpsConfirmation()
            _stationaryDetected.value = false
            traceRecorder.record(
                location = loc,
                motionState = motionDetector?.isMoving?.value,
                decision = "ignored",
                reason = "jump_gt_100m",
                deltaMeters = d,
                totalDistanceMeters = totalDistance,
                segmentDistanceMeters = segmentDistance,
                paceSecondsPerKm = _paceSecondsPerKm.value
            )
            return
        }

        val motionState = motionDetector?.isMoving?.value
        val gpsIndicatesMovement = (loc.hasSpeed() && loc.speed >= GPS_MOVING_SPEED_MPS) ||
            d >= GPS_MOVING_STEP_DISTANCE_M
        var distanceToAdd = d
        var acceptedReason = "distance_accumulated"

        if (motionState == false) {
            if (!gpsIndicatesMovement) {
                resetStationaryGpsConfirmation()
                _stationaryDetected.value = true
                traceRecorder.record(
                    location = loc,
                    motionState = false,
                    decision = "ignored",
                    reason = "stationary_gps_still",
                    deltaMeters = d,
                    totalDistanceMeters = totalDistance,
                    segmentDistanceMeters = segmentDistance,
                    paceSecondsPerKm = _paceSecondsPerKm.value
                )
                return
            }

            if (!gpsMovementOverride) {
                if (pendingStationaryStartLocation == null) {
                    pendingStationaryStartLocation = prev
                    pendingStationaryStartTime = prev.time
                    pendingStationaryDistance = 0f
                }
                pendingStationaryDistance += d

                val elapsedMs = loc.time - pendingStationaryStartTime
                val displacement = pendingStationaryStartLocation?.distanceTo(loc) ?: 0f
                val gpsMovementConfirmed = elapsedMs >= GPS_CONFIRMATION_DURATION_MS &&
                    pendingStationaryDistance >= GPS_CONFIRMATION_DISTANCE_M &&
                    displacement >= GPS_CONFIRMATION_DISPLACEMENT_M

                if (!gpsMovementConfirmed) {
                    _stationaryDetected.value = true
                    traceRecorder.record(
                        location = loc,
                        motionState = false,
                        decision = "ignored",
                        reason = "stationary_waiting_for_gps_confirmation",
                        deltaMeters = d,
                        totalDistanceMeters = totalDistance,
                        segmentDistanceMeters = segmentDistance,
                        paceSecondsPerKm = _paceSecondsPerKm.value
                    )
                    return
                }

                gpsMovementOverride = true
                _stationaryDetected.value = false
                distanceToAdd = pendingStationaryDistance
                pendingStationaryDistance = 0f
                pendingStationaryStartTime = 0L
                pendingStationaryStartLocation = null
            }
            acceptedReason = "gps_confirmed_movement"
        } else {
            _stationaryDetected.value = false
            resetStationaryGpsConfirmation()
        }

        totalDistance += distanceToAdd
        segmentDistance += distanceToAdd
        _distanceMeters.value = totalDistance

        // Recalculate pace every time segment reaches 100m+
        if (segmentDistance >= 100f && segmentStartTime > 0) {
            val segmentTimeS = (loc.time - segmentStartTime) / 1000.0
            if (segmentTimeS > 0) {
                val speedMps = segmentDistance / segmentTimeS
                if (speedMps in MIN_SPEED_MPS..MAX_SPEED_MPS) {
                    val pace = (1000.0 / speedMps).toInt()
                    if (paceBuffer.size >= PACE_BUFFER_SIZE) paceBuffer.removeFirst()
                    paceBuffer.addLast(pace)
                    _paceSecondsPerKm.value = medianPace()
                }
                // Speed out of range: keep last smoothed pace
            }
            segmentDistance = 0f
            segmentStartTime = loc.time
        }

        traceRecorder.record(
            location = loc,
            motionState = motionState,
            decision = "accepted",
            reason = acceptedReason,
            deltaMeters = distanceToAdd,
            totalDistanceMeters = totalDistance,
            segmentDistanceMeters = segmentDistance,
            paceSecondsPerKm = _paceSecondsPerKm.value
        )
    }

    private fun resetStationaryGpsConfirmation() {
        gpsMovementOverride = false
        pendingStationaryDistance = 0f
        pendingStationaryStartTime = 0L
        pendingStationaryStartLocation = null
    }

    private fun medianPace(): Int {
        val sorted = paceBuffer.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }

    @SuppressLint("MissingPermission")
    fun start() {
        lastLocation = null
        totalDistance = 0f
        segmentDistance = 0f
        segmentStartTime = 0L
        paceBuffer.clear()
        resetStationaryGpsConfirmation()
        _distanceMeters.value = 0f
        _paceSecondsPerKm.value = 0
        _stationaryDetected.value = false
        traceRecorder.startSession()
        fusedClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    fun pause() {
        fusedClient.removeLocationUpdates(locationCallback)
    }

    @SuppressLint("MissingPermission")
    fun resume() {
        segmentDistance = 0f
        segmentStartTime = 0L
        lastLocation = null
        resetStationaryGpsConfirmation()
        _stationaryDetected.value = false
        fusedClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    fun stop(saveSession: Boolean = true) {
        fusedClient.removeLocationUpdates(locationCallback)
        lastLocation = null
        resetStationaryGpsConfirmation()
        _stationaryDetected.value = false
        traceRecorder.closeSession(save = saveSession)
    }

    fun currentTracePath(): String? = traceRecorder.currentPath()
}
