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

    @Test fun absorbsAShortGpsTailIntoTheClosingCircularPhase() {
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

        assertLayer(route, route.indices, 0)
    }

    @Test fun preservesAValidClosureWhenSparseSamplingJumpsPastTheGate() {
        val meter = 1.0 / 111_195.0
        val route = TraceGeometry().analyze(
            listOf(
                TracePoint(0.0, 0.0, 0f),
                TracePoint(0.0, 200 * meter, 200f),
                TracePoint(200 * meter, 200 * meter, 400f),
                TracePoint(200 * meter, 0.0, 600f),
                TracePoint(0.0, 20 * meter, 800f), // Valid closure, 20 m from the gate.
                TracePoint(-100 * meter, 100 * meter, 940f), // One sample jumps > 70 m away.
            )
        )

        assertLayer(route, 0..4, 0)
        assertEquals(1, route[5].repeatLevel)
    }

    @Test fun keepsIngressThreeLapsAndExitInFourContinuousPhases() {
        val trace = SyntheticTrace()
        trace.lineTo(300.0, 0.0)
        val lapRanges = mutableListOf<IntRange>()
        repeat(3) {
            val start = trace.points.lastIndex + 1
            trace.lineTo(400.0, 0.0)
            trace.lineTo(400.0, 100.0)
            trace.lineTo(300.0, 100.0)
            trace.lineTo(300.0, 0.0)
            lapRanges += start..trace.points.lastIndex
        }
        val exitStart = trace.points.lastIndex + 1
        trace.lineTo(200.0, 0.0) // Retraces the ingress corridor in reverse.
        trace.lineTo(200.0, -100.0) // Continues away from every earlier route.
        val exitRange = exitStart..trace.points.lastIndex

        val route = TraceGeometry().analyze(trace.points)

        assertLayer(route, 0..lapRanges[0].last, 0)
        assertLayer(route, lapRanges[1], 1)
        assertLayer(route, lapRanges[2], 2)
        assertLayer(route, exitRange, 3)
        assertEquals(listOf(0, 1, 2, 3), route.map(RoutePoint::repeatLevel).distinct())
    }

    @Test fun keepsTheTwoPointZeroEightKilometerShapeInThreeContinuousPhases() {
        val trace = SyntheticTrace(startX = -100.0, startY = 0.0)
        trace.lineTo(0.0, 0.0) // The historical gate lies just after the activity start.

        trace.lineTo(220.0, 0.0)
        trace.lineTo(220.0, 160.0)
        trace.lineTo(-60.0, 160.0)
        val firstClosureEnd = trace.lineTo(0.0, 0.0).last

        val stemAndProperLoopStart = trace.points.lastIndex + 1
        trace.lineTo(180.0, 0.0) // Stem from the discovered gate to the proper loop.
        trace.lineTo(320.0, 0.0)
        trace.lineTo(320.0, -160.0)
        trace.lineTo(180.0, -160.0)
        val stemAndProperLoopEnd = trace.lineTo(180.0, 0.0).last

        val tailStart = trace.points.lastIndex + 1
        trace.lineTo(0.0, 0.0) // The proper loop closes at X; X -> O is already the tail.
        trace.lineTo(-60.0, 0.0)
        val tail = tailStart..trace.points.lastIndex
        val route = TraceGeometry().analyze(trace.points)

        assertLayer(route, 0..firstClosureEnd, 0)
        assertLayer(route, stemAndProperLoopStart..stemAndProperLoopEnd, 1)
        assertLayer(route, tail, 2)
        assertEquals(listOf(0, 1, 2), route.map(RoutePoint::repeatLevel).distinct())
        val visualLanes = TraceLaneAllocator().allocate(route)
        assertTrue(visualLanes[stemAndProperLoopStart] != visualLanes[tail.first])
    }

    @Test fun ignoresAnEarlyLongDegenerateOutAndBackAsAPhaseBoundary() {
        val trace = SyntheticTrace()
        trace.lineTo(600.0, 0.0)
        trace.lineTo(0.0, 0.0) // Long enough by distance, but it encloses no proper loop area.

        trace.lineTo(200.0, 0.0)
        trace.lineTo(200.0, 200.0)
        trace.lineTo(-80.0, 200.0)
        trace.lineTo(-80.0, 0.0)
        val validClosureEnd = trace.lineTo(0.0, 0.0).last
        val nextPhase = trace.lineTo(0.0, -120.0)

        val route = TraceGeometry().analyze(trace.points)

        assertLayer(route, 0..validClosureEnd, 0)
        assertLayer(route, nextPhase, 1)
    }

    @Test fun ignoresAParallelGpsCorridorThatLooksLikeANarrowClosedLoop() {
        val trace = SyntheticTrace()
        trace.lineTo(600.0, 0.0)
        trace.lineTo(600.0, 15.0)
        trace.lineTo(0.0, 15.0)
        trace.lineTo(0.0, 0.0) // 9,000 m² and compact enough to pass the area-only filter.

        trace.lineTo(200.0, 0.0)
        trace.lineTo(200.0, 200.0)
        trace.lineTo(0.0, 200.0)
        val properLoopEnd = trace.lineTo(0.0, 0.0).last
        val exit = trace.lineTo(0.0, -120.0)

        val route = TraceGeometry().analyze(trace.points)

        val virtualGateStart = properLoopEnd - 3 // The 35 m closure gate may finish slightly early.
        assertLayer(route, 0 until virtualGateStart, 0)
        assertTrue(route.indexOfFirst { it.repeatLevel == 1 } >= virtualGateStart)
        assertEquals(1, route[exit.last].repeatLevel)
    }

    @Test fun rechecksReverseCoverageAtTheClosestApproach() {
        val trace = SyntheticTrace(sampleSpacingMeters = 10.0)
        trace.lineTo(200.0, 0.0)
        trace.lineTo(230.0, 15.0)
        trace.lineTo(0.0, 15.0)
        trace.lineTo(0.0, 0.0)
        trace.lineTo(-100.0, -100.0) // A tail would expose a wrongly committed closure.

        val route = TraceGeometry().analyze(trace.points)

        assertLayer(route, route.indices, 0)
    }

    @Test fun ignoresANarrowLocalLoopInsideAMuchLongerPhase() {
        val trace = SyntheticTrace()
        trace.lineTo(10_000.0, 0.0)
        trace.lineTo(10_150.0, 0.0)
        trace.lineTo(10_150.0, 5.0)
        trace.lineTo(10_000.0, 5.0)
        trace.lineTo(10_000.0, 0.0) // 310 m long, but only 750 m²: a degenerate sliver.

        trace.lineTo(10_000.0, 1_000.0)
        trace.lineTo(0.0, 1_000.0)
        val principalClosureEnd = trace.lineTo(0.0, 0.0).last
        val exit = trace.lineTo(0.0, -120.0)

        val route = TraceGeometry().analyze(trace.points)

        assertLayer(route, 0..principalClosureEnd, 0)
        assertLayer(route, exit, 1)
    }

    @Test fun detectsRepeatedLocalLapsAfterAVeryLongIngress() {
        val trace = SyntheticTrace()
        trace.lineTo(10_000.0, 0.0)
        val lapRanges = mutableListOf<IntRange>()
        repeat(3) {
            val start = trace.points.lastIndex + 1
            trace.lineTo(10_100.0, 0.0)
            trace.lineTo(10_100.0, 100.0)
            trace.lineTo(10_000.0, 100.0)
            trace.lineTo(10_000.0, 0.0)
            lapRanges += start..trace.points.lastIndex
        }
        val exitStart = trace.points.lastIndex + 1
        trace.lineTo(9_900.0, 0.0)
        trace.lineTo(9_900.0, -100.0)
        val exit = exitStart..trace.points.lastIndex

        val route = TraceGeometry().analyze(trace.points)

        assertLayer(route, 0..lapRanges[0].last, 0)
        assertLayer(route, lapRanges[1], 1)
        assertLayer(route, lapRanges[2], 2)
        assertLayer(route, exit, 3)
    }

    @Test fun activityStartReturnsAlongDifferentRoutesCreateFourContinuousPhases() {
        val trace = SyntheticTrace()

        val firstLoop = trace.appendLoop(
            200.0 to 0.0,
            200.0 to 150.0,
            0.0 to 150.0,
            0.0 to 0.0
        )
        val secondLoop = trace.appendLoop(
            0.0 to -220.0,
            -180.0 to -220.0,
            -180.0 to 0.0,
            0.0 to 0.0
        )
        val thirdLoop = trace.appendLoop(
            -250.0 to 0.0,
            -250.0 to 260.0,
            180.0 to 260.0,
            180.0 to 0.0,
            0.0 to 0.0
        )
        val exit = trace.lineTo(300.0, -300.0)

        val route = TraceGeometry().analyze(trace.points)

        assertLayer(route, firstLoop, 0)
        assertLayer(route, secondLoop, 1)
        assertLayer(route, thirdLoop, 2)
        assertLayer(route, exit, 3)
        assertEquals(listOf(0, 1, 2, 3), route.map(RoutePoint::repeatLevel).distinct())
    }

    @Test fun preservesEveryPhaseAndConnectorAcrossMultipleVenues() {
        val trace = SyntheticTrace()
        val connectors = mutableListOf<IntRange>()
        val venueLaps = mutableListOf<List<IntRange>>()
        listOf(1_000.0, 4_000.0, 7_000.0, 10_000.0).forEach { anchorX ->
            connectors += trace.lineTo(anchorX, 0.0)
            venueLaps += List(3) {
                trace.appendLoop(
                    (anchorX + 100.0) to 0.0,
                    (anchorX + 100.0) to 100.0,
                    anchorX to 100.0,
                    anchorX to 0.0,
                )
            }
        }
        val exit = trace.lineTo(10_000.0, -120.0)

        val route = TraceGeometry().analyze(trace.points)
        val visualLanes = TraceLaneAllocator().allocate(route)

        assertEquals((0..12).toList(), route.map(RoutePoint::repeatLevel).distinct())
        connectors.forEachIndexed { venueIndex, connector ->
            val firstPhaseAtVenue = venueIndex * 3
            assertLayer(route, connector, firstPhaseAtVenue)
            venueLaps[venueIndex].forEachIndexed { lapIndex, lap ->
                assertLayer(route, lap, firstPhaseAtVenue + lapIndex)
            }
        }
        assertLayer(route, exit, 12)
        venueLaps.forEach { laps ->
            assertEquals(3, laps.map { lap -> visualLanes[lap.first] }.distinct().size)
        }
        assertTrue(visualLanes.max() <= 3)
    }

    @Test fun shortTailKeepsTheMostRecentPhaseAfterManyLaps() {
        val trace = SyntheticTrace()
        repeat(8) {
            trace.appendLoop(
                100.0 to 0.0,
                100.0 to 100.0,
                0.0 to 100.0,
                0.0 to 0.0,
            )
        }
        val shortTail = trace.lineTo(30.0, 0.0)

        val route = TraceGeometry().analyze(trace.points)

        assertEquals(7, route[shortTail.first - 1].repeatLevel)
        assertLayer(route, shortTail, 7)
    }

    @Test fun twentyOverlappingLapsKeepTwentyDistinctVisualLanes() {
        val trace = SyntheticTrace()
        val laps = List(20) {
            trace.appendLoop(
                100.0 to 0.0,
                100.0 to 100.0,
                0.0 to 100.0,
                0.0 to 0.0,
            )
        }

        val route = TraceGeometry().analyze(trace.points)
        val visualLanes = TraceLaneAllocator().allocate(route)

        assertEquals((0 until 20).toList(), route.map(RoutePoint::repeatLevel).distinct())
        assertEquals((0 until 20).toList(), laps.map { lap -> visualLanes[lap.first] })
    }

    private fun assertLayer(route: List<RoutePoint>, range: IntRange, expected: Int) {
        val unexpected = range.firstOrNull { route[it].repeatLevel != expected }
        assertTrue(
            "Expected layer $expected in $range, first mismatch was index $unexpected " +
                "with layer ${unexpected?.let { route[it].repeatLevel }}",
            unexpected == null
        )
    }

    private class SyntheticTrace(
        startX: Double = 0.0,
        startY: Double = 0.0,
        private val sampleSpacingMeters: Double = 10.0
    ) {
        val points = mutableListOf<TracePoint>()
        private var x = startX
        private var y = startY
        private var totalDistance = 0f

        init {
            points += tracePointAtMeters(x, y, totalDistance)
        }

        fun lineTo(targetX: Double, targetY: Double): IntRange {
            val start = points.lastIndex + 1
            val dx = targetX - x
            val dy = targetY - y
            val length = kotlin.math.hypot(dx, dy)
            val steps = (length / sampleSpacingMeters).toInt().coerceAtLeast(1)
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
            return start..points.lastIndex
        }

        fun appendLoop(vararg vertices: Pair<Double, Double>): IntRange {
            val start = points.lastIndex + 1
            vertices.forEach { (targetX, targetY) -> lineTo(targetX, targetY) }
            return start..points.lastIndex
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
}
