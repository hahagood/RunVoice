package com.runvoice.share

import kotlin.math.floor

/**
 * Assigns a compact visual lane to each chronological phase.
 *
 * Lanes are consumed only by phases that share a meaningful length of the same physical corridor.
 * Disjoint venues can therefore reuse low lanes even when their global phase indices are large.
 */
internal class TraceLaneAllocator {
    fun allocate(points: List<RoutePoint>): IntArray {
        if (points.isEmpty()) return IntArray(0)

        val lanes = IntArray(points.size)
        val laneByPhase = mutableMapOf<Int, Int>()
        val historyGrid = mutableMapOf<GridKey, MutableList<Int>>()
        var phaseStart = 0

        while (phaseStart < points.size) {
            val phaseIndex = points[phaseStart].repeatLevel
            var phaseEnd = phaseStart
            while (phaseEnd < points.lastIndex &&
                points[phaseEnd + 1].repeatLevel == phaseIndex
            ) phaseEnd++

            val evidenceByPhase = mutableMapOf<Int, OverlapEvidence>()
            for (index in phaseStart..phaseEnd) {
                val point = points[index]
                val key = gridKey(point)
                val matchedPhases = mutableSetOf<Int>()
                for (offsetX in -1..1) for (offsetY in -1..1) {
                    historyGrid[GridKey(key.x + offsetX, key.y + offsetY)]?.forEach { priorIndex ->
                        val priorPoint = points[priorIndex]
                        if (squaredDistance(point, priorPoint) <= CLOSE_DISTANCE_SQUARED) {
                            matchedPhases += priorPoint.repeatLevel
                        }
                    }
                }

                val sampleDistance = if (index > 0) {
                    (point.distanceMeters - points[index - 1].distanceMeters)
                        .coerceIn(0f, MAX_SAMPLE_WEIGHT_METERS)
                } else {
                    0f
                }
                matchedPhases.forEach { priorPhase ->
                    val evidence = evidenceByPhase.getOrPut(priorPhase) { OverlapEvidence() }
                    evidence.distanceMeters += sampleDistance
                    evidence.samples++
                }
            }

            val occupiedLanes = evidenceByPhase.mapNotNullTo(mutableSetOf()) { (priorPhase, evidence) ->
                laneByPhase[priorPhase]?.takeIf {
                    evidence.distanceMeters >= MIN_OVERLAP_DISTANCE_METERS &&
                        evidence.samples >= MIN_OVERLAP_SAMPLES
                }
            }
            var lane = 0
            while (lane in occupiedLanes) lane++
            laneByPhase[phaseIndex] = lane
            for (index in phaseStart..phaseEnd) lanes[index] = lane

            for (index in phaseStart..phaseEnd) {
                historyGrid.getOrPut(gridKey(points[index])) { mutableListOf() }.add(index)
            }
            phaseStart = phaseEnd + 1
        }

        return lanes
    }

    private fun gridKey(point: RoutePoint): GridKey = GridKey(
        floor(point.xMeters / CELL_SIZE_METERS).toInt(),
        floor(point.yMeters / CELL_SIZE_METERS).toInt(),
    )

    private fun squaredDistance(first: RoutePoint, second: RoutePoint): Double {
        val dx = first.xMeters - second.xMeters
        val dy = first.yMeters - second.yMeters
        return dx * dx + dy * dy
    }

    private data class GridKey(val x: Int, val y: Int)

    private data class OverlapEvidence(
        var distanceMeters: Float = 0f,
        var samples: Int = 0,
    )

    private companion object {
        const val CELL_SIZE_METERS = 40.0
        const val CLOSE_DISTANCE_SQUARED = 35.0 * 35.0
        const val MIN_OVERLAP_DISTANCE_METERS = 60f
        const val MIN_OVERLAP_SAMPLES = 4
        const val MAX_SAMPLE_WEIGHT_METERS = 20f
    }
}
