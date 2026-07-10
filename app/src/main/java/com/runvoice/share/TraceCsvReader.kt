package com.runvoice.share

import java.io.File
import kotlin.math.cos
import kotlin.math.hypot

internal data class TracePoint(
    val latitude: Double,
    val longitude: Double,
    val distanceMeters: Float
)

internal class TraceCsvReader {
    fun readAccepted(traceCsvPath: String?): List<TracePoint> {
        if (traceCsvPath.isNullOrBlank()) return emptyList()
        val file = File(traceCsvPath)
        if (!file.isFile) return emptyList()
        return runCatching {
            file.bufferedReader().use { reader ->
                val header = reader.readLine() ?: return@use emptyList()
                val columns = parseLine(header)
                val latitudeIndex = columns.indexOf("latitude")
                val longitudeIndex = columns.indexOf("longitude")
                val decisionIndex = columns.indexOf("decision")
                val totalDistanceIndex = columns.indexOf("total_distance_m")
                if (latitudeIndex < 0 || longitudeIndex < 0 || decisionIndex < 0) return@use emptyList()

                normalizeDistances(reader.lineSequence().mapNotNull { line ->
                    val values = parseLine(line)
                    if (values.size <= maxOf(latitudeIndex, longitudeIndex, decisionIndex) ||
                        values[decisionIndex] != "accepted"
                    ) return@mapNotNull null
                    val latitude = values[latitudeIndex].toDoubleOrNull() ?: return@mapNotNull null
                    val longitude = values[longitudeIndex].toDoubleOrNull() ?: return@mapNotNull null
                    val distance = values.getOrNull(totalDistanceIndex)?.toFloatOrNull() ?: 0f
                    TracePoint(latitude, longitude, distance)
                }.toList())
            }
        }.getOrDefault(emptyList())
    }

    internal fun parseLine(line: String): List<String> {
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < line.length) {
            when {
                line[index] == '"' && inQuotes && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                line[index] == '"' -> inQuotes = !inQuotes
                line[index] == ',' && !inQuotes -> {
                    values += current.toString()
                    current.clear()
                }
                else -> current.append(line[index])
            }
            index++
        }
        values += current.toString()
        return values
    }

    private fun normalizeDistances(points: List<TracePoint>): List<TracePoint> {
        if (points.isEmpty()) return points
        if (points.last().distanceMeters > 0f &&
            points.zipWithNext().all { (previous, current) -> current.distanceMeters >= previous.distanceMeters }
        ) return points

        var cumulative = 0f
        var previous = points.first()
        return points.mapIndexed { index, point ->
            if (index > 0) {
                cumulative += approximateDistanceMeters(previous, point)
                previous = point
            }
            point.copy(distanceMeters = cumulative)
        }
    }

    private fun approximateDistanceMeters(first: TracePoint, second: TracePoint): Float {
        val latitudeRadians = Math.toRadians((first.latitude + second.latitude) / 2.0)
        val dx = (second.longitude - first.longitude) * METERS_PER_DEGREE * cos(latitudeRadians)
        val dy = (second.latitude - first.latitude) * METERS_PER_DEGREE
        return hypot(dx, dy).toFloat()
    }

    private companion object {
        const val METERS_PER_DEGREE = 111_320.0
    }
}
