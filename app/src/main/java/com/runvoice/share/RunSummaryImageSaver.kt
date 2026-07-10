package com.runvoice.share

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

class RunSummaryImageSaver(private val context: Context) {

    fun saveSummary(runData: RunData, finishedAtMillis: Long, traceCsvPath: String? = null): String {
        val fileName = "RunVoice-${timestampForFile(finishedAtMillis)}.png"
        val bitmap = renderSummaryBitmap(runData, finishedAtMillis, traceCsvPath)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(bitmap, fileName)
        } else {
            saveToAppStorage(bitmap, fileName)
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

        val tracePoints = readAcceptedTracePoints(traceCsvPath)

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
            textMuted = textMuted,
            textPrimary = textPrimary
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
        textPrimary: Int,
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
                label = "总用时",
                value = runData.timeFormatted,
                valueColor = accentYellow
            ),
            SummaryRow(
                rect = RectF(card.left + px(28f), card.top + px(540f), card.right - px(28f), card.top + px(768f)),
                label = "平均配速",
                value = "${averagePaceFormatted(runData)} /km",
                valueColor = if (runData.distanceMeters > 0f) accentYellow else textMuted
            ),
            SummaryRow(
                rect = RectF(card.left + px(28f), card.top + px(796f), card.right - px(28f), card.top + px(1024f)),
                label = "最大心率",
                value = if (runData.maxHeartRate > 0) "${runData.maxHeartRate} bpm" else "-- bpm",
                valueColor = if (runData.maxHeartRate > 0) accentRed else textMuted
            )
        )

        rows.forEach { drawSummaryRowBackground(canvas, it.rect, bgColor) }
        if (tracePoints.size >= 2) {
            drawTraceOverlay(
                canvas = canvas,
                tracePoints = tracePoints,
                routeRect = RectF(card.left + px(180f), card.top + px(96f), card.right - px(180f), card.bottom - px(96f)),
                startColor = 0xFF516B83.toInt(),
                finishColor = 0xFF6C5F86.toInt(),
                endpointInnerColor = textPrimary
            )
        }
        rows.forEach { drawSummaryRowText(canvas, it, textSecondary) }
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
        canvas.drawText(row.label, rect.left + px(34f), labelBaseline, labelPaint)
        val valueWidth = valuePaint.measureText(row.value)
        canvas.drawText(row.value, rect.right - px(34f) - valueWidth, valueBaseline, valuePaint)
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
        val visualOffsets = smoothedRouteOffsets(screenPoints)
        var startIndex = 0
        while (startIndex < screenPoints.lastIndex) {
            val level = screenPoints[startIndex + 1].repeatLevel
            var endIndex = startIndex + 1
            while (endIndex < screenPoints.lastIndex && screenPoints[endIndex + 1].repeatLevel == level) {
                endIndex++
            }

            val routeStyle = routeStyleForRepeatLevel(level)
            val segmentPath = Path().apply {
                val startPoint = offsetScreenPoint(screenPoints, startIndex, visualOffsets[startIndex])
                moveTo(startPoint.x, startPoint.y)
                for (index in (startIndex + 1)..endIndex) {
                    val point = offsetScreenPoint(screenPoints, index, visualOffsets[index])
                    lineTo(point.x, point.y)
                }
            }
            val routeColor = routeStyle.color
            val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x24000000
                style = Paint.Style.STROKE
                strokeWidth = px(routeStyle.strokeWidth + 4f)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = routeColor.withAlpha(routeStyle.glowAlpha)
                style = Paint.Style.STROKE
                strokeWidth = px(routeStyle.strokeWidth + 3.5f)
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
            canvas.drawPath(segmentPath, shadowPaint)
            canvas.drawPath(segmentPath, glowPaint)
            canvas.drawPath(segmentPath, routePaint)

            startIndex = endIndex
        }

        drawTraceEndpoint(canvas, screenPoints.first().point, startColor.withAlpha(170), endpointInnerColor.withAlpha(150), px(7f), filled = true)
        drawTraceEndpoint(canvas, screenPoints.last().point, finishColor.withAlpha(170), endpointInnerColor.withAlpha(150), px(7f), filled = false)
    }

    private fun routeStyleForRepeatLevel(repeatLevel: Int): RouteStyle {
        return when (repeatLevel.coerceIn(0, MAX_REPEAT_LEVEL)) {
            0 -> RouteStyle(color = 0xD02F7285.toInt(), strokeWidth = 4.5f, glowAlpha = 34, offsetPx = 0f)
            1 -> RouteStyle(color = 0xD84B91A4.toInt(), strokeWidth = 4f, glowAlpha = 46, offsetPx = 6f)
            2 -> RouteStyle(color = 0xE064AAB8.toInt(), strokeWidth = 4f, glowAlpha = 58, offsetPx = -6f)
            3 -> RouteStyle(color = 0xE47FC0CA.toInt(), strokeWidth = 3.6f, glowAlpha = 68, offsetPx = 10f)
            else -> RouteStyle(color = 0xE8A3D5DA.toInt(), strokeWidth = 3.6f, glowAlpha = 78, offsetPx = -10f)
        }
    }

    private fun smoothedRouteOffsets(points: List<ScreenTracePoint>): FloatArray {
        if (points.isEmpty()) return FloatArray(0)

        val nominalOffsets = points.map { routeStyleForRepeatLevel(it.repeatLevel).offsetPx }
        val offsets = nominalOffsets.toFloatArray()
        val boundaries = mutableListOf<OffsetBoundary>()

        if (nominalOffsets.first() != 0f) {
            boundaries.add(
                OffsetBoundary(
                    distanceMeters = points.first().distanceMeters,
                    fromOffset = 0f,
                    toOffset = nominalOffsets.first()
                )
            )
        }

        for (index in 0 until points.lastIndex) {
            val fromOffset = nominalOffsets[index]
            val toOffset = nominalOffsets[index + 1]
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

        if (nominalOffsets.last() != 0f) {
            boundaries.add(
                OffsetBoundary(
                    distanceMeters = points.last().distanceMeters,
                    fromOffset = nominalOffsets.last(),
                    toOffset = 0f
                )
            )
        }

        if (boundaries.isEmpty()) return offsets

        points.forEachIndexed { index, point ->
            val nearestBoundary = boundaries.minByOrNull { boundary ->
                kotlin.math.abs(point.distanceMeters - boundary.distanceMeters)
            } ?: return@forEachIndexed
            val distanceFromBoundary = kotlin.math.abs(point.distanceMeters - nearestBoundary.distanceMeters)
            if (distanceFromBoundary <= OFFSET_TRANSITION_METERS) {
                val progress = ((point.distanceMeters - nearestBoundary.distanceMeters + OFFSET_TRANSITION_METERS) /
                    (OFFSET_TRANSITION_METERS * 2f)).coerceIn(0f, 1f)
                offsets[index] = nearestBoundary.fromOffset +
                    (nearestBoundary.toOffset - nearestBoundary.fromOffset) * progress
            }
        }

        return offsets
    }

    private fun offsetScreenPoint(
        points: List<ScreenTracePoint>,
        index: Int,
        offsetPx: Float,
    ): PointF {
        if (offsetPx == 0f || points.size < 2) return points[index].point

        val startIndex = (index - 1).coerceAtLeast(0)
        val endIndex = (index + 1).coerceAtMost(points.lastIndex)
        val start = points[startIndex].point
        val end = points[endIndex].point
        val dx = end.x - start.x
        val dy = end.y - start.y
        val length = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (length <= 0.001f) return points[index].point

        val normalX = -dy / length
        val normalY = dx / length
        val point = points[index].point
        return PointF(
            point.x + normalX * offsetPx,
            point.y + normalY * offsetPx
        )
    }

    private fun projectTracePointsWithRepeatLevels(points: List<TracePoint>, rect: RectF): List<ScreenTracePoint>? {
        val projected = projectTracePointsToMeters(points)
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
        val repeatLevels = detectRepeatLevels(projected)

        return projected.mapIndexed { index, point ->
            ScreenTracePoint(
                point = PointF(
                    rect.centerX() + ((point.xMeters - centerX) * projectionScale).toFloat(),
                    rect.centerY() - ((point.yMeters - centerY) * projectionScale).toFloat()
                ),
                distanceMeters = point.distanceMeters,
                repeatLevel = repeatLevels[index]
            )
        }
    }

    private fun detectRepeatLevels(points: List<ProjectedTracePoint>): IntArray {
        if (points.size < 3) return IntArray(points.size)

        val rawLevels = IntArray(points.size)
        val cellSizeMeters = 40.0
        val closeDistanceSquared = 35.0 * 35.0
        val minDistanceGapMeters = 300f
        val minDirectionAlignment = 0.5
        val directionVectors = points.indices.map { directionVector(points, it) }
        val grid = mutableMapOf<GridKey, MutableList<Int>>()

        points.forEachIndexed { index, point ->
            val cellX = floor(point.xMeters / cellSizeMeters).toInt()
            val cellY = floor(point.yMeters / cellSizeMeters).toInt()
            var bestLevel = 0
            val currentVector = directionVectors[index]
            val currentVectorLength = currentVector.length()

            for (offsetX in -1..1) {
                for (offsetY in -1..1) {
                    val candidateIndices = grid[GridKey(cellX + offsetX, cellY + offsetY)] ?: continue
                    candidateIndices.forEach { candidateIndex ->
                        val candidate = points[candidateIndex]
                        if (point.distanceMeters - candidate.distanceMeters < minDistanceGapMeters) {
                            return@forEach
                        }

                        val dx = point.xMeters - candidate.xMeters
                        val dy = point.yMeters - candidate.yMeters
                        if (dx * dx + dy * dy > closeDistanceSquared) {
                            return@forEach
                        }

                        val candidateVector = directionVectors[candidateIndex]
                        val candidateVectorLength = candidateVector.length()
                        if (currentVectorLength > 0.1 && candidateVectorLength > 0.1) {
                            val directionCosine = currentVector.dot(candidateVector) / (currentVectorLength * candidateVectorLength)
                            if (abs(directionCosine) < minDirectionAlignment) {
                                return@forEach
                            }
                        }

                        bestLevel = maxOf(bestLevel, (rawLevels[candidateIndex] + 1).coerceAtMost(MAX_REPEAT_LEVEL))
                    }
                }
            }

            rawLevels[index] = bestLevel
            grid.getOrPut(GridKey(cellX, cellY)) { mutableListOf() }.add(index)
        }

        return smoothRepeatLevels(points, rawLevels)
    }

    private fun smoothRepeatLevels(points: List<ProjectedTracePoint>, rawLevels: IntArray): IntArray {
        val levels = IntArray(rawLevels.size)
        val minRepeatRunMeters = 180f
        var index = 0
        var lastAcceptedLevel = 0

        while (index < rawLevels.size) {
            if (rawLevels[index] <= 0) {
                index++
                continue
            }

            val startIndex = index
            val rawLevel = rawLevels[startIndex].coerceAtMost(MAX_REPEAT_LEVEL)
            while (index < rawLevels.size && rawLevels[index].coerceAtMost(MAX_REPEAT_LEVEL) == rawLevel) {
                index++
            }
            val endIndex = index - 1
            val runDistance = points[endIndex].distanceMeters - points[startIndex].distanceMeters
            val chosenLevel = if (runDistance >= minRepeatRunMeters) {
                rawLevel
            } else if (lastAcceptedLevel > 0 && rawLevel > lastAcceptedLevel) {
                lastAcceptedLevel
            } else {
                0
            }
            for (levelIndex in startIndex..endIndex) {
                levels[levelIndex] = chosenLevel
            }
            if (chosenLevel > 0) {
                lastAcceptedLevel = chosenLevel
            }
        }

        return levels
    }

    private fun directionVector(points: List<ProjectedTracePoint>, index: Int): Vector {
        val startIndex = (index - 2).coerceAtLeast(0)
        val endIndex = (index + 2).coerceAtMost(points.lastIndex)
        if (startIndex == endIndex) return Vector(0.0, 0.0)
        return Vector(
            x = points[endIndex].xMeters - points[startIndex].xMeters,
            y = points[endIndex].yMeters - points[startIndex].yMeters
        )
    }

    private fun projectTracePointsToMeters(points: List<TracePoint>): List<ProjectedTracePoint> {
        val centerLatitude = points.map { it.latitude }.average()
        val centerLongitude = points.map { it.longitude }.average()
        val longitudeMeterScale = METERS_PER_DEGREE * cos(Math.toRadians(centerLatitude))

        return points.map { point ->
            ProjectedTracePoint(
                xMeters = (point.longitude - centerLongitude) * longitudeMeterScale,
                yMeters = (point.latitude - centerLatitude) * METERS_PER_DEGREE,
                distanceMeters = point.distanceMeters
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

    private fun readAcceptedTracePoints(traceCsvPath: String?): List<TracePoint> {
        if (traceCsvPath.isNullOrBlank()) return emptyList()
        val file = File(traceCsvPath)
        if (!file.exists()) return emptyList()

        return runCatching {
            file.bufferedReader().use { reader ->
                val header = reader.readLine() ?: return@use emptyList<TracePoint>()
                val columns = parseCsvLine(header)
                val latitudeIndex = columns.indexOf("latitude")
                val longitudeIndex = columns.indexOf("longitude")
                val decisionIndex = columns.indexOf("decision")
                val totalDistanceIndex = columns.indexOf("total_distance_m")
                if (latitudeIndex < 0 || longitudeIndex < 0 || decisionIndex < 0) {
                    return@use emptyList<TracePoint>()
                }

                val points = reader.lineSequence().mapNotNull { line ->
                    val values = parseCsvLine(line)
                    if (values.size <= maxOf(latitudeIndex, longitudeIndex, decisionIndex)) {
                        return@mapNotNull null
                    }
                    if (values[decisionIndex] != "accepted") {
                        return@mapNotNull null
                    }

                    val latitude = values[latitudeIndex].toDoubleOrNull()
                    val longitude = values[longitudeIndex].toDoubleOrNull()
                    val distanceMeters = if (totalDistanceIndex >= 0 && values.size > totalDistanceIndex) {
                        values[totalDistanceIndex].toFloatOrNull()
                    } else {
                        null
                    }
                    if (latitude == null || longitude == null) {
                        null
                    } else {
                        TracePoint(
                            latitude = latitude,
                            longitude = longitude,
                            distanceMeters = distanceMeters ?: 0f
                        )
                    }
                }.toList()
                normalizeTraceDistances(points)
            }
        }.getOrDefault(emptyList())
    }

    private fun normalizeTraceDistances(points: List<TracePoint>): List<TracePoint> {
        if (points.isEmpty()) return points
        val csvDistancesAreUsable = points.last().distanceMeters > 0f &&
            points.zipWithNext().all { (previous, current) -> current.distanceMeters >= previous.distanceMeters }
        if (csvDistancesAreUsable) return points

        var cumulativeDistance = 0f
        var previousPoint = points.first()
        return points.mapIndexed { index, point ->
            if (index > 0) {
                cumulativeDistance += approximateDistanceMeters(previousPoint, point)
                previousPoint = point
            }
            point.copy(distanceMeters = cumulativeDistance)
        }
    }

    private fun approximateDistanceMeters(first: TracePoint, second: TracePoint): Float {
        val averageLatitudeRadians = Math.toRadians((first.latitude + second.latitude) / 2.0)
        val dx = (second.longitude - first.longitude) * METERS_PER_DEGREE * cos(averageLatitudeRadians)
        val dy = (second.latitude - first.latitude) * METERS_PER_DEGREE
        return hypot(dx, dy).toFloat()
    }

    private fun parseCsvLine(line: String): List<String> {
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && inQuotes && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    values.add(current.toString())
                    current.clear()
                }
                else -> current.append(char)
            }
            index++
        }
        values.add(current.toString())
        return values
    }

    private fun Int.withAlpha(alpha: Int): Int {
        return (this and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
    }

    private data class TracePoint(
        val latitude: Double,
        val longitude: Double,
        val distanceMeters: Float,
    )

    private data class SummaryRow(
        val rect: RectF,
        val label: String,
        val value: String,
        val valueColor: Int,
    )

    private data class ProjectedTracePoint(
        val xMeters: Double,
        val yMeters: Double,
        val distanceMeters: Float,
    )

    private data class ScreenTracePoint(
        val point: PointF,
        val distanceMeters: Float,
        val repeatLevel: Int,
    )

    private data class RouteStyle(
        val color: Int,
        val strokeWidth: Float,
        val glowAlpha: Int,
        val offsetPx: Float,
    )

    private data class OffsetBoundary(
        val distanceMeters: Float,
        val fromOffset: Float,
        val toOffset: Float,
    )

    private data class Vector(
        val x: Double,
        val y: Double,
    ) {
        fun length(): Double = hypot(x, y)

        fun dot(other: Vector): Double = x * other.x + y * other.y
    }

    private data class GridKey(
        val x: Int,
        val y: Int,
    )

    private companion object {
        private const val METERS_PER_DEGREE = 111_320.0
        private const val MAX_REPEAT_LEVEL = 4
        private const val OFFSET_TRANSITION_METERS = 45f
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
