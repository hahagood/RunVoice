package com.runvoice.share

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sqrt

internal data class RoutePoint(
    val xMeters: Double,
    val yMeters: Double,
    val distanceMeters: Float,
    val repeatLevel: Int
)

/** Geographic projection and chronological closed-route phases, independent from rendering. */
internal class TraceGeometry {
    fun analyze(points: List<TracePoint>): List<RoutePoint> {
        if (points.isEmpty()) return emptyList()
        val projected = projectToMeters(points)
        val levels = detectPhaseLevels(projected)
        return projected.mapIndexed { index, point -> point.copy(repeatLevel = levels[index]) }
    }

    /**
     * A poster layer is a complete chronological phase, not a per-edge overlap count. Each phase
     * ends when the route completes a non-degenerate closed walk inside that phase. Restricting a
     * new loop's anchor to the current phase is important: after an inner loop closes, a later
     * return to an older outer anchor is composite and must not swallow the inner boundary.
     */
    private fun detectPhaseLevels(points: List<RoutePoint>): IntArray {
        if (points.size < 3) return IntArray(points.size)

        val areaPrefix = signedAreaPrefix(points)
        val historyGrid = mutableMapOf<GridKey, MutableList<Int>>()
        val completionIndices = mutableListOf<Int>()
        var phaseStartIndex = 0
        var approach: ClosureApproach? = null

        points.forEachIndexed { endIndex, point ->
            var completedIndex: Int? = null
            val activeApproach = approach
            if (activeApproach != null && activeApproach.anchorIndex >= phaseStartIndex) {
                val anchor = points[activeApproach.anchorIndex]
                val distanceSquared = squaredDistance(point, anchor)
                val validAtCurrent = hasValidLoopShape(
                    points = points,
                    areaPrefix = areaPrefix,
                    startIndex = activeApproach.anchorIndex,
                    endIndex = endIndex,
                    closureDistanceSquared = distanceSquared,
                )

                if (validAtCurrent && distanceSquared < activeApproach.bestDistanceSquared) {
                    activeApproach.bestDistanceSquared = distanceSquared
                    activeApproach.bestEndIndex = endIndex
                    activeApproach.movingAwaySamples = 0
                } else if (sqrt(distanceSquared) >=
                    sqrt(activeApproach.bestDistanceSquared) + CLOSEST_APPROACH_RISE_METERS
                ) {
                    activeApproach.movingAwaySamples++
                } else {
                    activeApproach.movingAwaySamples = 0
                }

                completedIndex = when {
                    validAtCurrent && distanceSquared <= IMMEDIATE_CLOSE_DISTANCE_SQUARED -> endIndex
                    activeApproach.movingAwaySamples >= CLOSEST_APPROACH_CONFIRMATION_SAMPLES ->
                        activeApproach.bestEndIndex
                    else -> null
                }

                if (completedIndex == null && distanceSquared > ABANDON_APPROACH_DISTANCE_SQUARED) {
                    // Sparse GPS sampling can jump straight from inside the virtual gate to well
                    // outside it. The best point was already a validated closure, so preserve it.
                    completedIndex = activeApproach.bestEndIndex
                }

                if (completedIndex != null && isDegenerateOutAndBack(
                        points,
                        activeApproach.anchorIndex,
                        completedIndex,
                    )
                ) {
                    // The extra samples inside the gate can push a borderline route over the
                    // reverse-corridor threshold. Judge the actual closest closure before commit.
                    completedIndex = null
                    approach = null
                }
            } else {
                approach = null
            }

            if (completedIndex == null && approach == null) {
                findBestLoopCandidate(
                    points = points,
                    areaPrefix = areaPrefix,
                    historyGrid = historyGrid,
                    phaseStartIndex = phaseStartIndex,
                    endIndex = endIndex,
                )?.let { candidate ->
                    if (candidate.closureDistanceSquared <= IMMEDIATE_CLOSE_DISTANCE_SQUARED) {
                        completedIndex = endIndex
                    } else {
                        approach = ClosureApproach(
                            anchorIndex = candidate.startIndex,
                            bestEndIndex = endIndex,
                            bestDistanceSquared = candidate.closureDistanceSquared,
                        )
                    }
                }
            }

            if (completedIndex != null && completedIndex > phaseStartIndex) {
                completionIndices += completedIndex
                phaseStartIndex = completedIndex
                approach = null
                // Old phases can no longer supply a valid anchor. Keeping only the closing point
                // and any confirmation samples prevents dense multi-lap routes trending O(n²).
                historyGrid.clear()
                for (retainedIndex in completedIndex until endIndex) {
                    val retainedPoint = points[retainedIndex]
                    historyGrid.getOrPut(gridKey(retainedPoint)) { mutableListOf() }
                        .add(retainedIndex)
                }
            }

            historyGrid.getOrPut(gridKey(point)) { mutableListOf() }.add(endIndex)
        }

        // A run can finish while still inside the virtual gate, before moving-away confirmation.
        approach?.takeIf { it.anchorIndex >= phaseStartIndex }?.let { pending ->
            val endIndex = pending.bestEndIndex
            if (endIndex > phaseStartIndex &&
                !isDegenerateOutAndBack(points, pending.anchorIndex, endIndex)
            ) completionIndices += endIndex
        }

        return levelsFromCompletions(points, completionIndices.distinct())
    }

    private fun findBestLoopCandidate(
        points: List<RoutePoint>,
        areaPrefix: DoubleArray,
        historyGrid: Map<GridKey, List<Int>>,
        phaseStartIndex: Int,
        endIndex: Int,
    ): LoopCandidate? {
        val point = points[endIndex]
        val key = gridKey(point)
        val candidates = mutableListOf<LoopCandidate>()

        for (offsetX in -1..1) for (offsetY in -1..1) {
            historyGrid[GridKey(key.x + offsetX, key.y + offsetY)]?.forEach { startIndex ->
                if (startIndex < phaseStartIndex) return@forEach
                val closureDistanceSquared = squaredDistance(point, points[startIndex])
                if (!hasValidLoopShape(
                        points,
                        areaPrefix,
                        startIndex,
                        endIndex,
                        closureDistanceSquared,
                    )
                ) return@forEach

                candidates += LoopCandidate(startIndex, closureDistanceSquared)
            }
        }

        return candidates
            .sortedWith(
                compareByDescending<LoopCandidate> { it.startIndex == phaseStartIndex }
                    .thenBy { it.closureDistanceSquared }
                    .thenByDescending { it.startIndex }
            )
            .firstOrNull { candidate ->
                !isDegenerateOutAndBack(points, candidate.startIndex, endIndex)
            }
    }

    private fun hasValidLoopShape(
        points: List<RoutePoint>,
        areaPrefix: DoubleArray,
        startIndex: Int,
        endIndex: Int,
        closureDistanceSquared: Double,
    ): Boolean {
        if (startIndex >= endIndex || closureDistanceSquared > CLOSE_DISTANCE_SQUARED) return false
        val loopLength = points[endIndex].distanceMeters - points[startIndex].distanceMeters
        if (loopLength < MIN_LOOP_DISTANCE_METERS) return false
        val twiceSignedOpenArea = areaPrefix[endIndex] - areaPrefix[startIndex]
        val closingEdgeArea = cross(points[endIndex], points[startIndex])
        val enclosedArea = abs(twiceSignedOpenArea + closingEdgeArea) / 2.0
        val compactness = 4.0 * PI * enclosedArea / (loopLength * loopLength)
        return enclosedArea >= MIN_ENCLOSED_AREA_SQUARE_METERS &&
            compactness >= MIN_LOOP_COMPACTNESS
    }

    /** Reject a narrow A-B-A corridor: it closes geometrically but is not a visual lap. */
    private fun isDegenerateOutAndBack(
        points: List<RoutePoint>,
        startIndex: Int,
        endIndex: Int,
    ): Boolean {
        if (endIndex - startIndex < MIN_OUT_AND_BACK_POINTS) return false
        val loopDistance = points[endIndex].distanceMeters - points[startIndex].distanceMeters
        if (loopDistance < MIN_REVERSE_CORRIDOR_DISTANCE_GAP_METERS * 2) return false
        val interior = ((startIndex + 1) until endIndex).filter { index ->
            points[index].distanceMeters - points[startIndex].distanceMeters >=
                OUT_AND_BACK_ENDPOINT_EXCLUSION_METERS &&
                points[endIndex].distanceMeters - points[index].distanceMeters >=
                OUT_AND_BACK_ENDPOINT_EXCLUSION_METERS
        }
        if (interior.size < MIN_OUT_AND_BACK_COMPARISONS) return false
        val sourceGrid = mutableMapOf<GridKey, MutableList<Int>>()
        interior.forEach { index ->
            sourceGrid.getOrPut(gridKey(points[index])) { mutableListOf() }.add(index)
        }

        var comparedDistance = 0.0
        var matchedDistance = 0.0
        interior.forEach { probeIndex ->
            val sampleDistance = (points[probeIndex].distanceMeters -
                points[(probeIndex - 1).coerceAtLeast(0)].distanceMeters).coerceAtLeast(0f)
            if (sampleDistance <= 0f) return@forEach
            comparedDistance += sampleDistance
            if (hasOppositeCorridorMatch(points, sourceGrid, probeIndex)) {
                matchedDistance += sampleDistance
            }
        }
        val coverage = if (comparedDistance > 0.0) {
            matchedDistance / comparedDistance
        } else {
            0.0
        }
        return coverage >= MIN_OUT_AND_BACK_MATCH_RATIO
    }

    private fun hasOppositeCorridorMatch(
        points: List<RoutePoint>,
        sourceGrid: Map<GridKey, List<Int>>,
        probeIndex: Int,
    ): Boolean {
        val probe = points[probeIndex]
        val probeVector = directionVector(points, probeIndex)
        if (probeVector.length() <= MIN_DIRECTION_VECTOR_METERS) return false
        val key = gridKey(probe)
        for (offsetX in -1..1) for (offsetY in -1..1) {
            sourceGrid[GridKey(key.x + offsetX, key.y + offsetY)]?.forEach { sourceIndex ->
                if (abs(points[probeIndex].distanceMeters - points[sourceIndex].distanceMeters) <
                    MIN_REVERSE_CORRIDOR_DISTANCE_GAP_METERS
                ) return@forEach
                if (squaredDistance(probe, points[sourceIndex]) > CLOSE_DISTANCE_SQUARED) {
                    return@forEach
                }
                val sourceVector = directionVector(points, sourceIndex)
                if (sourceVector.length() <= MIN_DIRECTION_VECTOR_METERS) return@forEach
                val cosine = probeVector.dot(sourceVector) /
                    (probeVector.length() * sourceVector.length())
                if (cosine <= -MIN_DIRECTION_ALIGNMENT) return true
            }
        }
        return false
    }

    private fun levelsFromCompletions(
        points: List<RoutePoint>,
        completionIndices: List<Int>,
    ): IntArray {
        val levels = IntArray(points.size)
        var rangeStart = 0
        var level = 0
        completionIndices.sorted().forEach { completionIndex ->
            if (completionIndex < rangeStart) return@forEach
            fillLevels(levels, rangeStart, completionIndex, level)
            rangeStart = completionIndex + 1
            level++
        }

        if (rangeStart < levels.size) {
            val lastCompletionIndex = completionIndices.maxOrNull()
            val tailDistance = lastCompletionIndex?.let {
                points.last().distanceMeters - points[it].distanceMeters
            } ?: 0f
            val tailLevel = if (lastCompletionIndex != null && tailDistance < MIN_EXIT_PHASE_METERS) {
                levels[lastCompletionIndex]
            } else {
                level
            }
            fillLevels(levels, rangeStart, levels.lastIndex, tailLevel)
        }
        return levels
    }

    private fun fillLevels(levels: IntArray, start: Int, end: Int, level: Int) {
        if (start > end || start !in levels.indices) return
        for (index in start..end.coerceAtMost(levels.lastIndex)) levels[index] = level
    }

    private fun signedAreaPrefix(points: List<RoutePoint>): DoubleArray {
        val prefix = DoubleArray(points.size)
        for (index in 1 until points.size) {
            prefix[index] = prefix[index - 1] + cross(points[index - 1], points[index])
        }
        return prefix
    }

    private fun cross(first: RoutePoint, second: RoutePoint): Double =
        first.xMeters * second.yMeters - second.xMeters * first.yMeters

    private fun squaredDistance(first: RoutePoint, second: RoutePoint): Double {
        val dx = first.xMeters - second.xMeters
        val dy = first.yMeters - second.yMeters
        return dx * dx + dy * dy
    }

    private fun directionVector(points: List<RoutePoint>, index: Int): Vector {
        val start = (index - DIRECTION_SAMPLE_RADIUS).coerceAtLeast(0)
        val end = (index + DIRECTION_SAMPLE_RADIUS).coerceAtMost(points.lastIndex)
        return Vector(
            x = points[end].xMeters - points[start].xMeters,
            y = points[end].yMeters - points[start].yMeters,
        )
    }

    private fun gridKey(point: RoutePoint): GridKey = GridKey(
        floor(point.xMeters / CELL_SIZE_METERS).toInt(),
        floor(point.yMeters / CELL_SIZE_METERS).toInt(),
    )

    private fun projectToMeters(points: List<TracePoint>): List<RoutePoint> {
        val centerLatitude = points.map { it.latitude }.average()
        val centerLongitude = points.map { it.longitude }.average()
        val longitudeMeterScale = METERS_PER_DEGREE * cos(Math.toRadians(centerLatitude))
        return points.map { point ->
            RoutePoint(
                xMeters = (point.longitude - centerLongitude) * longitudeMeterScale,
                yMeters = (point.latitude - centerLatitude) * METERS_PER_DEGREE,
                distanceMeters = point.distanceMeters,
                repeatLevel = 0,
            )
        }
    }

    private data class LoopCandidate(
        val startIndex: Int,
        val closureDistanceSquared: Double,
    )

    private data class ClosureApproach(
        val anchorIndex: Int,
        var bestEndIndex: Int,
        var bestDistanceSquared: Double,
        var movingAwaySamples: Int = 0,
    )

    private data class Vector(val x: Double, val y: Double) {
        fun length(): Double = hypot(x, y)
        fun dot(other: Vector): Double = x * other.x + y * other.y
    }

    private data class GridKey(val x: Int, val y: Int)

    private companion object {
        const val METERS_PER_DEGREE = 111_320.0
        const val CELL_SIZE_METERS = 40.0
        const val CLOSE_DISTANCE_SQUARED = 35.0 * 35.0
        const val IMMEDIATE_CLOSE_DISTANCE_SQUARED = 10.0 * 10.0
        const val ABANDON_APPROACH_DISTANCE_SQUARED = 70.0 * 70.0
        const val CLOSEST_APPROACH_RISE_METERS = 4.0
        const val CLOSEST_APPROACH_CONFIRMATION_SAMPLES = 2
        const val MIN_LOOP_DISTANCE_METERS = 300f
        const val MIN_ENCLOSED_AREA_SQUARE_METERS = 1_000.0
        const val MIN_LOOP_COMPACTNESS = 0.05
        const val MIN_OUT_AND_BACK_POINTS = 10
        const val MIN_REVERSE_CORRIDOR_DISTANCE_GAP_METERS = 120f
        const val OUT_AND_BACK_ENDPOINT_EXCLUSION_METERS = 30f
        const val MIN_OUT_AND_BACK_COMPARISONS = 4
        const val MIN_OUT_AND_BACK_MATCH_RATIO = 0.65
        const val MIN_DIRECTION_ALIGNMENT = 0.5
        const val MIN_DIRECTION_VECTOR_METERS = 0.1
        const val DIRECTION_SAMPLE_RADIUS = 2
        const val MIN_EXIT_PHASE_METERS = 60f
    }
}
