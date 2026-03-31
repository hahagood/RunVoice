package com.runvoice.tracker

import android.content.Context
import android.location.Location
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GpsTraceRecorder(private val context: Context) {

    companion object {
        private const val TAG = "GpsTraceRecorder"
    }

    private var currentFile: File? = null
    private var writer: BufferedWriter? = null

    fun startSession() {
        closeSession()

        val baseDir = context.getExternalFilesDir("gps-traces") ?: File(context.filesDir, "gps-traces")
        val dir = baseDir.apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "run-$timestamp.csv")
        currentFile = file
        writer = BufferedWriter(FileWriter(file, false)).also {
            it.write(
                "timestamp,latitude,longitude,accuracy_m,speed_mps,bearing_deg,altitude_m," +
                    "provider,motion_state,decision,reason,delta_m,total_distance_m,segment_distance_m,pace_sec_per_km\n"
            )
            it.flush()
        }
        Log.i(TAG, "GPS trace session started: ${file.absolutePath}")
    }

    fun record(
        location: Location,
        motionState: Boolean?,
        decision: String,
        reason: String,
        deltaMeters: Float,
        totalDistanceMeters: Float,
        segmentDistanceMeters: Float,
        paceSecondsPerKm: Int
    ) {
        val line = listOf(
            location.time.toString(),
            location.latitude.toString(),
            location.longitude.toString(),
            location.accuracy.toString(),
            location.speed.toString(),
            location.bearing.toString(),
            location.altitude.toString(),
            escape(location.provider ?: ""),
            motionState?.toString() ?: "",
            decision,
            escape(reason),
            deltaMeters.toString(),
            totalDistanceMeters.toString(),
            segmentDistanceMeters.toString(),
            paceSecondsPerKm.toString()
        ).joinToString(",")

        try {
            writer?.apply {
                write(line)
                newLine()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to record GPS trace line", t)
        }
    }

    fun closeSession() {
        try {
            writer?.flush()
            writer?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to close GPS trace session", t)
        } finally {
            writer = null
            currentFile?.let { Log.i(TAG, "GPS trace saved: ${it.absolutePath}") }
            currentFile = null
        }
    }

    fun currentPath(): String? = currentFile?.absolutePath

    private fun escape(value: String): String {
        return "\"" + value.replace("\"", "\"\"") + "\""
    }
}
