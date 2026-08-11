package com.runvoice.tracker

import java.io.File
import java.io.RandomAccessFile

internal const val GPS_TRACE_CSV_HEADER =
    "timestamp,latitude,longitude,accuracy_m,speed_mps,bearing_deg,altitude_m," +
        "provider,motion_state,decision,reason,delta_m,total_distance_m,segment_distance_m," +
        "pace_sec_per_km,heart_rate,hr_connected"

data class RecoveryTracePoint(
    val latitude: Double,
    val longitude: Double,
    val totalDistanceMeters: Float
)

data class TraceRecoveryData(
    val localPath: String,
    val totalDistanceMeters: Float,
    val maxHeartRate: Int,
    val acceptedPoints: List<RecoveryTracePoint>
)

internal object RecoveryTraceCsv {
    fun repairAndRead(file: File): TraceRecoveryData {
        repairTrailingRecord(file)
        val acceptedPoints = mutableListOf<RecoveryTracePoint>()
        var totalDistanceMeters = 0f
        var maxHeartRate = 0
        file.bufferedReader().use { reader ->
            val header = reader.readLine() ?: error("上次轨迹文件缺少表头")
            val columns = parseLine(header)
            check(columns == GPS_TRACE_CSV_HEADER.split(',')) { "上次轨迹文件格式不兼容" }
            val latitudeIndex = columns.indexOf("latitude")
            val longitudeIndex = columns.indexOf("longitude")
            val decisionIndex = columns.indexOf("decision")
            val totalDistanceIndex = columns.indexOf("total_distance_m")
            val heartRateIndex = columns.indexOf("heart_rate")

            reader.lineSequence().forEach { line ->
                val values = parseLine(line)
                if (values.size != columns.size) return@forEach
                val distance = values[totalDistanceIndex].toFloatOrNull()
                if (distance != null && distance.isFinite() && distance >= totalDistanceMeters) {
                    totalDistanceMeters = distance
                }
                values[heartRateIndex].toIntOrNull()?.let { maxHeartRate = maxOf(maxHeartRate, it) }
                if (values[decisionIndex] == "accepted") {
                    val latitude = values[latitudeIndex].toDoubleOrNull() ?: return@forEach
                    val longitude = values[longitudeIndex].toDoubleOrNull() ?: return@forEach
                    val acceptedDistance = distance ?: return@forEach
                    acceptedPoints += RecoveryTracePoint(latitude, longitude, acceptedDistance)
                }
            }
        }
        return TraceRecoveryData(
            localPath = file.absolutePath,
            totalDistanceMeters = totalDistanceMeters,
            maxHeartRate = maxHeartRate,
            acceptedPoints = acceptedPoints
        )
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

    private fun repairTrailingRecord(file: File) {
        RandomAccessFile(file, "rw").use { random ->
            val length = random.length()
            if (length <= 0L) return
            random.seek(length - 1L)
            if (random.readByte().toInt() == '\n'.code) return

            var previousNewline = length - 1L
            while (previousNewline >= 0L) {
                random.seek(previousNewline)
                if (random.readByte().toInt() == '\n'.code) break
                previousNewline--
            }
            val recordStart = previousNewline + 1L
            val recordLength = (length - recordStart).toInt()
            val bytes = ByteArray(recordLength)
            random.seek(recordStart)
            random.readFully(bytes)
            val trailingRecord = bytes.toString(Charsets.UTF_8)
            if (parseLine(trailingRecord).size == GPS_TRACE_CSV_HEADER.count { it == ',' } + 1) {
                random.seek(length)
                random.write('\n'.code)
            } else {
                random.setLength(recordStart)
            }
        }
    }
}
