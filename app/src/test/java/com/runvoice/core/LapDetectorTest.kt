package com.runvoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LapDetectorTest {
    private fun longitudeForMeters(meters: Double): Double = meters / 111_195.0

    @Test fun announcesOnlyAfterLeavingAndCompletingAClosedLap() {
        val detector = LapDetector()
        assertNull(detector.process(0.0, longitudeForMeters(0.0), 0f))
        assertNull(detector.process(0.0, longitudeForMeters(100.0), 100f))
        assertNull(detector.process(0.001, longitudeForMeters(100.0), 210f))
        assertNull(detector.process(0.001, longitudeForMeters(0.0), 310f))

        val first = detector.process(0.0, longitudeForMeters(4.0), 421f)
        assertEquals(1, first?.lapNumber)
        assertEquals(421f, first?.lapDistanceMeters)

        assertNull(detector.process(0.0, longitudeForMeters(100.0), 517f))
        assertNull(detector.process(0.001, longitudeForMeters(100.0), 628f))
        assertNull(detector.process(0.001, longitudeForMeters(4.0), 724f))
        val second = detector.process(0.0, longitudeForMeters(6.0), 835f)
        assertEquals(2, second?.lapNumber)
        assertEquals(414f, second?.lapDistanceMeters)
    }

    @Test fun jitterNearStartNeverCreatesALap() {
        val detector = LapDetector()
        repeat(20) { index ->
            assertNull(detector.process(0.0, longitudeForMeters((index % 5).toDouble()), index * 10f))
        }
    }
}
