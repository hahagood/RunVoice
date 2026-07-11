package com.runvoice.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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

    @Test fun assignsEachClosedLapToAHigherVisualLayer() {
        val meter = 1.0 / 111_195.0
        val route = TraceGeometry().analyze(
            listOf(
                TracePoint(0.0, 0.0, 0f),
                TracePoint(0.0, 100 * meter, 100f),
                TracePoint(100 * meter, 100 * meter, 200f),
                TracePoint(100 * meter, 0.0, 300f),
                TracePoint(0.0, 2 * meter, 402f),
                TracePoint(0.0, 100 * meter, 500f),
                TracePoint(100 * meter, 100 * meter, 600f),
                TracePoint(100 * meter, 0.0, 700f),
                TracePoint(0.0, 3 * meter, 803f),
                TracePoint(0.0, 100 * meter, 900f)
            )
        )

        assertEquals(0, route[4].repeatLevel)
        assertEquals(1, route[5].repeatLevel)
        assertEquals(1, route[8].repeatLevel)
        assertEquals(2, route[9].repeatLevel)
    }

    @Test fun splitsAnOutAndBackLapIntoOutboundAndReturnLayers() {
        val meter = 1.0 / 111_195.0
        val points = mutableListOf<TracePoint>()
        var distance = 0f
        points += TracePoint(0.0, 0.0, distance)
        for (x in 20..200 step 20) {
            distance += 20f
            points += TracePoint(0.0, x * meter, distance)
        }
        for (x in 180 downTo 0 step 20) {
            distance += 20f
            points += TracePoint(0.0, x * meter, distance)
        }
        for (x in 20..80 step 20) {
            distance += 20f
            points += TracePoint(0.0, x * meter, distance)
        }

        val route = TraceGeometry().analyze(points)

        assertEquals(0, route.first { it.distanceMeters == 100f }.repeatLevel)
        assertEquals(1, route.first { it.distanceMeters == 300f }.repeatLevel)
        assertEquals(2, route.first { it.distanceMeters == 420f }.repeatLevel)
    }

    @Test fun doesNotSplitTheTwoHalvesOfAnOrdinaryCircularLap() {
        val meter = 1.0 / 111_195.0
        val points = mutableListOf<TracePoint>()
        var distance = 0f
        for (degree in 0..360 step 10) {
            if (degree > 0) distance += (2 * PI * 100 / 36).toFloat()
            val angle = Math.toRadians(degree.toDouble())
            points += TracePoint(
                latitude = sin(angle) * 100 * meter,
                longitude = cos(angle) * 100 * meter,
                distanceMeters = distance
            )
        }
        distance += (2 * PI * 100 / 36).toFloat()
        points += TracePoint(
            sin(Math.toRadians(10.0)) * 100 * meter,
            cos(Math.toRadians(10.0)) * 100 * meter,
            distance
        )

        val route = TraceGeometry().analyze(points)

        assertTrue(route.take(35).all { it.repeatLevel == 0 })
        assertTrue(route.drop(35).all { it.repeatLevel == 1 })
    }

    @Test fun keepsExitAfterThreeLocalLapsOnAFourthLayer() {
        val points = mutableListOf<TracePoint>()
        var totalDistance = 0f
        var x = 0.0
        var y = 0.0
        points += tracePointAtMeters(x, y, totalDistance)

        fun lineTo(targetX: Double, targetY: Double) {
            val dx = targetX - x
            val dy = targetY - y
            val length = kotlin.math.hypot(dx, dy)
            val steps = (length / 10.0).toInt().coerceAtLeast(1)
            val startX = x
            val startY = y
            repeat(steps) { step ->
                val progress = (step + 1).toDouble() / steps
                val nextX = startX + dx * progress
                val nextY = startY + dy * progress
                totalDistance += kotlin.math.hypot(nextX - x, nextY - y).toFloat()
                x = nextX
                y = nextY
                points += tracePointAtMeters(x, y, totalDistance)
            }
        }

        lineTo(300.0, 0.0)
        val lapRanges = mutableListOf<IntRange>()
        repeat(3) {
            val start = points.lastIndex + 1
            lineTo(400.0, 0.0)
            lineTo(400.0, 100.0)
            lineTo(300.0, 100.0)
            lineTo(300.0, 0.0)
            lapRanges += start..points.lastIndex
        }
        val exitStart = points.lastIndex + 1
        lineTo(200.0, 0.0) // Retraces the ingress corridor in reverse.
        val exitOverlapEnd = points.lastIndex
        lineTo(200.0, -100.0) // Continues away from every earlier route.

        val route = TraceGeometry().analyze(points)

        assertEquals(0, route[10].repeatLevel)
        assertEquals(0, route[lapRanges[0].first + lapRanges[0].count() / 2].repeatLevel)
        assertEquals(1, route[lapRanges[1].first + lapRanges[1].count() / 2].repeatLevel)
        assertEquals(2, route[lapRanges[2].first + lapRanges[2].count() / 2].repeatLevel)
        assertTrue(route.slice((exitStart + 4)..exitOverlapEnd).all { it.repeatLevel == 3 })
        assertTrue(route.drop(exitOverlapEnd + 1).all { it.repeatLevel == 3 })
    }

    private fun tracePointAtMeters(x: Double, y: Double, distanceMeters: Float): TracePoint {
        val metersPerDegree = 111_195.0
        return TracePoint(
            latitude = y / metersPerDegree,
            longitude = x / metersPerDegree,
            distanceMeters = distanceMeters
        )
    }
}
