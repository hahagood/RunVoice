package com.runvoice.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.text.TextPaint
import com.runvoice.model.RunData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RunSummaryImageSaver(private val context: Context) {
    private val traceReader = TraceCsvReader()
    private val traceGeometry = TraceGeometry()
    private val storage = RunSummaryImageStorage(context)

    fun saveSummary(runData: RunData, finishedAtMillis: Long, traceCsvPath: String? = null): String {
        val fileName = "RunVoice-${timestampForFile(finishedAtMillis)}.png"
        val bitmap = renderSummaryBitmap(runData, finishedAtMillis, traceCsvPath)

        return try {
            storage.save(bitmap, fileName)
        } finally {
            bitmap.recycle()
        }
    }

    private fun renderSummaryBitmap(runData: RunData, finishedAtMillis: Long, traceCsvPath: String?): Bitmap {
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

        val tracePoints = traceReader.readAccepted(traceCsvPath)

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

        drawPlainSummary(
            canvas = canvas,
            runData = runData,
            tracePoints = tracePoints,
            bgColor = bgColor,
            cardColor = cardColor,
            accentYellow = accentYellow,
            accentRed = accentRed,
            textSecondary = textSecondary,
            textMuted = textMuted
        )

        return bitmap
    }

    private fun drawPlainSummary(
        canvas: Canvas,
        runData: RunData,
        tracePoints: List<TracePoint>,
        bgColor: Int,
        cardColor: Int,
        accentYellow: Int,
        accentRed: Int,
        textSecondary: Int,
        textMuted: Int,
    ) {
        val width = canvas.width
        val scale = width / 1080f
        fun px(value: Float) = value * scale

        val card = RectF(px(48f), px(286f), width - px(48f), px(1430f))
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cardColor }
        canvas.drawRoundRect(card, px(28f), px(28f), cardPaint)

        val rows = listOf(
            SummaryRow(
                rect = RectF(card.left + px(28f), card.top + px(28f), card.right - px(28f), card.top + px(256f)),
                label = "距离",
                value = "${runData.distanceFormatted} km",
                valueColor = accentYellow
            ),
            SummaryRow(
                rect = RectF(card.left + px(28f), card.top + px(284f), card.right - px(28f), card.top + px(512f)),
                label = "时间",
                value = runData.timeFormatted,
                valueColor = accentYellow
            ),
            SummaryRow(
                rect = RectF(card.left + px(28f), card.top + px(540f), card.right - px(28f), card.top + px(768f)),
                label = "平均配速",
                value = runData.averagePaceFormatted,
                valueColor = if (runData.distanceMeters > 0f) accentYellow else textMuted
            ),
            SummaryRow(
                rect = RectF(card.left + px(28f), card.top + px(796f), card.right - px(28f), card.top + px(1024f)),
                label = "最大心率",
                value = if (runData.maxHeartRate > 0) runData.maxHeartRate.toString() else "--",
                valueColor = if (runData.maxHeartRate > 0) accentRed else textMuted
            )
        )

        rows.forEach { drawSummaryRowBackground(canvas, it.rect, bgColor) }
        if (tracePoints.size >= 2) {
            drawTraceOverlay(
                canvas = canvas,
                tracePoints = tracePoints,
                routeRect = RectF(card.left + px(180f), card.top + px(96f), card.right - px(180f), card.bottom - px(96f)),
                startColor = 0xFF27704A.toInt(),
                finishColor = 0xFF71499E.toInt(),
                endpointInnerColor = 0xFF252832.toInt()
            )
        }
        rows.forEach { drawSummaryRowText(canvas, it, textSecondary, bgColor) }
    }

    private fun drawSummaryRowBackground(
        canvas: Canvas,
        rect: RectF,
        bgColor: Int,
    ) {
        val scale = canvas.width / 1080f
        fun px(value: Float) = value * scale

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
        canvas.drawRoundRect(rect, px(24f), px(24f), bgPaint)
    }

    private fun drawSummaryRowText(
        canvas: Canvas,
        row: SummaryRow,
        labelColor: Int,
        scrimColor: Int,
    ) {
        val scale = canvas.width / 1080f
        fun px(value: Float) = value * scale

        val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor
            textSize = px(54f)
            isFakeBoldText = true
        }
        val valuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = row.valueColor
            textSize = px(54f)
            isFakeBoldText = true
        }

        val rect = row.rect
        val labelBaseline = rect.centerY() + px(18f)
        val valueBaseline = rect.centerY() + px(18f)
        val labelX = rect.left + px(34f)
        val labelWidth = labelPaint.measureText(row.label)
        val valueWidth = valuePaint.measureText(row.value)
        val valueX = rect.right - px(34f) - valueWidth
        val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = scrimColor.withAlpha(TEXT_SCRIM_ALPHA)
        }
        drawTextScrim(canvas, labelX, labelBaseline, labelWidth, labelPaint, scrimPaint)
        drawTextScrim(canvas, valueX, valueBaseline, valueWidth, valuePaint, scrimPaint)
        canvas.drawText(row.label, labelX, labelBaseline, labelPaint)
        canvas.drawText(row.value, valueX, valueBaseline, valuePaint)
    }

    private fun drawTextScrim(
        canvas: Canvas,
        textX: Float,
        baseline: Float,
        textWidth: Float,
        textPaint: TextPaint,
        scrimPaint: Paint,
    ) {
        val scale = canvas.width / 1080f
        val horizontalPadding = 14f * scale
        val verticalPadding = 10f * scale
        val metrics = textPaint.fontMetrics
        val bounds = RectF(
            textX - horizontalPadding,
            baseline + metrics.ascent - verticalPadding,
            textX + textWidth + horizontalPadding,
            baseline + metrics.descent + verticalPadding,
        )
        canvas.drawRoundRect(bounds, 15f * scale, 15f * scale, scrimPaint)
    }

    private fun drawTraceOverlay(
        canvas: Canvas,
        tracePoints: List<TracePoint>,
        routeRect: RectF,
        startColor: Int,
        finishColor: Int,
        endpointInnerColor: Int,
    ) {
        val scale = canvas.width / 1080f
        fun px(value: Float) = value * scale

        val screenPoints = projectTracePointsWithRepeatLevels(tracePoints, routeRect) ?: return
        val visualLevels = smoothedLayerLevels(screenPoints)
        var startIndex = 0
        while (startIndex < screenPoints.lastIndex) {
            val level = screenPoints[startIndex + 1].repeatLevel
            var endIndex = startIndex + 1
            while (endIndex < screenPoints.lastIndex && screenPoints[endIndex + 1].repeatLevel == level) {
                endIndex++
            }

            val routeStyle = routeStyleForRepeatLevel(level)
            val segmentPath = buildLayerPath(screenPoints, visualLevels, startIndex, endIndex)
            val depth = px(3f + level.coerceAtMost(MAX_REPEAT_LEVEL) * 2.2f)
            val extrusionPath = buildLayerPath(
                screenPoints,
                visualLevels,
                startIndex,
                endIndex,
                shiftX = -depth * 0.46f,
                shiftY = depth
            )
            val routeColor = routeStyle.color
            val extrusionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = routeStyle.edgeColor
                style = Paint.Style.STROKE
                strokeWidth = px(routeStyle.strokeWidth + 5.5f)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x55000000
                style = Paint.Style.STROKE
                strokeWidth = px(routeStyle.strokeWidth + 8f)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = routeColor.withAlpha(routeStyle.glowAlpha)
                style = Paint.Style.STROKE
                strokeWidth = px(routeStyle.strokeWidth + 5f)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = routeColor
                style = Paint.Style.STROKE
                strokeWidth = px(routeStyle.strokeWidth)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = routeStyle.highlightColor.withAlpha(150)
                style = Paint.Style.STROKE
                strokeWidth = px(1.35f)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            canvas.drawPath(extrusionPath, shadowPaint)
            canvas.drawPath(extrusionPath, extrusionPaint)
            canvas.drawPath(segmentPath, glowPaint)
            canvas.drawPath(segmentPath, routePaint)
            canvas.drawPath(segmentPath, highlightPaint)

            startIndex = endIndex
        }

        drawTraceEndpoint(canvas, screenPoints.first().point, startColor.withAlpha(170), endpointInnerColor.withAlpha(150), px(7f), filled = true)
        val finishPoint = stackedScreenPoint(screenPoints.last().point, visualLevels.last())
        drawTraceEndpoint(canvas, finishPoint, finishColor.withAlpha(210), endpointInnerColor.withAlpha(190), px(7f), filled = false)
    }

    private fun routeStyleForRepeatLevel(repeatLevel: Int): RouteStyle {
        return when (repeatLevel.coerceIn(0, MAX_REPEAT_LEVEL)) {
            0 -> RouteStyle(0xFF42C77A.toInt(), 0xFF1F6B45.toInt(), 0xFFA6E8BD.toInt(), 6.4f, 62)
            1 -> RouteStyle(0xFFA875F5.toInt(), 0xFF5B3D91.toInt(), 0xFFD3B7FF.toInt(), 6.2f, 72)
            2 -> RouteStyle(0xFFF58C4A.toInt(), 0xFF854725.toInt(), 0xFFFFC09A.toInt(), 6.2f, 78)
            3 -> RouteStyle(0xFFD96BC5.toInt(), 0xFF78386F.toInt(), 0xFFF0AFE4.toInt(), 6.1f, 82)
            4 -> RouteStyle(0xFF82C653.toInt(), 0xFF466D2B.toInt(), 0xFFBDE49C.toInt(), 6f, 86)
            5 -> RouteStyle(0xFFC48655.toInt(), 0xFF70472C.toInt(), 0xFFE2B995.toInt(), 6f, 88)
            else -> RouteStyle(0xFF55C9A5.toInt(), 0xFF2B705C.toInt(), 0xFFA1E4CF.toInt(), 6f, 90)
        }
    }

    private fun smoothedLayerLevels(points: List<ScreenTracePoint>): FloatArray {
        if (points.isEmpty()) return FloatArray(0)

        val nominalLevels = points.map { it.repeatLevel.toFloat() }
        val levels = nominalLevels.toFloatArray()
        val boundaries = mutableListOf<OffsetBoundary>()

        for (index in 0 until points.lastIndex) {
            val fromOffset = nominalLevels[index]
            val toOffset = nominalLevels[index + 1]
            if (fromOffset != toOffset) {
                boundaries.add(
                    OffsetBoundary(
                        distanceMeters = (points[index].distanceMeters + points[index + 1].distanceMeters) / 2f,
                        fromOffset = fromOffset,
                        toOffset = toOffset
                    )
                )
            }
        }

        if (boundaries.isEmpty()) return levels

        points.forEachIndexed { index, point ->
            val nearestBoundary = boundaries.minByOrNull { boundary ->
                kotlin.math.abs(point.distanceMeters - boundary.distanceMeters)
            } ?: return@forEachIndexed
            val distanceFromBoundary = kotlin.math.abs(point.distanceMeters - nearestBoundary.distanceMeters)
            if (distanceFromBoundary <= OFFSET_TRANSITION_METERS) {
                val progress = ((point.distanceMeters - nearestBoundary.distanceMeters + OFFSET_TRANSITION_METERS) /
                    (OFFSET_TRANSITION_METERS * 2f)).coerceIn(0f, 1f)
                levels[index] = nearestBoundary.fromOffset +
                    (nearestBoundary.toOffset - nearestBoundary.fromOffset) * progress
            }
        }

        return levels
    }

    private fun buildLayerPath(
        points: List<ScreenTracePoint>,
        visualLevels: FloatArray,
        startIndex: Int,
        endIndex: Int,
        shiftX: Float = 0f,
        shiftY: Float = 0f,
    ): Path = Path().apply {
        val start = stackedScreenPoint(points[startIndex].point, visualLevels[startIndex])
        moveTo(start.x + shiftX, start.y + shiftY)
        for (index in (startIndex + 1)..endIndex) {
            val point = stackedScreenPoint(points[index].point, visualLevels[index])
            lineTo(point.x + shiftX, point.y + shiftY)
        }
    }

    private fun stackedScreenPoint(point: PointF, visualLevel: Float): PointF {
        return PointF(
            point.x + visualLevel * STACK_SHIFT_X_PX,
            point.y - visualLevel * STACK_SHIFT_Y_PX
        )
    }

    private fun projectTracePointsWithRepeatLevels(points: List<TracePoint>, rect: RectF): List<ScreenTracePoint>? {
        val projected = traceGeometry.analyze(points)
        val minX = projected.minOf { it.xMeters }
        val maxX = projected.maxOf { it.xMeters }
        val minY = projected.minOf { it.yMeters }
        val maxY = projected.maxOf { it.yMeters }
        val projectedWidth = maxX - minX
        val projectedHeight = maxY - minY
        val scaleCandidates = buildList<Double> {
            if (projectedWidth > 0.000000001) add(rect.width().toDouble() / projectedWidth)
            if (projectedHeight > 0.000000001) add(rect.height().toDouble() / projectedHeight)
        }
        val projectionScale = scaleCandidates.minOrNull() ?: return null
        val centerX = (minX + maxX) / 2.0
        val centerY = (minY + maxY) / 2.0
        return projected.mapIndexed { index, point ->
            ScreenTracePoint(
                point = PointF(
                    rect.centerX() + ((point.xMeters - centerX) * projectionScale).toFloat(),
                    rect.centerY() - ((point.yMeters - centerY) * projectionScale).toFloat()
                ),
                distanceMeters = point.distanceMeters,
                repeatLevel = point.repeatLevel
            )
        }
    }

    private fun drawTraceEndpoint(
        canvas: Canvas,
        point: PointF,
        color: Int,
        innerColor: Int,
        radius: Float,
        filled: Boolean,
    ) {
        val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
            strokeWidth = radius * 0.42f
        }
        val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = innerColor
            style = Paint.Style.FILL
        }
        canvas.drawCircle(point.x, point.y, radius * 1.55f, outerPaint)
        canvas.drawCircle(point.x, point.y, radius * 0.58f, innerPaint)
    }

    private fun Int.withAlpha(alpha: Int): Int {
        return (this and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
    }

    private data class SummaryRow(
        val rect: RectF,
        val label: String,
        val value: String,
        val valueColor: Int,
    )

    private data class ScreenTracePoint(
        val point: PointF,
        val distanceMeters: Float,
        val repeatLevel: Int,
    )

    private data class RouteStyle(
        val color: Int,
        val edgeColor: Int,
        val highlightColor: Int,
        val strokeWidth: Float,
        val glowAlpha: Int,
    )

    private data class OffsetBoundary(
        val distanceMeters: Float,
        val fromOffset: Float,
        val toOffset: Float,
    )

    private companion object {
        private const val MAX_REPEAT_LEVEL = 6
        private const val OFFSET_TRANSITION_METERS = 35f
        private const val STACK_SHIFT_X_PX = 7f
        private const val STACK_SHIFT_Y_PX = 11f
        private const val TEXT_SCRIM_ALPHA = 145
    }

    private fun timestampForFile(timeMillis: Long): String {
        return SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(timeMillis))
    }

    private fun formatFinishedAtLines(timeMillis: Long): Pair<String, String> {
        val date = SimpleDateFormat("yyyy年MM月dd日", Locale.getDefault()).format(Date(timeMillis))
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timeMillis))
        return date to time
    }

}
