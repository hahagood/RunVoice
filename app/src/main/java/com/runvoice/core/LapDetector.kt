package com.runvoice.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

data class LapCompletion(
    val lapNumber: Int,
    val lapDistanceMeters: Float,
    val totalDistanceMeters: Float
)

data class LapDetectionConfig(
    val finishRadiusMeters: Float = 35f,
    val armRadiusMeters: Float = 70f,
    val minimumLapDistanceMeters: Float = 300f,
    val immediateCompletionRadiusMeters: Float = 5f,
    val closestApproachRiseMeters: Float = 4f,
    val closestApproachConfirmationSamples: Int = 2,
    val minimumDiscoveryDirectionCosine: Float = 0.2f,
    val directionSampleDistanceMeters: Float = 10f
)

/**
 * Detects a repeated route without moving the finish anchor after every lap.
 *
 * The first completion discovers a previously visited local route point. That historical point is
 * then locked as the lap anchor, so an ingress before the loop is allowed and GPS error cannot move
 * the boundary forward on every subsequent lap. A closest approach is confirmed only after several
 * samples move away from the best point; a clean crossing of the fixed virtual gate can confirm it
 * immediately.
 */
class LapDetector(private val config: LapDetectionConfig = LapDetectionConfig()) {
    private val history = mutableListOf<TrackPoint>()
    private val historyGrid = mutableMapOf<GridKey, MutableList<Int>>()

    private var originLatitudeRadians: Double? = null
    private var originLongitudeRadians: Double? = null
    private var longitudeScale = 0.0
    private var lockedAnchor: TrackPoint? = null
    private var lockedDirection: Vector? = null
    private var activeApproach: Approach? = null
    private var lapStartDistanceMeters = 0f
    private var armed = false
    private var completedLaps = 0

    fun reset() {
        history.clear()
        historyGrid.clear()
        originLatitudeRadians = null
        originLongitudeRadians = null
        longitudeScale = 0.0
        lockedAnchor = null
        lockedDirection = null
        activeApproach = null
        lapStartDistanceMeters = 0f
        armed = false
        completedLaps = 0
    }

    fun process(latitude: Double, longitude: Double, totalDistanceMeters: Float): LapCompletion? {
        if (!latitude.isFinite() || !longitude.isFinite() || !totalDistanceMeters.isFinite()) return null
        if (history.isNotEmpty() && totalDistanceMeters < history.last().totalDistanceMeters) return null

        val point = project(latitude, longitude, totalDistanceMeters)
        val anchor = lockedAnchor
        val completion = if (anchor == null) processDiscovery(point) else processLocked(point, anchor)
        addToHistory(point)
        return completion
    }

    private fun processDiscovery(point: TrackPoint): LapCompletion? {
        val approach = activeApproach ?: findDiscoveryAnchor(point)?.let { anchor ->
            Approach(anchor = anchor, bestPoint = point, bestDistanceMeters = distance(anchor, point)).also {
                activeApproach = it
            }
        } ?: return null

        if (approach.anchor.index != 0 && !hasCompatibleDirection(approach.anchor, point)) {
            activeApproach = null
            return null
        }

        val completion = updateApproach(point, approach, allowGateCrossing = true)
        if (completion != null) {
            lockedAnchor = approach.anchor
            lockedDirection = directionAfter(approach.anchor.index)
            lapStartDistanceMeters = approach.anchor.totalDistanceMeters
            return completeLap(completion)
        }

        if (distance(approach.anchor, point) >
            config.finishRadiusMeters + config.closestApproachRiseMeters
        ) {
            activeApproach = null
        }
        return null
    }

    private fun processLocked(point: TrackPoint, anchor: TrackPoint): LapCompletion? {
        val distanceFromAnchor = distance(anchor, point)
        if (!armed) {
            if (distanceFromAnchor >= config.armRadiusMeters) armed = true
            return null
        }

        val lapDistance = point.totalDistanceMeters - lapStartDistanceMeters
        if (lapDistance < config.minimumLapDistanceMeters) return null

        val approach = activeApproach ?: if (distanceFromAnchor <= config.finishRadiusMeters) {
            Approach(anchor, point, distanceFromAnchor).also { activeApproach = it }
        } else {
            return null
        }

        return updateApproach(point, approach, allowGateCrossing = true)?.let(::completeLap).also {
            if (it == null && distanceFromAnchor > config.armRadiusMeters) activeApproach = null
        }
    }

    private fun completeLap(completionDistanceMeters: Float): LapCompletion? {
        val lapDistance = completionDistanceMeters - lapStartDistanceMeters
        if (lapDistance < config.minimumLapDistanceMeters) {
            activeApproach = null
            return null
        }

        completedLaps++
        lapStartDistanceMeters = completionDistanceMeters
        armed = false
        activeApproach = null
        return LapCompletion(completedLaps, lapDistance, completionDistanceMeters)
    }

    private fun updateApproach(
        point: TrackPoint,
        approach: Approach,
        allowGateCrossing: Boolean
    ): Float? {
        val distance = distance(approach.anchor, point)
        val previous = history.lastOrNull()
        if (distance < approach.bestDistanceMeters) {
            approach.bestDistanceMeters = distance
            approach.bestPoint = point
            approach.movingAwaySamples = 0
        } else if (distance >= approach.bestDistanceMeters + config.closestApproachRiseMeters) {
            approach.movingAwaySamples++
        } else {
            approach.movingAwaySamples = 0
        }

        if (approach.bestDistanceMeters <= config.immediateCompletionRadiusMeters) {
            return approach.bestPoint.totalDistanceMeters
        }

        val gateDirection = if (approach.anchor == lockedAnchor) lockedDirection else directionAfter(approach.anchor.index)
        if (allowGateCrossing && previous != null && gateDirection != null) {
            crossingDistance(previous, point, approach.anchor, gateDirection)?.let { return it }
        }

        return approach.bestPoint.totalDistanceMeters.takeIf {
            approach.movingAwaySamples >= config.closestApproachConfirmationSamples
        }
    }

    private fun crossingDistance(
        previous: TrackPoint,
        current: TrackPoint,
        anchor: TrackPoint,
        direction: Vector
    ): Float? {
        val length = direction.length()
        if (length < 0.1) return null
        val unit = Vector(direction.x / length, direction.y / length)
        val previousAlong = (previous.xMeters - anchor.xMeters) * unit.x +
            (previous.yMeters - anchor.yMeters) * unit.y
        val currentAlong = (current.xMeters - anchor.xMeters) * unit.x +
            (current.yMeters - anchor.yMeters) * unit.y
        if (previousAlong == currentAlong || previousAlong * currentAlong > 0.0) return null

        val fraction = (-previousAlong / (currentAlong - previousAlong)).coerceIn(0.0, 1.0)
        val crossingX = previous.xMeters + (current.xMeters - previous.xMeters) * fraction
        val crossingY = previous.yMeters + (current.yMeters - previous.yMeters) * fraction
        val lateralDistance = hypot(crossingX - anchor.xMeters, crossingY - anchor.yMeters)
        if (lateralDistance > config.finishRadiusMeters) return null

        return previous.totalDistanceMeters +
            (current.totalDistanceMeters - previous.totalDistanceMeters) * fraction.toFloat()
    }

    private fun findDiscoveryAnchor(point: TrackPoint): TrackPoint? {
        if (history.isEmpty()) return null
        val activityStart = history.first()
        if (point.totalDistanceMeters - activityStart.totalDistanceMeters >= config.minimumLapDistanceMeters &&
            distance(activityStart, point) <= config.finishRadiusMeters &&
            departedFrom(activityStart, 0)
        ) {
            return activityStart
        }

        val key = gridKey(point)
        var best: TrackPoint? = null
        var bestDistance = Double.POSITIVE_INFINITY
        for (offsetX in -1..1) for (offsetY in -1..1) {
            historyGrid[GridKey(key.x + offsetX, key.y + offsetY)]?.forEach { index ->
                val candidate = history[index]
                if (point.totalDistanceMeters - candidate.totalDistanceMeters < config.minimumLapDistanceMeters) {
                    return@forEach
                }
                val candidateDistance = distance(candidate, point)
                if (candidateDistance > config.finishRadiusMeters || candidateDistance >= bestDistance) return@forEach
                if (!departedFrom(candidate, index)) return@forEach
                if (!hasCompatibleDirection(candidate, point)) return@forEach
                best = candidate
                bestDistance = candidateDistance
            }
        }
        return best
    }

    /** Reverse overlap can create a zero-distance candidate at every point of an out-and-back. */
    private fun hasCompatibleDirection(candidate: TrackPoint, current: TrackPoint): Boolean {
        val historicalDirection = directionAfter(candidate.index) ?: return false
        val currentDirection = directionTo(current) ?: return false
        val denominator = historicalDirection.length() * currentDirection.length()
        if (denominator < 0.1) return false
        return historicalDirection.dot(currentDirection) / denominator >= config.minimumDiscoveryDirectionCosine
    }

    private fun directionTo(current: TrackPoint): Vector? {
        for (previousIndex in history.lastIndex downTo 0) {
            val previous = history[previousIndex]
            if (current.totalDistanceMeters - previous.totalDistanceMeters >= config.directionSampleDistanceMeters) {
                return Vector(current.xMeters - previous.xMeters, current.yMeters - previous.yMeters)
            }
        }
        return null
    }

    private fun departedFrom(candidate: TrackPoint, candidateIndex: Int): Boolean {
        for (index in (candidateIndex + 1) until history.size) {
            if (distance(candidate, history[index]) >= config.armRadiusMeters) return true
        }
        return false
    }

    private fun directionAfter(index: Int): Vector? {
        val start = history.getOrNull(index) ?: return null
        for (nextIndex in (index + 1) until history.size) {
            val next = history[nextIndex]
            if (next.totalDistanceMeters - start.totalDistanceMeters >= config.directionSampleDistanceMeters) {
                return Vector(next.xMeters - start.xMeters, next.yMeters - start.yMeters)
            }
        }
        return null
    }

    private fun project(latitude: Double, longitude: Double, totalDistanceMeters: Float): TrackPoint {
        val latitudeRadians = latitude * PI / 180.0
        val longitudeRadians = longitude * PI / 180.0
        if (originLatitudeRadians == null || originLongitudeRadians == null) {
            originLatitudeRadians = latitudeRadians
            originLongitudeRadians = longitudeRadians
            longitudeScale = EARTH_RADIUS_METERS * cos(latitudeRadians)
        }
        return TrackPoint(
            xMeters = (longitudeRadians - requireNotNull(originLongitudeRadians)) * longitudeScale,
            yMeters = (latitudeRadians - requireNotNull(originLatitudeRadians)) * EARTH_RADIUS_METERS,
            totalDistanceMeters = totalDistanceMeters,
            index = history.size
        )
    }

    private fun addToHistory(point: TrackPoint) {
        history += point
        historyGrid.getOrPut(gridKey(point)) { mutableListOf() }.add(point.index)
    }

    private fun gridKey(point: TrackPoint): GridKey = GridKey(
        floor(point.xMeters / GRID_CELL_METERS).toInt(),
        floor(point.yMeters / GRID_CELL_METERS).toInt()
    )

    private fun distance(first: TrackPoint, second: TrackPoint): Double =
        hypot(first.xMeters - second.xMeters, first.yMeters - second.yMeters)

    private data class TrackPoint(
        val xMeters: Double,
        val yMeters: Double,
        val totalDistanceMeters: Float,
        val index: Int
    )

    private data class Approach(
        val anchor: TrackPoint,
        var bestPoint: TrackPoint,
        var bestDistanceMeters: Double,
        var movingAwaySamples: Int = 0
    )

    private data class Vector(val x: Double, val y: Double) {
        fun length(): Double = hypot(x, y)
        fun dot(other: Vector): Double = x * other.x + y * other.y
    }

    private data class GridKey(val x: Int, val y: Int)

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0
        const val GRID_CELL_METERS = 40.0
    }
}
