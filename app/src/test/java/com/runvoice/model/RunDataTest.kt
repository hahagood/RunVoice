package com.runvoice.model

import org.junit.Assert.assertEquals
import org.junit.Test

class RunDataTest {
    @Test fun averagePaceHasOneCanonicalCalculation() {
        val data = RunData(elapsedSeconds = 1_500, distanceMeters = 4_000f)
        assertEquals(375, data.averagePaceSecondsPerKm)
        assertEquals("6'15\"", data.averagePaceFormatted)
    }

    @Test fun zeroDistanceHasNoAveragePace() {
        val data = RunData(elapsedSeconds = 100, distanceMeters = 0f)
        assertEquals(0, data.averagePaceSecondsPerKm)
        assertEquals("--'--\"", data.averagePaceFormatted)
    }
}
