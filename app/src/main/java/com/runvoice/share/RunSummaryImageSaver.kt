package com.runvoice.share

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.TextPaint
import com.runvoice.model.RunData
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RunSummaryImageSaver(private val context: Context) {

    fun saveSummary(runData: RunData, finishedAtMillis: Long): String {
        val fileName = "RunVoice-${timestampForFile(finishedAtMillis)}.png"
        val bitmap = renderSummaryBitmap(runData, finishedAtMillis)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(bitmap, fileName)
        } else {
            saveToAppStorage(bitmap, fileName)
        }
    }

    private fun renderSummaryBitmap(runData: RunData, finishedAtMillis: Long): Bitmap {
        val width = 1080
        val height = 1320
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgColor = 0xFF1A1A2E.toInt()
        val cardColor = 0xFF16213E.toInt()
        val accentYellow = 0xFFFFD600.toInt()
        val accentRed = 0xFFFF5252.toInt()
        val textPrimary = 0xFFFFFFFF.toInt()
        val textSecondary = 0xFFB0BEC5.toInt()
        val textMuted = 0xFF7F8C99.toInt()

        val density = context.resources.displayMetrics.density
        fun dp(value: Float) = value * density
        fun sp(value: Float) = value * context.resources.displayMetrics.scaledDensity

        canvas.drawColor(bgColor)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textPrimary
            textSize = sp(26f)
            isFakeBoldText = true
        }
        canvas.drawText(formatFinishedAt(finishedAtMillis), dp(20f), dp(52f), titlePaint)

        val card = RectF(dp(20f), dp(84f), width - dp(20f), dp(520f))
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cardColor }
        canvas.drawRoundRect(card, dp(16f), dp(16f), cardPaint)

        val smallTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textSecondary
            textSize = sp(14f)
        }
        canvas.drawText("当前记录", card.left + dp(16f), card.top + dp(28f), smallTextPaint)

        val distancePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentYellow
            textSize = sp(36f)
            isFakeBoldText = true
        }
        canvas.drawText("${runData.distanceFormatted} km", card.left + dp(16f), card.top + dp(92f), distancePaint)

        drawMetricBlock(
            canvas = canvas,
            x = card.left + dp(16f),
            y = card.top + dp(150f),
            label = "时间",
            value = runData.timeFormatted,
            valueColor = accentYellow,
            labelColor = textSecondary
        )
        drawMetricBlock(
            canvas = canvas,
            x = card.left + dp(320f),
            y = card.top + dp(150f),
            label = "平均配速",
            value = averagePaceFormatted(runData),
            valueColor = if (runData.distanceMeters > 0f) accentYellow else textMuted,
            labelColor = textSecondary,
            unit = "/km"
        )
        drawMetricBlock(
            canvas = canvas,
            x = card.left + dp(16f),
            y = card.top + dp(294f),
            label = "最大心率",
            value = if (runData.maxHeartRate > 0) "${runData.maxHeartRate}" else "--",
            valueColor = if (runData.maxHeartRate > 0) accentRed else textMuted,
            labelColor = textSecondary,
            unit = "bpm"
        )
    drawMetricBlock(
            canvas = canvas,
            x = card.left + dp(320f),
            y = card.top + dp(294f),
            label = "当前心率",
            value = if (runData.heartRate > 0) "${runData.heartRate}" else "--",
            valueColor = if (runData.heartRate > 0) accentYellow else textMuted,
            labelColor = textSecondary,
            unit = "bpm"
        )

        val notePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textSecondary
            textSize = sp(15f)
        }
        canvas.drawText("RunVoice", dp(20f), dp(590f), notePaint)
        canvas.drawText("已保存的跑步结束截图，可用于后续分享。", dp(20f), dp(620f), notePaint)

        return bitmap
    }

    private fun drawMetricBlock(
        canvas: Canvas,
        x: Float,
        y: Float,
        label: String,
        value: String,
        valueColor: Int,
        labelColor: Int,
        unit: String = ""
    ) {
        val density = context.resources.displayMetrics.density
        fun sp(value: Float) = value * context.resources.displayMetrics.scaledDensity
        fun dp(value: Float) = value * density

        val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor
            textSize = sp(14f)
        }
        val valuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = valueColor
            textSize = sp(28f)
            isFakeBoldText = true
        }
        val unitPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor
            textSize = sp(14f)
        }

        canvas.drawText(label, x, y, labelPaint)
        val valueBaseline = y + dp(48f)
        canvas.drawText(value, x, valueBaseline, valuePaint)
        if (unit.isNotEmpty()) {
            val offset = valuePaint.measureText(value) + dp(6f)
            canvas.drawText(unit, x + offset, valueBaseline, unitPaint)
        }
    }

    private fun saveWithMediaStore(bitmap: Bitmap, fileName: String): String {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/RunVoice")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return "保存截图失败"

        resolver.openOutputStream(uri)?.use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        } ?: return "保存截图失败"

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return "截图已保存到本地相册"
    }

    private fun saveToAppStorage(bitmap: Bitmap, fileName: String): String {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: File(context.filesDir, "pictures")
        val dir = File(baseDir, "RunVoice").apply { mkdirs() }
        val file = File(dir, fileName)
        FileOutputStream(file).use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        return "截图已保存到 ${file.absolutePath}"
    }

    private fun timestampForFile(timeMillis: Long): String {
        return SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(timeMillis))
    }

    private fun formatFinishedAt(timeMillis: Long): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timeMillis))
    }

    private fun averagePaceFormatted(runData: RunData): String {
        if (runData.distanceMeters <= 0f || runData.elapsedSeconds <= 0L) return "--'--\""
        val secondsPerKm = ((runData.elapsedSeconds * 1000f) / runData.distanceMeters).toInt()
        val minutes = secondsPerKm / 60
        val seconds = secondsPerKm % 60
        return "%d'%02d\"".format(minutes, seconds)
    }
}
