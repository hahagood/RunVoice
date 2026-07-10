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

    @Test fun pauseResumeStartsWithSeedAndKeepsAccumulatedDistance() {
        val engine = TrackingEngine()
        engine.process(sample(1_000, 0.0))
        val beforePause = engine.process(sample(4_000, 12.0))
        engine.resumeTracking()
        val seed = engine.process(sample(100_000, 1_000.0))
        assertEquals("seed_point", seed.reason)
        assertEquals(beforePause.totalDistanceMeters, seed.totalDistanceMeters)
    }

    @Test fun stationaryDriftDoesNotIncreaseDistance() {
        val engine = TrackingEngine()
        engine.process(sample(1_000, 0.0, motion = false))
        repeat(6) { index ->
            val decision = engine.process(sample(3_000L + index * 2_000L, (index + 1) * 0.4, motion = false))
            assertEquals(0f, decision.totalDistanceMeters)
            assertTrue(decision.stationaryDetected)
        }
    }

    @Test fun stableGpsMovementCanRecoverFromStationaryLock() {
        val engine = TrackingEngine(
            TrackingConfig(
                confirmationDurationMillis = 4_000,
                confirmationDistanceMeters = 8f,
                confirmationDisplacementMeters = 8f,
                confirmationMinStableSamples = 4
            )
        )
        engine.process(sample(1_000, 0.0, motion = false))
        var decision = engine.process(sample(2_000, 2.0, motion = false))
        decision = engine.process(sample(3_000, 4.0, motion = false))
        decision = engine.process(sample(4_000, 6.0, motion = false))
        decision = engine.process(sample(5_000, 8.0, motion = false))
        decision = engine.process(sample(6_000, 10.0, motion = false))
        assertEquals(TrackingDisposition.Accepted, decision.disposition)
        assertEquals("gps_confirmed_movement", decision.reason)
        assertTrue(decision.totalDistanceMeters in 9f..11f)
    }

    @Test fun stationaryResumeCannotAddAnUnboundedSingleDelta() {
        val engine = TrackingEngine(
            TrackingConfig(
                confirmationDurationMillis = 20_000,
                confirmationDistanceMeters = 100f,
                confirmationDisplacementMeters = 100f,
                confirmationMinStableSamples = 1
            )
        )
        engine.process(sample(1_000, 0.0, motion = false))
        engine.process(sample(11_000, 60.0, motion = false))
        val decision = engine.process(sample(21_000, 120.0, motion = false))
        assertEquals("stationary_resume_distance_above_limit", decision.reason)
        assertEquals(0f, decision.totalDistanceMeters)
        assertTrue(decision.stationaryDetected)
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
