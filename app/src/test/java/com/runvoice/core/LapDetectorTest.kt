package com.runvoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertTrue(first!!.lapDistanceMeters in 417f..421f)

        assertNull(detector.process(0.0, longitudeForMeters(100.0), 517f))
        assertNull(detector.process(0.001, longitudeForMeters(100.0), 628f))
        assertNull(detector.process(0.001, longitudeForMeters(4.0), 724f))
        assertNull(detector.process(0.0, longitudeForMeters(6.0), 835f))
        assertNull(detector.process(0.0, longitudeForMeters(12.0), 841f))
        val second = detector.process(0.0, longitudeForMeters(20.0), 849f)
        assertEquals(2, second?.lapNumber)
        assertTrue(second!!.lapDistanceMeters in 410f..418f)
    }

    @Test fun jitterNearStartNeverCreatesALap() {
        val detector = LapDetector()
        repeat(20) { index ->
            assertNull(detector.process(0.0, longitudeForMeters((index % 5).toDouble()), index * 10f))
        }
    }

    @Test fun locksTheHistoricalAnchorInsteadOfMovingItForwardEveryLap() {
        val detector = LapDetector()
        var total = 0f
        fun point(x: Double, y: Double, delta: Float): LapCompletion? {
            total += delta
            return detector.process(y / 111_195.0, longitudeForMeters(x), total)
        }

        assertNull(point(0.0, 0.0, 0f))
        val completions = mutableListOf<LapCompletion>()
        repeat(4) {
            assertNull(point(100.0, 0.0, 100f))
            assertNull(point(100.0, 100.0, 100f))
            assertNull(point(0.0, 100.0, 100f))
            point(2.0, 0.0, 100f)?.let(completions::add)
        }

        assertEquals(4, completions.size)
        assertTrue(completions.all { it.lapDistanceMeters in 395f..405f })
        assertTrue(completions.all { it.totalDistanceMeters % 400f < 6f })
    }

    @Test fun discoversAndLocksALocalLoopAfterAnIngress() {
        val detector = LapDetector()
        var total = 0f
        fun point(x: Double, y: Double, delta: Float): LapCompletion? {
            total += delta
            return detector.process(y / 111_195.0, longitudeForMeters(x), total)
        }

        assertNull(point(0.0, -50.0, 0f))
        assertNull(point(100.0, 0.0, 112f)) // Local loop entrance, not activity start.
        assertNull(point(200.0, 0.0, 100f))
        assertNull(point(200.0, 100.0, 100f))
        assertNull(point(100.0, 100.0, 100f))
        assertNull(point(0.0, 100.0, 100f))
        assertNull(point(0.0, 0.0, 100f))
        val first = point(102.0, 0.0, 102f)

        assertEquals(1, first?.lapNumber)
        assertTrue(first!!.lapDistanceMeters in 595f..605f)

        assertNull(point(200.0, 0.0, 100f))
        assertNull(point(200.0, 100.0, 100f))
        assertNull(point(100.0, 100.0, 100f))
        assertNull(point(0.0, 100.0, 100f))
        assertNull(point(0.0, 0.0, 100f))
        val second = point(104.0, 0.0, 104f)
        assertEquals(2, second?.lapNumber)
        assertTrue(second!!.lapDistanceMeters in 595f..610f)
    }

    @Test fun reverseOverlapDoesNotCreateAMidRouteAnchorForAnOutAndBack() {
        val detector = LapDetector()
        var total = 0f
        fun point(x: Double): LapCompletion? {
            val delta = if (total == 0f && x == 0.0) 0f else 50f
            total += delta
            return detector.process(0.0, longitudeForMeters(x), total)
        }

        assertNull(point(0.0))
        for (x in 50..300 step 50) assertNull(point(x.toDouble()))
        for (x in 250 downTo 50 step 50) assertNull(point(x.toDouble()))
        val completion = point(0.0)

        assertEquals(1, completion?.lapNumber)
        assertTrue(completion!!.lapDistanceMeters in 595f..605f)
    }
}
