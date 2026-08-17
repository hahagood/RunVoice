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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class SummaryImageSaveResult(
    val message: String,
    val reference: String
)

class RunSummaryImageSaver(private val context: Context) {
    private val traceReader = TraceCsvReader()
    private val traceGeometry = TraceGeometry()
    private val storage = RunSummaryImageStorage(context)

    fun saveSummary(
        runData: RunData,
        finishedAtMillis: Long,
        traceCsvPath: String? = null
    ): SummaryImageSaveResult {
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
        val stackDir = stackDirection(screenPoints)
        val segments = mutableListOf<TraceRenderSegment>()
        var startIndex = 0
        while (startIndex < screenPoints.lastIndex) {
            val level = screenPoints[startIndex + 1].repeatLevel
            var endIndex = startIndex + 1
            while (endIndex < screenPoints.lastIndex && screenPoints[endIndex + 1].repeatLevel == level) {
                endIndex++
            }

            val routeStyle = routeStyleForRepeatLevel(level)
            val segmentPath = buildLayerPath(
                screenPoints,
                visualLevels,
                stackDir,
                startIndex,
                endIndex,
            )
            val visualLane = screenPoints[startIndex + 1].visualLane.toFloat()
            val depth = px(
                (3f + visualLane * 1.8f).coerceAtMost(MAX_EXTRUSION_DEPTH_PX)
            )
            val extrusionPath = buildLayerPath(
                screenPoints,
                visualLevels,
                stackDir,
                startIndex,
                endIndex,
                shiftX = -depth * 0.46f,
                shiftY = depth
            )
            segments += TraceRenderSegment(segmentPath, extrusionPath, routeStyle)
            startIndex = endIndex
        }

        // Structural depth is painted first for every phase. Otherwise a later phase's wide,
        // opaque extrusion can erase the colored core of an earlier connector, as happened in
        // the 2.08 km trace where the return follows the ingress in reverse.
        segments.forEach { segment ->
            val routeStyle = segment.style
            val extrusionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = routeStyle.edgeColor
                style = Paint.Style.STROKE
                strokeWidth = px(routeStyle.strokeWidth + 3.5f)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x55000000
                style = Paint.Style.STROKE
                strokeWidth = px(routeStyle.strokeWidth + 5f)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            canvas.drawPath(segment.extrusionPath, shadowPaint)
            canvas.drawPath(segment.extrusionPath, extrusionPaint)
        }

        // Glows sit above all structural depth but below all colored route cores.
        segments.forEach { segment ->
            val routeStyle = segment.style
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = routeStyle.color.withAlpha(routeStyle.glowAlpha)
                style = Paint.Style.STROKE
                strokeWidth = px(routeStyle.strokeWidth + 3f)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            canvas.drawPath(segment.path, glowPaint)
        }

        // Draw every colored core last. Each repeat phase is stacked by a rigid per-lane translation
        // along stackDir (perpendicular to the route's principal axis) so overlapping laps read as
        // layered strata instead of concentric rings; thin cores keep adjacent layers distinct.
        segments.forEach { segment ->
            val routeStyle = segment.style
            val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = routeStyle.color
                style = Paint.Style.STROKE
                strokeWidth = px(routeStyle.strokeWidth)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = routeStyle.highlightColor.withAlpha(150)
                style = Paint.Style.STROKE
                strokeWidth = px(0.9f)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            canvas.drawPath(segment.path, routePaint)
            canvas.drawPath(segment.path, highlightPaint)
        }

        drawTraceEndpoint(canvas, screenPoints.first().point, startColor.withAlpha(170), endpointInnerColor.withAlpha(150), px(7f), filled = true)
        val finishPoint = stackedScreenPoint(screenPoints.last().point, visualLevels.last(), stackDir)
        drawTraceEndpoint(canvas, finishPoint, finishColor.withAlpha(210), endpointInnerColor.withAlpha(190), px(7f), filled = false)
    }

    private fun routeStyleForRepeatLevel(repeatLevel: Int): RouteStyle {
        return when (Math.floorMod(repeatLevel, ROUTE_STYLE_COUNT)) {
            0 -> RouteStyle(0xFF42C77A.toInt(), 0xFF1F6B45.toInt(), 0xFFA6E8BD.toInt(), 4.0f, 62)
            1 -> RouteStyle(0xFFA875F5.toInt(), 0xFF5B3D91.toInt(), 0xFFD3B7FF.toInt(), 3.9f, 72)
            2 -> RouteStyle(0xFFF58C4A.toInt(), 0xFF854725.toInt(), 0xFFFFC09A.toInt(), 3.9f, 78)
            3 -> RouteStyle(0xFFD96BC5.toInt(), 0xFF78386F.toInt(), 0xFFF0AFE4.toInt(), 3.8f, 82)
            4 -> RouteStyle(0xFF82C653.toInt(), 0xFF466D2B.toInt(), 0xFFBDE49C.toInt(), 3.7f, 86)
            5 -> RouteStyle(0xFFC48655.toInt(), 0xFF70472C.toInt(), 0xFFE2B995.toInt(), 3.7f, 88)
            else -> RouteStyle(0xFF55C9A5.toInt(), 0xFF2B705C.toInt(), 0xFFA1E4CF.toInt(), 3.7f, 90)
        }
    }

    private fun smoothedLayerLevels(points: List<ScreenTracePoint>): FloatArray {
        if (points.isEmpty()) return FloatArray(0)

        val nominalLevels = points.map { it.visualLane.toFloat() }
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
        stackDir: PointF,
        startIndex: Int,
        endIndex: Int,
        shiftX: Float = 0f,
        shiftY: Float = 0f,
    ): Path = Path().apply {
        val start = stackedScreenPoint(points[startIndex].point, visualLevels[startIndex], stackDir)
        moveTo(start.x + shiftX, start.y + shiftY)
        for (index in (startIndex + 1)..endIndex) {
            val point = stackedScreenPoint(points[index].point, visualLevels[index], stackDir)
            lineTo(point.x + shiftX, point.y + shiftY)
        }
    }

    private fun stackedScreenPoint(point: PointF, visualLevel: Float, stackDir: PointF): PointF {
        return PointF(
            point.x + visualLevel * STACK_OFFSET_PX * stackDir.x,
            point.y + visualLevel * STACK_OFFSET_PX * stackDir.y,
        )
    }

    /**
     * A single screen-space direction used to slide each repeat lane off its predecessor. Chosen
     * perpendicular to the route's principal axis (the direction of greatest spread), because a
     * rigid translation only separates overlapping passes by its component perpendicular to them —
     * a fixed diagonal collapses ingress/egress corridors that happen to run along that diagonal.
     * Oriented so lanes stack upward on screen.
     */
    private fun stackDirection(points: List<ScreenTracePoint>): PointF {
        if (points.size < 2) return PointF(0f, -1f)
        var meanX = 0.0
        var meanY = 0.0
        for (p in points) {
            meanX += p.point.x
            meanY += p.point.y
        }
        meanX /= points.size
        meanY /= points.size
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for (p in points) {
            val dx = p.point.x - meanX
            val dy = p.point.y - meanY
            sxx += dx * dx
            syy += dy * dy
            sxy += dx * dy
        }
        val majorAngle = 0.5 * atan2(2.0 * sxy, sxx - syy)
        var perpX = -sin(majorAngle)
        var perpY = cos(majorAngle)
        if (perpY > 0) {
            perpX = -perpX
            perpY = -perpY
        }
        return PointF(perpX.toFloat(), perpY.toFloat())
    }

    private fun projectTracePointsWithRepeatLevels(points: List<TracePoint>, rect: RectF): List<ScreenTracePoint>? {
        val projected = traceGeometry.analyze(points)
        val visualLanes = TraceLaneAllocator().allocate(projected)
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
                repeatLevel = point.repeatLevel,
                visualLane = visualLanes[index],
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
        val visualLane: Int,
    )

    private data class RouteStyle(
        val color: Int,
        val edgeColor: Int,
        val highlightColor: Int,
        val strokeWidth: Float,
        val glowAlpha: Int,
    )

    private data class TraceRenderSegment(
        val path: Path,
        val extrusionPath: Path,
        val style: RouteStyle,
    )

    private data class OffsetBoundary(
        val distanceMeters: Float,
        val fromOffset: Float,
        val toOffset: Float,
    )

    private companion object {
        private const val ROUTE_STYLE_COUNT = 7
        private const val OFFSET_TRANSITION_METERS = 35f
        private const val STACK_OFFSET_PX = 13f
        private const val MAX_EXTRUSION_DEPTH_PX = 10f
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
