package com.runvoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingEngineTest {
    private fun sample(
        elapsedMillis: Long,
        metersEast: Double,
        motion: Boolean? = true,
        accuracy: Float = 5f
    ) = LocationSample(
        wallTimeMillis = 1_700_000_000_000L + elapsedMillis,
        elapsedRealtimeMillis = elapsedMillis,
        latitude = 0.0,
        longitude = metersEast / 111_195.0,
        accuracyMeters = accuracy,
        speedMetersPerSecond = null,
        motionMoving = motion
    )

    @Test fun highSpeedPointDoesNotAddDistanceOrPoisonNextPoint() {
        val engine = TrackingEngine()
        engine.process(sample(1_000, 0.0))
        val bad = engine.process(sample(2_000, 20.0))
        assertEquals("speed_above_7_mps", bad.reason)
        assertEquals(0f, bad.totalDistanceMeters)
        val recovered = engine.process(sample(5_000, 12.0))
        assertEquals(TrackingDisposition.Accepted, recovered.disposition)
        assertTrue(recovered.totalDistanceMeters in 11f..13f)
    }

    @Test fun sustainedHighSpeedReanchorsAndRecoversInsteadOfBecomingPermanentJump() {
        val engine = TrackingEngine(
            TrackingConfig(
                trackingAlertDelayMillis = 5_000,
                trackingRecoveryDurationMillis = 0,
                trackingRecoveryMinSamples = 1
            )
        )
        engine.process(sample(1_000, 0.0))

        val rejected = (1..7).map { index ->
            engine.process(sample(1_000L + index * 1_000L, index * 10.0))
        }

        assertTrue(rejected.all { it.disposition == TrackingDisposition.Ignored })
        assertTrue(rejected.none { it.reason.startsWith("jump_gt_100m") })
        assertEquals(1, rejected.count { it.alert == TrackingAlert.HighSpeedStarted })

        val recovered = engine.process(sample(9_000, 75.0))
        assertEquals(TrackingDisposition.Accepted, recovered.disposition)
        assertEquals(TrackingAlert.Recovered, recovered.alert)
        assertTrue(recovered.totalDistanceMeters in 4f..6f)
    }

    @Test fun consistentLocationsAfterHugeJumpReanchorWithoutAddingUncertainGap() {
        val engine = TrackingEngine(
            TrackingConfig(
                trackingAlertDelayMillis = 0,
                trackingRecoveryDurationMillis = 0,
                trackingRecoveryMinSamples = 1
            )
        )
        engine.process(sample(1_000, 0.0))
        val rejected = listOf(
            engine.process(sample(2_000, 500.0)),
            engine.process(sample(3_000, 503.0)),
            engine.process(sample(4_000, 506.0)),
            engine.process(sample(5_000, 509.0))
        )
        val reanchored = rejected.last()
        assertEquals("jump_gt_100m_reanchored", reanchored.reason)
        assertEquals(1, rejected.count { it.alert == TrackingAlert.LocationJumpStarted })
        assertEquals(0f, reanchored.totalDistanceMeters)

        val recovered = engine.process(sample(6_000, 513.0))
        assertEquals(TrackingDisposition.Accepted, recovered.disposition)
        assertEquals(TrackingAlert.Recovered, recovered.alert)
        assertTrue(recovered.totalDistanceMeters in 3f..5f)
    }

    @Test fun nonMonotonicPointIsIgnoredWithoutNegativeGrowth() {
        val engine = TrackingEngine()
        engine.process(sample(10_000, 0.0))
        val decision = engine.process(sample(9_000, 5.0))
        assertEquals("non_monotonic_location_time", decision.reason)
        assertEquals(0f, decision.totalDistanceMeters)
    }

    @Test fun inaccurateAndHugeJumpPointsNeverIncreaseDistance() {
        val engine = TrackingEngine()
        engine.process(sample(1_000, 0.0))
        assertEquals(0f, engine.process(sample(2_000, 1.0, accuracy = 50f)).totalDistanceMeters)
        val jump = engine.process(sample(20_000, 150.0))
        assertEquals("jump_gt_100m", jump.reason)
        assertEquals(0f, jump.totalDistanceMeters)
    }

    @Test fun longGapWithPlausibleRunningSpeedIsNotMisclassifiedAsAJump() {
        val engine = TrackingEngine()
        engine.process(sample(1_000, 0.0))
        engine.process(sample(12_000, 50.0, accuracy = 50f))

        val reacquired = engine.process(sample(26_000, 115.0))

        assertEquals(TrackingDisposition.Accepted, reacquired.disposition)
        assertEquals("distance_accumulated", reacquired.reason)
        assertTrue(reacquired.totalDistanceMeters in 114f..116f)
        assertEquals(null, reacquired.alert)
    }

    @Test fun transientTrackingIssueClearsSilentlyAfterStableLocations() {
        val engine = TrackingEngine()
        engine.process(sample(1_000, 0.0))
        val rejected = (1..7).map { index ->
            engine.process(sample(1_000L + index * 1_000L, index * 10.0))
        }
        assertTrue(rejected.none { it.alert != null })

        val stable = (1..5).map { index ->
            engine.process(sample(8_000L + index * 2_500L, 70.0 + index * 4.0))
        }
        assertTrue(stable.none { it.alert != null })
    }

    @Test fun announcedTrackingIssueNeedsAStableRecoveryWindow() {
        val engine = TrackingEngine(
            TrackingConfig(
                trackingAlertDelayMillis = 2_000,
                trackingRecoveryDurationMillis = 5_000,
                trackingRecoveryMinSamples = 3
            )
        )
        engine.process(sample(1_000, 0.0))
        val alerted = (1..4).map { index ->
            engine.process(sample(1_000L + index * 1_000L, index * 10.0))
        }
        assertEquals(1, alerted.count { it.alert == TrackingAlert.HighSpeedStarted })

        val firstHealthy = engine.process(sample(6_000, 42.0))
        assertEquals(null, firstHealthy.alert)
        engine.process(sample(7_000, 50.0))
        val recovering = listOf(
            engine.process(sample(9_000, 54.0)),
            engine.process(sample(12_000, 60.0)),
            engine.process(sample(14_000, 64.0))
        )
        assertEquals(TrackingAlert.Recovered, recovering.last().alert)
        assertTrue(recovering.dropLast(1).none { it.alert != null })
    }

    @Test fun pauseResumeStartsWithSeedAndKeepsAccumulatedDistance() {
        val engine = TrackingEngine()
        engine.process(sample(1_000, 0.0))
        val beforePause = engine.process(sample(4_000, 12.0))
        engine.resumeTracking()
        val seed = engine.process(sample(100_000, 1_000.0))
        assertEquals("seed_point", seed.reason)
        assertEquals(beforePause.totalDistanceMeters, seed.totalDistanceMeters)
    }

    @Test fun paceUpdatesAfterFiftyMeters() {
        val engine = TrackingEngine()
        engine.process(sample(1_000, 0.0))

        val beforeBoundary = engine.process(sample(11_400, 26.0))
        assertEquals(0, beforeBoundary.paceSecondsPerKm)

        val atBoundary = engine.process(sample(21_800, 52.0))
        assertEquals(400, atBoundary.paceSecondsPerKm)
        assertEquals(0f, atBoundary.segmentDistanceMeters)
    }

    @Test fun resumeClearsOldPaceUntilFreshFiftyMeterSegmentCompletes() {
        val engine = TrackingEngine()
        engine.process(sample(1_000, 0.0))
        assertEquals(400, engine.process(sample(21_800, 52.0)).paceSecondsPerKm)

        engine.resumeTracking()
        val resumeSeed = engine.process(sample(100_000, 1_000.0))
        assertEquals("seed_point", resumeSeed.reason)
        assertEquals(0, resumeSeed.paceSecondsPerKm)

        val partialSegment = engine.process(sample(110_400, 1_026.0))
        assertEquals(0, partialSegment.paceSecondsPerKm)

        val freshPace = engine.process(sample(120_800, 1_052.0))
        assertEquals(400, freshPace.paceSecondsPerKm)
    }

    @Test fun restoredDistanceUsesFirstLocationOnlyAsANewAnchor() {
        val engine = TrackingEngine()
        engine.restoreDistance(12_345f)

        val seed = engine.process(sample(100_000, 50_000.0))
        assertEquals("seed_point", seed.reason)
        assertEquals(12_345f, seed.totalDistanceMeters)
        assertEquals(0, seed.paceSecondsPerKm)

        val continued = engine.process(sample(104_000, 50_010.0))
        assertTrue(continued.totalDistanceMeters in 12_354f..12_356f)
    }

    @Test fun stationaryDriftDoesNotIncreaseDistance() {
        val engine = TrackingEngine()
        engine.process(sample(1_000, 0.0, motion = false))
        var lastDecision: TrackingDecision? = null
        repeat(6) { index ->
            val decision = engine.process(sample(3_000L + index * 2_000L, (index + 1) * 0.4, motion = false))
            assertEquals(0f, decision.totalDistanceMeters)
            lastDecision = decision
        }
        assertTrue(requireNotNull(lastDecision).stationaryDetected)
    }

    @Test fun aSingleStationarySensorSampleDoesNotLockAStartingRunner() {
        val engine = TrackingEngine()
        engine.process(sample(1_000, 0.0))

        val candidate = engine.process(sample(2_000, 0.1, motion = false))
        assertEquals("stationary_candidate_gps_still", candidate.reason)
        assertTrue(!candidate.stationaryDetected)

        val moving = engine.process(sample(3_000, 2.0, motion = true))
        assertEquals(TrackingDisposition.Accepted, moving.disposition)
        assertTrue(moving.totalDistanceMeters in 1.8f..2.2f)
        assertTrue(!moving.stationaryDetected)
    }

    @Test fun stableGpsMovementCanRecoverFromStationaryLock() {
        val engine = TrackingEngine(
            TrackingConfig(
                stationaryEntryDurationMillis = 1_000,
                confirmationDurationMillis = 4_000,
                confirmationDistanceMeters = 8f,
                confirmationDisplacementMeters = 8f,
                confirmationMinStableSamples = 4
            )
        )
        engine.process(sample(1_000, 0.0))
        engine.process(sample(2_000, 0.0, motion = false))
        var decision = engine.process(sample(3_000, 2.0, motion = false))
        decision = engine.process(sample(4_000, 4.0, motion = false))
        decision = engine.process(sample(5_000, 6.0, motion = false))
        decision = engine.process(sample(6_000, 8.0, motion = false))
        decision = engine.process(sample(7_000, 10.0, motion = false))
        assertEquals(TrackingDisposition.Accepted, decision.disposition)
        assertEquals("gps_confirmed_movement", decision.reason)
        assertTrue(decision.totalDistanceMeters in 9f..11f)
    }

    @Test fun stationaryResumeCanRestoreMoreThanOneHundredMetersWhenElapsedSpeedIsPlausible() {
        val engine = TrackingEngine(
            TrackingConfig(
                stationaryEntryDurationMillis = 1_000,
                confirmationDurationMillis = 20_000,
                confirmationDistanceMeters = 100f,
                confirmationDisplacementMeters = 100f,
                confirmationMinStableSamples = 1
            )
        )
        engine.process(sample(1_000, 0.0))
        engine.process(sample(2_000, 0.0, motion = false))
        engine.process(sample(12_000, 60.0, motion = false))
        val decision = engine.process(sample(22_000, 120.0, motion = false))
        assertEquals("gps_confirmed_movement", decision.reason)
        assertTrue(decision.totalDistanceMeters in 119f..121f)
        assertTrue(!decision.stationaryDetected)
    }

    @Test fun invalidCoordinatesNeverProduceNanDistance() {
        val engine = TrackingEngine()
        val invalid = LocationSample(0, 1, Double.NaN, 0.0, 5f, null, true)
        val decision = engine.process(invalid)
        assertEquals(TrackingDisposition.Ignored, decision.disposition)
        assertEquals(0f, decision.totalDistanceMeters)
        assertTrue(decision.totalDistanceMeters.isFinite())
    }
}
