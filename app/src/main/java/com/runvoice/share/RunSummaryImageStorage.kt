package com.runvoice.share

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

internal class RunSummaryImageStorage(private val context: Context) {
    fun save(bitmap: Bitmap, fileName: String): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        saveWithMediaStore(bitmap, fileName)
    } else {
        saveToAppStorage(bitmap, fileName)
    }

    private fun saveWithMediaStore(bitmap: Bitmap, fileName: String): String {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/RunVoice")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        var uri: Uri? = null
        try {
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert returned null")
            resolver.openOutputStream(uri)?.use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG encoding failed" }
            } ?: error("MediaStore output stream unavailable")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            check(resolver.update(uri, values, null, null) > 0) { "Unable to publish image" }
            return "截图已保存到本地相册"
        } catch (failure: Throwable) {
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            throw failure
        }
    }

    private fun saveToAppStorage(bitmap: Bitmap, fileName: String): String {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: File(context.filesDir, "pictures")
        val dir = File(baseDir, "RunVoice")
        check(dir.exists() || dir.mkdirs()) { "Cannot create screenshot directory" }
        val file = File(dir, fileName)
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG encoding failed" }
        }
        return "截图已保存到 ${file.absolutePath}"
    }
}
