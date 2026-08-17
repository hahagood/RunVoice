package com.runvoice.history.data

import android.content.Context
import android.os.Environment
import com.runvoice.history.model.RunRecord
import java.io.File

class RunHistoryFileCleaner(context: Context) {
    private val allowedRoots: List<File> = listOfNotNull(
        context.getExternalFilesDir("gps-traces"),
        context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
        context.filesDir
    ).map { it.canonicalFile }

    fun deletePrivateFiles(record: RunRecord): Result<Unit> = runCatching {
        listOfNotNull(record.traceLocalPath, record.posterReference)
            .mapNotNull(::validatedPrivateFileOrNull)
            .distinctBy { it.path }
            .forEach { file ->
                check(!file.exists() || file.delete()) {
                    "无法删除应用内文件：${file.name}"
                }
            }
    }

    private fun validatedPrivateFileOrNull(reference: String): File? {
        if (reference.startsWith("content://") || !File(reference).isAbsolute) return null
        val file = runCatching { File(reference).canonicalFile }.getOrNull() ?: return null
        return file.takeIf { candidate ->
            allowedRoots.any { root ->
                candidate == root || candidate.path.startsWith(root.path + File.separator)
            }
        }
    }
}
