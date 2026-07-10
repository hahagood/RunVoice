package com.runvoice.share

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
        val levels = detectRepeatLevels(projected)
        return projected.mapIndexed { index, point -> point.copy(repeatLevel = levels[index]) }
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
        const val MAX_REPEAT_LEVEL = 4
    }
}
