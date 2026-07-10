package com.runvoice.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceGeometryTest {
    @Test fun projectsCoordinatesAndPreservesDistance() {
        val route = TraceGeometry().analyze(
            listOf(
                TracePoint(30.0, 114.0, 0f),
                TracePoint(30.0, 114.001, 96f)
            )
        )
        assertEquals(2, route.size)
        assertTrue(route[1].xMeters > route[0].xMeters)
        assertEquals(96f, route[1].distanceMeters)
        assertEquals(0, route[1].repeatLevel)
    }
}
