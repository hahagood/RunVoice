package com.runvoice.tracker

import android.content.ContentValues
import android.content.Context
import android.location.Location
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GpsTraceRecorder(private val context: Context) {

    companion object {
        private const val TAG = "GpsTraceRecorder"
        private const val PUBLIC_TRACE_DIR = "RunVoice/gps-traces"
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
                    "provider,motion_state,decision,reason,delta_m,total_distance_m,segment_distance_m," +
                    "pace_sec_per_km,heart_rate,hr_connected\n"
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
        paceSecondsPerKm: Int,
        heartRate: Int,
        hrConnected: Boolean
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
            paceSecondsPerKm.toString(),
            heartRate.toString(),
            hrConnected.toString()
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
        closeSession(save = true)
    }

    fun flush() {
        try {
            writer?.flush()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to flush GPS trace session", t)
        }
    }

    fun closeSession(save: Boolean) {
        val file = currentFile
        try {
            writer?.flush()
            writer?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to close GPS trace session", t)
        } finally {
            writer = null
            if (save) {
                file?.let {
                    Log.i(TAG, "GPS trace saved: ${it.absolutePath}")
                    savePublicCopy(it)
                }
            } else if (file != null && file.exists()) {
                if (file.delete()) {
                    Log.i(TAG, "GPS trace discarded: ${file.absolutePath}")
                } else {
                    Log.w(TAG, "Failed to discard GPS trace: ${file.absolutePath}")
                }
            }
            currentFile = null
        }
    }

    fun currentPath(): String? = currentFile?.absolutePath

    private fun savePublicCopy(file: File) {
        if (!file.exists() || file.length() <= 0L) return

        val savedPath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            savePublicCopyWithMediaStore(file)
        } else {
            savePublicCopyToDocuments(file)
        }

        if (savedPath != null) {
            Log.i(TAG, "GPS trace public copy saved: $savedPath")
        } else {
            Log.w(TAG, "Failed to save GPS trace public copy: ${file.absolutePath}")
        }
    }

    private fun savePublicCopyWithMediaStore(file: File): String? {
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/$PUBLIC_TRACE_DIR")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: return null

            resolver.openOutputStream(uri)?.use { output ->
                FileInputStream(file).use { input -> input.copyTo(output) }
            } ?: return null

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "${Environment.DIRECTORY_DOCUMENTS}/$PUBLIC_TRACE_DIR/${file.name}"
        }.getOrElse { t ->
            Log.w(TAG, "Failed to save GPS trace public copy with MediaStore", t)
            null
        }
    }

    private fun savePublicCopyToDocuments(file: File): String? {
        return runCatching {
            @Suppress("DEPRECATION")
            val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val dir = File(documentsDir, PUBLIC_TRACE_DIR).apply { mkdirs() }
            val target = File(dir, file.name)
            file.copyTo(target, overwrite = true)
            target.absolutePath
        }.getOrElse { t ->
            Log.w(TAG, "Failed to save GPS trace public copy to Documents", t)
            null
        }
    }

    private fun escape(value: String): String {
        return "\"" + value.replace("\"", "\"\"") + "\""
    }
}
