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
import java.io.FileOutputStream
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
    private var outputStream: FileOutputStream? = null
    private var writer: BufferedWriter? = null

    @Synchronized
    fun startSession(): String {
        if (currentFile != null) closeSession(save = false)
        val dir = traceDirectory()
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "run-$timestamp.csv")
        currentFile = file
        outputStream = FileOutputStream(file, false)
        writer = outputStream!!.bufferedWriter().also {
            it.write(GPS_TRACE_CSV_HEADER)
            it.newLine()
            it.flush()
            syncOutputBestEffort()
        }
        Log.i(TAG, "GPS trace session started: ${file.absolutePath}")
        return file.absolutePath
    }

    @Synchronized
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

    @Synchronized
    fun flush(): Boolean {
        val success = runCatching { writer?.flush() }
            .onFailure { Log.w(TAG, "Failed to flush GPS trace session", it) }
            .isSuccess
        if (success) syncOutputBestEffort()
        return success
    }

    @Synchronized
    fun closeSession(save: Boolean): TraceSaveResult {
        val file = currentFile ?: return TraceSaveResult.Discarded
        val flushFailure = runCatching { writer?.flush() }.exceptionOrNull()
        if (flushFailure == null) syncOutputBestEffort()
        val closeFailure = runCatching { writer?.close() }.exceptionOrNull() ?: flushFailure
        writer = null
        outputStream = null
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

    @Synchronized
    fun closeForRecovery() {
        val flushResult = runCatching { writer?.flush() }
            .onFailure { Log.w(TAG, "Failed to flush suspended GPS trace session", it) }
        if (flushResult.isSuccess) syncOutputBestEffort()
        runCatching { writer?.close() }
            .onFailure { Log.w(TAG, "Failed to suspend GPS trace session", it) }
        writer = null
        outputStream = null
        currentFile = null
    }

    @Synchronized
    fun resumeSession(tracePath: String): TraceRecoveryData {
        closeForRecovery()
        val file = validatedSessionFile(tracePath)
        check(file.isFile && file.length() > 0L) { "上次轨迹文件不存在或为空" }
        val recovery = RecoveryTraceCsv.repairAndRead(file)

        currentFile = file
        outputStream = FileOutputStream(file, true)
        writer = outputStream!!.bufferedWriter()
        Log.i(TAG, "GPS trace session resumed: ${file.absolutePath}")
        return recovery
    }

    @Synchronized
    fun discardRecoveredSession(tracePath: String): Boolean {
        val file = runCatching { validatedSessionFile(tracePath) }.getOrNull() ?: return false
        if (currentFile?.canonicalPath == file.canonicalPath) closeForRecovery()
        return !file.exists() || file.delete()
    }

    @Synchronized
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

    private fun traceDirectory(): File {
        val baseDir = context.getExternalFilesDir("gps-traces") ?: File(context.filesDir, "gps-traces")
        return baseDir.apply { check(exists() || mkdirs()) { "Cannot create GPS trace directory" } }
    }

    private fun validatedSessionFile(tracePath: String): File {
        val directory = traceDirectory().canonicalFile
        val file = File(tracePath).canonicalFile
        check(file.parentFile == directory) { "上次轨迹路径不安全" }
        return file
    }

    private fun syncOutputBestEffort() {
        runCatching { outputStream?.fd?.sync() }
            .onFailure { Log.w(TAG, "Unable to fsync GPS trace; buffered data was still flushed", it) }
    }

    private fun escape(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
}
