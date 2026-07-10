package com.runvoice.tracker

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface TraceSaveResult {
    data class Saved(val localPath: String, val publicPath: String) : TraceSaveResult
    data object Discarded : TraceSaveResult
    data class Failed(val message: String, val localPath: String? = null) : TraceSaveResult
}

class GpsTraceRecorder(private val context: Context) {
    companion object {
        private const val TAG = "GpsTraceRecorder"
        private const val PUBLIC_TRACE_DIR = "RunVoice/gps-traces"
    }

    private var currentFile: File? = null
    private var writer: BufferedWriter? = null

    fun startSession() {
        if (currentFile != null) closeSession(save = false)
        val baseDir = context.getExternalFilesDir("gps-traces") ?: File(context.filesDir, "gps-traces")
        val dir = baseDir.apply { check(exists() || mkdirs()) { "Cannot create GPS trace directory" } }
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
            location.time.toString(), location.latitude.toString(), location.longitude.toString(),
            location.accuracy.toString(), location.speed.toString(), location.bearing.toString(),
            location.altitude.toString(), escape(location.provider ?: ""), motionState?.toString() ?: "",
            decision, escape(reason), deltaMeters.toString(), totalDistanceMeters.toString(),
            segmentDistanceMeters.toString(), paceSecondsPerKm.toString(), heartRate.toString(),
            hrConnected.toString()
        ).joinToString(",")

        runCatching {
            writer?.apply {
                write(line)
                newLine()
            }
        }.onFailure { Log.w(TAG, "Failed to record GPS trace line", it) }
    }

    fun flush() {
        runCatching { writer?.flush() }
            .onFailure { Log.w(TAG, "Failed to flush GPS trace session", it) }
    }

    fun closeSession(save: Boolean): TraceSaveResult {
        val file = currentFile ?: return TraceSaveResult.Discarded
        val closeFailure = runCatching {
            writer?.flush()
            writer?.close()
        }.exceptionOrNull()
        writer = null
        currentFile = null
        if (closeFailure != null) {
            Log.w(TAG, "Failed to close GPS trace session", closeFailure)
            return TraceSaveResult.Failed("轨迹文件写入失败", file.absolutePath)
        }

        if (!save) {
            return if (!file.exists() || file.delete()) {
                TraceSaveResult.Discarded
            } else {
                TraceSaveResult.Failed("无法删除本次轨迹", file.absolutePath)
            }
        }
        if (!file.exists() || file.length() <= 0L) {
            return TraceSaveResult.Failed("轨迹文件为空", file.absolutePath)
        }

        val publicCopy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            savePublicCopyWithMediaStore(file)
        } else {
            savePublicCopyToDocuments(file)
        }
        return publicCopy.fold(
            onSuccess = { path -> TraceSaveResult.Saved(file.absolutePath, path) },
            onFailure = { failure ->
                Log.w(TAG, "Failed to save public GPS trace copy", failure)
                TraceSaveResult.Failed("公共 Documents 导出失败，应用内副本仍保留", file.absolutePath)
            }
        )
    }

    fun currentPath(): String? = currentFile?.absolutePath

    private fun savePublicCopyWithMediaStore(file: File): Result<String> = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/$PUBLIC_TRACE_DIR")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        var uri: Uri? = null
        try {
            uri = resolver.insert(MediaStore.Files.getContentUri("external_primary"), values)
                ?: error("MediaStore insert returned null")
            resolver.openOutputStream(uri)?.use { output ->
                FileInputStream(file).use { input -> input.copyTo(output) }
            } ?: error("MediaStore output stream unavailable")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            check(resolver.update(uri, values, null, null) > 0) { "Unable to publish MediaStore item" }
            "${Environment.DIRECTORY_DOCUMENTS}/$PUBLIC_TRACE_DIR/${file.name}"
        } catch (failure: Throwable) {
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            throw failure
        }
    }

    private fun savePublicCopyToDocuments(file: File): Result<String> = runCatching {
        check(
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        ) { "Storage permission denied" }
        @Suppress("DEPRECATION")
        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val dir = File(documentsDir, PUBLIC_TRACE_DIR)
        check(dir.exists() || dir.mkdirs()) { "Cannot create public trace directory" }
        val target = File(dir, file.name)
        file.copyTo(target, overwrite = true)
        target.absolutePath
    }

    private fun escape(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
}
