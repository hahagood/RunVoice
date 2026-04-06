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
        val height = 1600
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgColor = 0xFF1A1A2E.toInt()
        val cardColor = 0xFF16213E.toInt()
        val accentYellow = 0xFFFFD600.toInt()
        val accentRed = 0xFFFF5252.toInt()
        val textPrimary = 0xFFFFFFFF.toInt()
        val textSecondary = 0xFFB0BEC5.toInt()
        val textMuted = 0xFF7F8C99.toInt()

        val scale = width / 1080f
        fun px(value: Float) = value * scale

        canvas.drawColor(bgColor)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textPrimary
            textSize = px(72f)
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val (dateText, timeText) = formatFinishedAtLines(finishedAtMillis)
        canvas.drawText(dateText, width / 2f, px(112f), titlePaint)
        canvas.drawText(timeText, width / 2f, px(206f), titlePaint)

        val card = RectF(px(48f), px(286f), width - px(48f), px(1430f))
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cardColor }
        canvas.drawRoundRect(card, px(28f), px(28f), cardPaint)

        drawSummaryRow(
            canvas = canvas,
            rect = RectF(card.left + px(28f), card.top + px(28f), card.right - px(28f), card.top + px(256f)),
            label = "距离",
            value = "${runData.distanceFormatted} km",
            valueColor = accentYellow,
            bgColor = bgColor,
            labelColor = textSecondary
        )
        drawSummaryRow(
            canvas = canvas,
            rect = RectF(card.left + px(28f), card.top + px(284f), card.right - px(28f), card.top + px(512f)),
            label = "总用时",
            value = runData.timeFormatted,
            valueColor = accentYellow,
            bgColor = bgColor,
            labelColor = textSecondary
        )
        drawSummaryRow(
            canvas = canvas,
            rect = RectF(card.left + px(28f), card.top + px(540f), card.right - px(28f), card.top + px(768f)),
            label = "平均配速",
            value = "${averagePaceFormatted(runData)} /km",
            valueColor = if (runData.distanceMeters > 0f) accentYellow else textMuted,
            bgColor = bgColor,
            labelColor = textSecondary
        )
        drawSummaryRow(
            canvas = canvas,
            rect = RectF(card.left + px(28f), card.top + px(796f), card.right - px(28f), card.top + px(1024f)),
            label = "最大心率",
            value = if (runData.maxHeartRate > 0) "${runData.maxHeartRate} bpm" else "-- bpm",
            valueColor = if (runData.maxHeartRate > 0) accentRed else textMuted,
            bgColor = bgColor,
            labelColor = textSecondary
        )

        return bitmap
    }

    private fun drawSummaryRow(
        canvas: Canvas,
        rect: RectF,
        label: String,
        value: String,
        valueColor: Int,
        bgColor: Int,
        labelColor: Int,
    ) {
        val scale = canvas.width / 1080f
        fun px(value: Float) = value * scale

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
        canvas.drawRoundRect(rect, px(24f), px(24f), bgPaint)

        val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor
            textSize = px(54f)
            isFakeBoldText = true
        }
        val valuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = valueColor
            textSize = px(54f)
            isFakeBoldText = true
        }

        val labelBaseline = rect.centerY() + px(18f)
        val valueBaseline = rect.centerY() + px(18f)
        canvas.drawText(label, rect.left + px(34f), labelBaseline, labelPaint)
        val valueWidth = valuePaint.measureText(value)
        canvas.drawText(value, rect.right - px(34f) - valueWidth, valueBaseline, valuePaint)
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
        return SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss", Locale.getDefault()).format(Date(timeMillis))
    }

    private fun formatFinishedAtLines(timeMillis: Long): Pair<String, String> {
        val date = SimpleDateFormat("yyyy年MM月dd日", Locale.getDefault()).format(Date(timeMillis))
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timeMillis))
        return date to time
    }

    private fun averagePaceFormatted(runData: RunData): String {
        if (runData.distanceMeters <= 0f || runData.elapsedSeconds <= 0L) return "--'--\""
        val secondsPerKm = ((runData.elapsedSeconds * 1000f) / runData.distanceMeters).toInt()
        val minutes = secondsPerKm / 60
        val seconds = secondsPerKm % 60
        return "%d'%02d\"".format(minutes, seconds)
    }
}
