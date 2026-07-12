package com.runvoice.share

import com.runvoice.core.LapDetector
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

internal data class RoutePoint(
    val xMeters: Double,
    val yMeters: Double,
    val distanceMeters: Float,
    val repeatLevel: Int
)

/** Geographic projection and repeated-route classification, independent from Canvas rendering. */
internal class TraceGeometry {
    fun analyze(points: List<TracePoint>): List<RoutePoint> {
        if (points.isEmpty()) return emptyList()
        val projected = projectToMeters(points)
        val lapLevels = detectClosedLapLevels(points, projected)
        val levels = lapLevels ?: promoteTrailingExitPhase(projected, detectRepeatLevels(projected))
        return projected.mapIndexed { index, point -> point.copy(repeatLevel = levels[index]) }
    }

    /**
     * Spatial repeat levels can fall back to zero after the final local lap because the exit is
     * shorter than a normal repeated run. If that tail starts by following a previously travelled
     * corridor, keep the entire remaining route on a new layer so it cannot merge with the ingress.
     */
    private fun promoteTrailingExitPhase(points: List<RoutePoint>, levels: IntArray): IntArray {
        if (points.size < 3 || levels.size != points.size) return levels
        val highestLevel = levels.maxOrNull() ?: return levels
        if (highestLevel < MIN_LEVELS_BEFORE_EXIT_PHASE) return levels
        if ((1..highestLevel).any { expected -> expected !in levels }) return levels

        val lastHighestIndex = levels.indexOfLast { it == highestLevel }
        val exitStartIndex = lastHighestIndex + 1
        if (exitStartIndex !in points.indices) return levels
        val exitDistance = points.last().distanceMeters - points[exitStartIndex].distanceMeters
        if (exitDistance < MIN_EXIT_PHASE_METERS) return levels
        if (!startsAlongHistoricalRoute(points, exitStartIndex)) return levels

        val promoted = levels.copyOf()
        val exitLevel = (highestLevel + 1).coerceAtMost(MAX_REPEAT_LEVEL)
        for (index in exitStartIndex..promoted.lastIndex) promoted[index] = exitLevel
        return promoted
    }

    private fun startsAlongHistoricalRoute(points: List<RoutePoint>, exitStartIndex: Int): Boolean {
        val vectors = points.indices.map { directionVector(points, it) }
        val historyGrid = mutableMapOf<GridKey, MutableList<Int>>()
        for (index in 0 until exitStartIndex) {
            historyGrid.getOrPut(gridKey(points[index])) { mutableListOf() }.add(index)
        }

        val startDistance = points[exitStartIndex].distanceMeters
        var compared = 0
        var matched = 0
        var matchedSpan = 0f
        for (index in exitStartIndex..points.lastIndex) {
            val probeDistance = points[index].distanceMeters - startDistance
            if (probeDistance > EXIT_OVERLAP_PROBE_METERS) break
            compared++
            val point = points[index]
            val key = gridKey(point)
            var hasMatch = false
            for (offsetX in -1..1) for (offsetY in -1..1) {
                historyGrid[GridKey(key.x + offsetX, key.y + offsetY)]?.forEach { candidateIndex ->
                    if (hasMatch) return@forEach
                    val candidate = points[candidateIndex]
                    if (point.distanceMeters - candidate.distanceMeters < MIN_DISTANCE_GAP_METERS) return@forEach
                    val dx = point.xMeters - candidate.xMeters
                    val dy = point.yMeters - candidate.yMeters
                    if (dx * dx + dy * dy > CLOSE_DISTANCE_SQUARED) return@forEach
                    val currentVector = vectors[index]
                    val candidateVector = vectors[candidateIndex]
                    if (currentVector.length() > 0.1 && candidateVector.length() > 0.1) {
                        val cosine = currentVector.dot(candidateVector) /
                            (currentVector.length() * candidateVector.length())
                        if (abs(cosine) < MIN_DIRECTION_ALIGNMENT) return@forEach
                    }
                    hasMatch = true
                }
            }
            if (hasMatch) {
                matched++
                matchedSpan = probeDistance
            }
        }

        return compared >= MIN_EXIT_OVERLAP_SAMPLES &&
            matched.toFloat() / compared >= MIN_EXIT_OVERLAP_RATIO &&
            matchedSpan >= MIN_EXIT_MATCHED_SPAN_METERS
    }

    /**
     * A completed return to the start gives stronger information than local overlap matching: the
     * whole following lap can be lifted as one visual layer. If the two halves of a lap occupy the
     * same corridor in opposite directions, they are instead treated as separate outbound/return
     * layers so an A-B-A shuttle remains legible in the poster.
     */
    private fun detectClosedLapLevels(points: List<TracePoint>, projected: List<RoutePoint>): IntArray? {
        val detector = LapDetector()
        val lapEndDistances = mutableListOf<Float>()
        points.forEach { point ->
            detector.process(point.latitude, point.longitude, point.distanceMeters)?.let {
                lapEndDistances += it.totalDistanceMeters
            }
        }
        val lapEndIndices = lapEndDistances.map { completionDistance ->
            points.indices.minBy { index -> abs(points[index].distanceMeters - completionDistance) }
        }.distinct()
        if (lapEndIndices.isEmpty()) return null

        val levels = IntArray(points.size)
        var lapStartIndex = 0
        var unassignedStartIndex = 0
        var currentLevel = 0
        lapEndIndices.forEach { lapEndIndex ->
            val turnIndex = detectOutAndBackTurn(projected, lapStartIndex, lapEndIndex)
            if (turnIndex != null) {
                fillLevels(levels, unassignedStartIndex, turnIndex, currentLevel)
                fillLevels(levels, maxOf(unassignedStartIndex, turnIndex + 1), lapEndIndex, currentLevel + 1)
                currentLevel += 2
            } else {
                fillLevels(levels, unassignedStartIndex, lapEndIndex, currentLevel)
                currentLevel++
            }
            lapStartIndex = lapEndIndex
            unassignedStartIndex = lapEndIndex + 1
        }
        fillLevels(levels, unassignedStartIndex, levels.lastIndex, currentLevel)
        return levels
    }

    private fun fillLevels(levels: IntArray, start: Int, end: Int, level: Int) {
        if (start > end || start !in levels.indices) return
        for (index in start..end.coerceAtMost(levels.lastIndex)) {
            levels[index] = level.coerceAtMost(MAX_REPEAT_LEVEL)
        }
    }

    private fun detectOutAndBackTurn(points: List<RoutePoint>, startIndex: Int, endIndex: Int): Int? {
        if (endIndex - startIndex < MIN_OUT_AND_BACK_POINTS) return null
        val anchor = points[startIndex]
        val turnIndex = (startIndex + 1 until endIndex).maxByOrNull { index ->
            val dx = points[index].xMeters - anchor.xMeters
            val dy = points[index].yMeters - anchor.yMeters
            dx * dx + dy * dy
        } ?: return null
        val outboundDistance = points[turnIndex].distanceMeters - points[startIndex].distanceMeters
        val returnDistance = points[endIndex].distanceMeters - points[turnIndex].distanceMeters
        if (outboundDistance < MIN_OUT_AND_BACK_LEG_METERS || returnDistance < MIN_OUT_AND_BACK_LEG_METERS) {
            return null
        }

        val vectors = points.indices.map { directionVector(points, it) }
        val outboundGrid = mutableMapOf<GridKey, MutableList<Int>>()
        for (index in (startIndex + 1) until turnIndex) {
            val distanceFromStart = points[index].distanceMeters - points[startIndex].distanceMeters
            val distanceToTurn = points[turnIndex].distanceMeters - points[index].distanceMeters
            if (distanceFromStart < OUT_AND_BACK_ENDPOINT_EXCLUSION_METERS ||
                distanceToTurn < OUT_AND_BACK_ENDPOINT_EXCLUSION_METERS
            ) continue
            val point = points[index]
            outboundGrid.getOrPut(gridKey(point)) { mutableListOf() }.add(index)
        }

        var compared = 0
        var oppositeMatches = 0
        for (index in (turnIndex + 1) until endIndex) {
            val distanceFromTurn = points[index].distanceMeters - points[turnIndex].distanceMeters
            val distanceToEnd = points[endIndex].distanceMeters - points[index].distanceMeters
            if (distanceFromTurn < OUT_AND_BACK_ENDPOINT_EXCLUSION_METERS ||
                distanceToEnd < OUT_AND_BACK_ENDPOINT_EXCLUSION_METERS
            ) continue
            compared++
            val point = points[index]
            val key = gridKey(point)
            var matched = false
            for (offsetX in -1..1) for (offsetY in -1..1) {
                outboundGrid[GridKey(key.x + offsetX, key.y + offsetY)]?.forEach { candidateIndex ->
                    if (matched) return@forEach
                    val candidate = points[candidateIndex]
                    val dx = point.xMeters - candidate.xMeters
                    val dy = point.yMeters - candidate.yMeters
                    if (dx * dx + dy * dy > CLOSE_DISTANCE_SQUARED) return@forEach
                    val currentVector = vectors[index]
                    val candidateVector = vectors[candidateIndex]
                    if (currentVector.length() <= 0.1 || candidateVector.length() <= 0.1) return@forEach
                    val cosine = currentVector.dot(candidateVector) /
                        (currentVector.length() * candidateVector.length())
                    if (cosine <= -MIN_DIRECTION_ALIGNMENT) matched = true
                }
            }
            if (matched) oppositeMatches++
        }

        if (compared < MIN_OUT_AND_BACK_COMPARISONS) return null
        return turnIndex.takeIf {
            oppositeMatches.toFloat() / compared >= MIN_OUT_AND_BACK_MATCH_RATIO
        }
    }

    private fun gridKey(point: RoutePoint): GridKey {
        return GridKey(
            floor(point.xMeters / CELL_SIZE_METERS).toInt(),
            floor(point.yMeters / CELL_SIZE_METERS).toInt()
        )
    }

    private fun projectToMeters(points: List<TracePoint>): List<RoutePoint> {
        val centerLatitude = points.map { it.latitude }.average()
        val centerLongitude = points.map { it.longitude }.average()
        val longitudeMeterScale = METERS_PER_DEGREE * cos(Math.toRadians(centerLatitude))
        return points.map { point ->
            RoutePoint(
                xMeters = (point.longitude - centerLongitude) * longitudeMeterScale,
                yMeters = (point.latitude - centerLatitude) * METERS_PER_DEGREE,
                distanceMeters = point.distanceMeters,
                repeatLevel = 0
            )
        }
    }

    private fun detectRepeatLevels(points: List<RoutePoint>): IntArray {
        if (points.size < 3) return IntArray(points.size)
        val rawLevels = IntArray(points.size)
        val vectors = points.indices.map { directionVector(points, it) }
        val grid = mutableMapOf<GridKey, MutableList<Int>>()

        points.forEachIndexed { index, point ->
            val cellX = floor(point.xMeters / CELL_SIZE_METERS).toInt()
            val cellY = floor(point.yMeters / CELL_SIZE_METERS).toInt()
            var bestLevel = 0
            val currentVector = vectors[index]
            for (offsetX in -1..1) for (offsetY in -1..1) {
                grid[GridKey(cellX + offsetX, cellY + offsetY)]?.forEach { candidateIndex ->
                    val candidate = points[candidateIndex]
                    if (point.distanceMeters - candidate.distanceMeters < MIN_DISTANCE_GAP_METERS) return@forEach
                    val dx = point.xMeters - candidate.xMeters
                    val dy = point.yMeters - candidate.yMeters
                    if (dx * dx + dy * dy > CLOSE_DISTANCE_SQUARED) return@forEach
                    val candidateVector = vectors[candidateIndex]
                    if (currentVector.length() > 0.1 && candidateVector.length() > 0.1) {
                        val cosine = currentVector.dot(candidateVector) / (currentVector.length() * candidateVector.length())
                        if (abs(cosine) < MIN_DIRECTION_ALIGNMENT) return@forEach
                    }
                    bestLevel = maxOf(bestLevel, (rawLevels[candidateIndex] + 1).coerceAtMost(MAX_REPEAT_LEVEL))
                }
            }
            rawLevels[index] = bestLevel
            grid.getOrPut(GridKey(cellX, cellY)) { mutableListOf() }.add(index)
        }
        return smoothRepeatLevels(points, rawLevels)
    }

    private fun smoothRepeatLevels(points: List<RoutePoint>, rawLevels: IntArray): IntArray {
        val levels = IntArray(rawLevels.size)
        var index = 0
        var lastAcceptedLevel = 0
        while (index < rawLevels.size) {
            if (rawLevels[index] <= 0) {
                index++
                continue
            }
            val start = index
            val rawLevel = rawLevels[start].coerceAtMost(MAX_REPEAT_LEVEL)
            while (index < rawLevels.size && rawLevels[index].coerceAtMost(MAX_REPEAT_LEVEL) == rawLevel) index++
            val end = index - 1
            val runDistance = points[end].distanceMeters - points[start].distanceMeters
            val chosen = when {
                runDistance >= MIN_REPEAT_RUN_METERS -> rawLevel
                lastAcceptedLevel > 0 && rawLevel > lastAcceptedLevel -> lastAcceptedLevel
                else -> 0
            }
            for (levelIndex in start..end) levels[levelIndex] = chosen
            if (chosen > 0) lastAcceptedLevel = chosen
        }
        return levels
    }

    private fun directionVector(points: List<RoutePoint>, index: Int): Vector {
        val start = (index - 2).coerceAtLeast(0)
        val end = (index + 2).coerceAtMost(points.lastIndex)
        return Vector(points[end].xMeters - points[start].xMeters, points[end].yMeters - points[start].yMeters)
    }

    private data class Vector(val x: Double, val y: Double) {
        fun length(): Double = hypot(x, y)
        fun dot(other: Vector): Double = x * other.x + y * other.y
    }

    private data class GridKey(val x: Int, val y: Int)

    private companion object {
        const val METERS_PER_DEGREE = 111_320.0
        const val CELL_SIZE_METERS = 40.0
        const val CLOSE_DISTANCE_SQUARED = 35.0 * 35.0
        const val MIN_DISTANCE_GAP_METERS = 300f
        const val MIN_DIRECTION_ALIGNMENT = 0.5
        const val MIN_REPEAT_RUN_METERS = 180f
        const val MIN_OUT_AND_BACK_POINTS = 10
        const val MIN_OUT_AND_BACK_LEG_METERS = 120f
        const val OUT_AND_BACK_ENDPOINT_EXCLUSION_METERS = 30f
        const val MIN_OUT_AND_BACK_COMPARISONS = 4
        const val MIN_OUT_AND_BACK_MATCH_RATIO = 0.65f
        const val MIN_LEVELS_BEFORE_EXIT_PHASE = 2
        const val MIN_EXIT_PHASE_METERS = 60f
        const val EXIT_OVERLAP_PROBE_METERS = 80f
        const val MIN_EXIT_OVERLAP_SAMPLES = 5
        const val MIN_EXIT_OVERLAP_RATIO = 0.6f
        const val MIN_EXIT_MATCHED_SPAN_METERS = 40f
        const val MAX_REPEAT_LEVEL = 6
    }
}
