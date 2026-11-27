package com.telematicslab.demo.ui.components

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telematicslab.demo.sdk.api.DrivingEvent
import com.telematicslab.demo.sdk.api.ImuSample
import com.telematicslab.demo.sdk.api.TrackPoint
import com.telematicslab.demo.ui.theme.TelemetryColors
import com.telematicslab.demo.ui.theme.eventColor
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class WaveformMode(val label: String) {
    VEHICLE("Vehicle G"),
    RAW_PHONE("Raw Phone"),
    GYRO("Gyroscope"),
    FILTER_COMPARE("Raw vs Filt")
}

fun formatMinSec(sec: Float): String {
    val total = max(0, sec.roundToInt())
    return "%02d:%02d".format(total / 60, total % 60)
}

fun nearestTrackIndex(track: List<TrackPoint>, targetElapsedSec: Float): Int? {
    if (track.isEmpty()) return null
    var bestIdx = 0
    var bestDiff = kotlin.math.abs(track[0].elapsedSec - targetElapsedSec)
    for (i in track.indices) {
        val diff = kotlin.math.abs(track[i].elapsedSec - targetElapsedSec)
        if (diff < bestDiff) {
            bestDiff = diff
            bestIdx = i
        }
    }
    return bestIdx
}

private fun windowedTrack(
    track: List<TrackPoint>,
    zoom: Float,
    centerFrac: Float
): Triple<List<TrackPoint>, Float, Float> {
    if (track.size < 2) return Triple(track, 0f, 1f)
    val firstT = track.first().elapsedSec
    val lastT = track.last().elapsedSec
    val totalSpan = max(1f, lastT - firstT)
    val clampedZoom = zoom.coerceIn(1f, 8f)
    if (clampedZoom <= 1.01f) return Triple(track, firstT, firstT + totalSpan)
    val visibleSpan = totalSpan / clampedZoom
    val clampedCenter = centerFrac.coerceIn(0f, 1f)
    val startT = (firstT + clampedCenter * totalSpan - visibleSpan * 0.5f)
        .coerceIn(firstT, firstT + totalSpan - visibleSpan)
    val endT = startT + visibleSpan
    var slice = track.filter { it.elapsedSec in startT..endT }
    if (slice.size < 2) slice = track
    return Triple(slice, startT, endT)
}

private fun downsampleTrack(
    track: List<TrackPoint>,
    maxPoints: Int = 350,
    harshThresholdG: Float? = null
): List<TrackPoint> {
    if (track.size <= maxPoints || maxPoints <= 2) return track
    val step = (track.size - 1).toDouble() / (maxPoints - 1).toDouble()
    val kept = HashSet<Int>(maxPoints + 16)
    for (i in 0 until maxPoints) {
        kept.add((i * step).roundToInt().coerceIn(0, track.lastIndex))
    }
    kept.add(0)
    kept.add(track.lastIndex)
    if (harshThresholdG != null) {
        track.forEachIndexed { idx, pt ->
            if (sqrt(pt.latG * pt.latG + pt.longG * pt.longG) >= harshThresholdG) {
                kept.add(idx)
            }
        }
    }
    return kept.sorted().map { track[it] }
}

@Composable
fun TelemetryCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(TelemetryColors.CardDark)
            .border(1.dp, TelemetryColors.BorderDark, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChartLegendRow(items: List<Pair<String, Color>>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                Text(label, style = MaterialTheme.typography.labelMedium, color = TelemetryColors.Slate, maxLines = 1)
            }
        }
    }
}

@Composable
fun GGFrictionCircle(
    currentLongG: Float,
    currentLatG: Float,
    trailSamples: List<ImuSample>,
    thresholdG: Float,
    modifier: Modifier = Modifier,
    scatterPoints: List<TrackPoint> = emptyList(),
    maxRangeG: Float = 0.7f,
    zoomScale: Float = 1f,
    inspectedPoint: TrackPoint? = null,
    onSelectTrackIndex: ((Int) -> Unit)? = null
) {
    val labelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.parseColor("#94A3B8")
            textSize = 26f
            isAntiAlias = true
        }
    }
    val effectiveRangeG = (maxRangeG / zoomScale.coerceIn(1f, 3.5f)).coerceAtLeast(0.18f)
    val sampledScatter = remember(scatterPoints, thresholdG) {
        downsampleTrack(scatterPoints, maxPoints = 400, harshThresholdG = thresholdG)
    }

    Canvas(
        modifier = modifier
            .clipToBounds()
            .pointerInput(scatterPoints, effectiveRangeG, onSelectTrackIndex) {
                if (onSelectTrackIndex != null && scatterPoints.isNotEmpty()) {
                    detectTapGestures { tap ->
                        val sizeMin = min(size.width.toFloat(), size.height.toFloat())
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val radius = (sizeMin * 0.43f).coerceAtLeast(10f)
                        val targetLat = ((tap.x - cx) / radius) * effectiveRangeG
                        val targetLong = ((cy - tap.y) / radius) * effectiveRangeG
                        var bestIdx = 0
                        var bestDist = Float.MAX_VALUE
                        scatterPoints.forEachIndexed { idx, pt ->
                            val dx = pt.latG - targetLat
                            val dy = pt.longG - targetLong
                            val d = dx * dx + dy * dy
                            if (d < bestDist) {
                                bestDist = d
                                bestIdx = idx
                            }
                        }
                        onSelectTrackIndex(bestIdx)
                    }
                }
            }
    ) {
        val sizeMin = min(size.width, size.height)
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = sizeMin * 0.43f

        val rings = if (effectiveRangeG <= 0.38f) listOf(0.1f, 0.2f, 0.3f) else listOf(0.2f, 0.4f, 0.6f)
        rings.filter { it <= effectiveRangeG }.forEach { g ->
            val r = (g / effectiveRangeG) * radius
            drawCircle(
                color = TelemetryColors.BorderDark,
                radius = r,
                center = Offset(cx, cy),
                style = Stroke(width = 1.5f)
            )
            drawContext.canvas.nativeCanvas.drawText(
                "${"%.1f".format(g)}g",
                cx + 4f,
                cy - r + 24f,
                labelPaint
            )
        }

        val threshR = (thresholdG / effectiveRangeG) * radius
        drawCircle(
            color = TelemetryColors.Amber.copy(alpha = 0.55f),
            radius = threshR.coerceAtMost(radius),
            center = Offset(cx, cy),
            style = Stroke(
                width = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
            )
        )

        drawLine(TelemetryColors.BorderDark, Offset(cx - radius, cy), Offset(cx + radius, cy), 1.5f)
        drawLine(TelemetryColors.BorderDark, Offset(cx, cy - radius), Offset(cx, cy + radius), 1.5f)

        drawContext.canvas.nativeCanvas.apply {
            drawText("ACCEL", cx - 36f, cy - radius - 8f, labelPaint)
            drawText("BRAKE", cx - 36f, cy + radius + 24f, labelPaint)
            drawText("L", cx - radius - 20f, cy + 8f, labelPaint)
            drawText("R", cx + radius + 8f, cy + 8f, labelPaint)
        }

        if (sampledScatter.isNotEmpty()) {
            sampledScatter.forEach { pt ->
                val px = cx + (pt.latG / effectiveRangeG).coerceIn(-1f, 1f) * radius
                val py = cy - (pt.longG / effectiveRangeG).coerceIn(-1f, 1f) * radius
                val mag = sqrt(pt.latG * pt.latG + pt.longG * pt.longG)
                val dotColor = if (mag >= thresholdG) TelemetryColors.Red else TelemetryColors.Cyan.copy(alpha = 0.55f)
                drawCircle(dotColor, radius = 4f, center = Offset(px, py))
            }
            inspectedPoint?.let { ins ->
                val ix = cx + (ins.latG / effectiveRangeG).coerceIn(-1f, 1f) * radius
                val iy = cy - (ins.longG / effectiveRangeG).coerceIn(-1f, 1f) * radius
                drawLine(Color.Yellow, Offset(cx, cy), Offset(ix, iy), strokeWidth = 3f)
                drawCircle(Color.Yellow, radius = 9f, center = Offset(ix, iy))
                drawCircle(Color.Black, radius = 3.5f, center = Offset(ix, iy))
            }
        }

        val trail = trailSamples.takeLast(120)
        if (trail.size > 1) {
            for (i in 1 until trail.size) {
                val p0 = trail[i - 1]
                val p1 = trail[i]
                val x0 = cx + (p0.latG / effectiveRangeG).coerceIn(-1f, 1f) * radius
                val y0 = cy - (p0.longG / effectiveRangeG).coerceIn(-1f, 1f) * radius
                val x1 = cx + (p1.latG / effectiveRangeG).coerceIn(-1f, 1f) * radius
                val y1 = cy - (p1.longG / effectiveRangeG).coerceIn(-1f, 1f) * radius
                val alpha = (i.toFloat() / trail.size).coerceIn(0.1f, 0.85f)
                drawLine(
                    color = TelemetryColors.Teal.copy(alpha = alpha),
                    start = Offset(x0, y0),
                    end = Offset(x1, y1),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round
                )
            }
        }

        if (scatterPoints.isEmpty()) {
            val curX = cx + (currentLatG / effectiveRangeG).coerceIn(-1f, 1f) * radius
            val curY = cy - (currentLongG / effectiveRangeG).coerceIn(-1f, 1f) * radius
            val mag = sqrt(currentLatG * currentLatG + currentLongG * currentLongG)
            val dotColor = when {
                mag >= thresholdG -> TelemetryColors.Red
                mag >= thresholdG * 0.65f -> TelemetryColors.Amber
                else -> TelemetryColors.Green
            }
            drawLine(
                color = dotColor,
                start = Offset(cx, cy),
                end = Offset(curX, curY),
                strokeWidth = 4f,
                cap = StrokeCap.Round
            )
            drawCircle(color = dotColor, radius = 9f, center = Offset(curX, curY))
            drawCircle(color = Color.White, radius = 3.5f, center = Offset(curX, curY))
        }
    }
}

@Composable
fun LiveWaveformCanvas(
    samples: List<ImuSample>,
    mode: WaveformMode,
    modifier: Modifier = Modifier
) {
    val labelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.parseColor("#94A3B8")
            textSize = 24f
            isAntiAlias = true
        }
    }

    val yRange = when (mode) {
        WaveformMode.VEHICLE, WaveformMode.FILTER_COMPARE -> 0.8f
        WaveformMode.RAW_PHONE -> 2.0f
        WaveformMode.GYRO -> 4.0f
    }

    Canvas(modifier = modifier) {
        val padLeft = 56f
        val w = (size.width - padLeft - 8f).coerceAtLeast(10f)
        val h = size.height
        val cy = h / 2f

        listOf(-yRange, -yRange / 2f, 0f, yRange / 2f, yRange).forEach { v ->
            val y = cy - (v / yRange) * (h * 0.44f)
            drawLine(
                color = if (v == 0f) TelemetryColors.Slate.copy(alpha = 0.45f) else TelemetryColors.BorderDark,
                start = Offset(padLeft, y),
                end = Offset(padLeft + w, y),
                strokeWidth = if (v == 0f) 2f else 1f
            )
            drawContext.canvas.nativeCanvas.drawText(
                "%.1f".format(v),
                4f,
                y + 8f,
                labelPaint
            )
        }

        if (samples.size < 2) {
            drawContext.canvas.nativeCanvas.drawText(
                "Waiting for IMU samples...",
                padLeft + 24f,
                cy - 8f,
                labelPaint
            )
            return@Canvas
        }

        fun drawSeries(color: Color, strokeWidth: Float = 3f, selector: (ImuSample) -> Float) {
            val path = Path()
            samples.forEachIndexed { idx, s ->
                val x = padLeft + (idx.toFloat() / (samples.size - 1)) * w
                val v = selector(s).coerceIn(-yRange, yRange)
                val y = cy - (v / yRange) * (h * 0.44f)
                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
        }

        when (mode) {
            WaveformMode.VEHICLE -> {
                drawSeries(TelemetryColors.Cyan) { it.longG }
                drawSeries(TelemetryColors.Amber) { it.latG }
                drawSeries(TelemetryColors.Purple, 2.2f) { it.vertG }
            }
            WaveformMode.RAW_PHONE -> {
                drawSeries(TelemetryColors.Red) { it.rawPhoneX }
                drawSeries(TelemetryColors.Green) { it.rawPhoneY }
                drawSeries(TelemetryColors.Cyan) { it.rawPhoneZ }
            }
            WaveformMode.GYRO -> {
                drawSeries(TelemetryColors.Red) { it.gyroX }
                drawSeries(TelemetryColors.Green) { it.gyroY }
                drawSeries(TelemetryColors.Amber) { it.gyroZ }
            }
            WaveformMode.FILTER_COMPARE -> {
                drawSeries(TelemetryColors.Slate.copy(alpha = 0.55f), 2f) { it.rawLongG }
                drawSeries(TelemetryColors.Cyan, 3.8f) { it.longG }
            }
        }
    }
}

@Composable
fun SpeedTimelineChart(
    track: List<TrackPoint>,
    events: List<DrivingEvent>,
    modifier: Modifier = Modifier,
    zoomLevel: Float = 1f,
    windowCenterFrac: Float = 0.5f,
    inspectedPoint: TrackPoint? = null,
    onSelectElapsedSec: ((Float) -> Unit)? = null
) {
    val labelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.parseColor("#94A3B8")
            textSize = 24f
            isAntiAlias = true
        }
    }
    val highlightPaint = remember {
        Paint().apply {
            color = android.graphics.Color.YELLOW
            textSize = 24f
            isAntiAlias = true
        }
    }

    val (windowSlice, minT, maxT) = remember(track, zoomLevel, windowCenterFrac) {
        windowedTrack(track, zoomLevel, windowCenterFrac)
    }
    val sampledTrack = remember(windowSlice) { downsampleTrack(windowSlice, maxPoints = 350) }

    Canvas(
        modifier = modifier
            .clipToBounds()
            .pointerInput(minT, maxT, sampledTrack.size, onSelectElapsedSec) {
                if (onSelectElapsedSec != null && sampledTrack.size >= 2) {
                    detectTapGestures { tap ->
                        val padLeft = 56f
                        val w = (size.width - padLeft - 12f).coerceAtLeast(10f)
                        val frac = ((tap.x - padLeft) / w).coerceIn(0f, 1f)
                        onSelectElapsedSec(minT + frac * (maxT - minT))
                    }
                }
            }
    ) {
        if (sampledTrack.size < 2) {
            drawContext.canvas.nativeCanvas.drawText("No GPS track points recorded", 24f, size.height / 2f, labelPaint)
            return@Canvas
        }
        val padLeft = 56f
        val padBottom = 28f
        val w = (size.width - padLeft - 12f).coerceAtLeast(10f)
        val h = (size.height - padBottom - 12f).coerceAtLeast(10f)

        val maxSpeed = max(25f, (sampledTrack.maxOf { it.speedMph } * 1.15f))
        val spanT = max(1f, maxT - minT)

        listOf(0f, maxSpeed * 0.5f, maxSpeed).forEach { spd ->
            val y = 12f + h - (spd / maxSpeed) * h
            drawLine(TelemetryColors.BorderDark, Offset(padLeft, y), Offset(padLeft + w, y), 1.5f)
            drawContext.canvas.nativeCanvas.drawText("${spd.toInt()}", 6f, y + 8f, labelPaint)
        }

        val linePath = Path()
        val fillPath = Path()
        sampledTrack.forEachIndexed { idx, pt ->
            val x = padLeft + ((pt.elapsedSec - minT) / spanT) * w
            val y = 12f + h - (pt.speedMph / maxSpeed).coerceIn(0f, 1f) * h
            if (idx == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, 12f + h)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            if (idx == sampledTrack.lastIndex) {
                fillPath.lineTo(x, 12f + h)
                fillPath.close()
            }
        }
        drawPath(fillPath, color = TelemetryColors.Cyan.copy(alpha = 0.16f))
        drawPath(linePath, color = TelemetryColors.Cyan, style = Stroke(width = 4f, cap = StrokeCap.Round))

        events.filter { it.elapsedTripSec in minT..maxT }.forEach { ev ->
            val frac = ((ev.elapsedTripSec - minT) / spanT).coerceIn(0f, 1f)
            val x = padLeft + frac * w
            val c = eventColor(ev.type)
            drawLine(c.copy(alpha = 0.45f), Offset(x, 12f), Offset(x, 12f + h), 2f)
            drawCircle(c, radius = 7f, center = Offset(x, 20f))
        }

        inspectedPoint?.takeIf { it.elapsedSec in minT..maxT }?.let { ins ->
            val frac = ((ins.elapsedSec - minT) / spanT).coerceIn(0f, 1f)
            val ix = padLeft + frac * w
            val iy = 12f + h - (ins.speedMph / maxSpeed).coerceIn(0f, 1f) * h
            drawLine(Color.Yellow, Offset(ix, 12f), Offset(ix, 12f + h), 2.5f)
            drawCircle(Color.Yellow, radius = 8f, center = Offset(ix, iy))
            drawContext.canvas.nativeCanvas.drawText(
                "${formatMinSec(ins.elapsedSec)} • ${ins.speedMph.toInt()} mph",
                ix.coerceIn(padLeft + 10f, padLeft + w - 180f),
                28f,
                highlightPaint
            )
        }

        drawContext.canvas.nativeCanvas.apply {
            drawText(formatMinSec(minT), padLeft, size.height - 4f, labelPaint)
            drawText(formatMinSec(maxT), padLeft + w - 68f, size.height - 4f, labelPaint)
        }
    }
}

@Composable
fun AccelTimelineChart(
    track: List<TrackPoint>,
    thresholdG: Float,
    modifier: Modifier = Modifier,
    zoomLevel: Float = 1f,
    windowCenterFrac: Float = 0.5f,
    inspectedPoint: TrackPoint? = null,
    onSelectElapsedSec: ((Float) -> Unit)? = null
) {
    val labelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.parseColor("#94A3B8")
            textSize = 24f
            isAntiAlias = true
        }
    }
    val highlightPaint = remember {
        Paint().apply {
            color = android.graphics.Color.YELLOW
            textSize = 24f
            isAntiAlias = true
        }
    }

    val (windowSlice, minT, maxT) = remember(track, zoomLevel, windowCenterFrac) {
        windowedTrack(track, zoomLevel, windowCenterFrac)
    }
    val sampledTrack = remember(windowSlice, thresholdG) {
        downsampleTrack(windowSlice, maxPoints = 350, harshThresholdG = thresholdG)
    }

    Canvas(
        modifier = modifier
            .clipToBounds()
            .pointerInput(minT, maxT, sampledTrack.size, onSelectElapsedSec) {
                if (onSelectElapsedSec != null && sampledTrack.size >= 2) {
                    detectTapGestures { tap ->
                        val padLeft = 56f
                        val w = (size.width - padLeft - 12f).coerceAtLeast(10f)
                        val frac = ((tap.x - padLeft) / w).coerceIn(0f, 1f)
                        onSelectElapsedSec(minT + frac * (maxT - minT))
                    }
                }
            }
    ) {
        if (sampledTrack.size < 2) {
            drawContext.canvas.nativeCanvas.drawText("No acceleration track recorded", 24f, size.height / 2f, labelPaint)
            return@Canvas
        }
        val padLeft = 56f
        val w = (size.width - padLeft - 12f).coerceAtLeast(10f)
        val h = size.height
        val cy = h / 2f
        val rangeG = 0.7f
        val spanT = max(1f, maxT - minT)

        drawLine(TelemetryColors.Slate.copy(alpha = 0.45f), Offset(padLeft, cy), Offset(padLeft + w, cy), 1.5f)
        listOf(-thresholdG, thresholdG).forEach { th ->
            val y = cy - (th / rangeG) * (h * 0.44f)
            drawLine(
                color = TelemetryColors.Red.copy(alpha = 0.45f),
                start = Offset(padLeft, y),
                end = Offset(padLeft + w, y),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
            )
        }
        drawContext.canvas.nativeCanvas.apply {
            drawText("+0.7g", 4f, 24f, labelPaint)
            drawText("0.0g", 8f, cy + 8f, labelPaint)
            drawText("-0.7g", 4f, h - 8f, labelPaint)
        }

        val longPath = Path()
        val latPath = Path()
        sampledTrack.forEachIndexed { idx, pt ->
            val x = padLeft + ((pt.elapsedSec - minT) / spanT) * w
            val yLong = cy - (pt.longG / rangeG).coerceIn(-1f, 1f) * (h * 0.44f)
            val yLat = cy - (pt.latG / rangeG).coerceIn(-1f, 1f) * (h * 0.44f)
            if (idx == 0) {
                longPath.moveTo(x, yLong)
                latPath.moveTo(x, yLat)
            } else {
                longPath.lineTo(x, yLong)
                latPath.lineTo(x, yLat)
            }
        }
        drawPath(longPath, TelemetryColors.Cyan, style = Stroke(width = 3.2f, cap = StrokeCap.Round))
        drawPath(latPath, TelemetryColors.Amber, style = Stroke(width = 3.0f, cap = StrokeCap.Round))

        inspectedPoint?.takeIf { it.elapsedSec in minT..maxT }?.let { ins ->
            val ix = padLeft + ((ins.elapsedSec - minT) / spanT).coerceIn(0f, 1f) * w
            val yl = cy - (ins.longG / rangeG).coerceIn(-1f, 1f) * (h * 0.44f)
            val ylt = cy - (ins.latG / rangeG).coerceIn(-1f, 1f) * (h * 0.44f)
            drawLine(Color.Yellow, Offset(ix, 10f), Offset(ix, h - 10f), 2.2f)
            drawCircle(TelemetryColors.Cyan, radius = 6f, center = Offset(ix, yl))
            drawCircle(TelemetryColors.Amber, radius = 6f, center = Offset(ix, ylt))
            drawContext.canvas.nativeCanvas.drawText(
                "%+.2fg / %+.2fg".format(ins.longG, ins.latG),
                ix.coerceIn(padLeft + 10f, padLeft + w - 200f),
                24f,
                highlightPaint
            )
        }

        drawContext.canvas.nativeCanvas.apply {
            drawText(formatMinSec(minT), padLeft, h - 6f, labelPaint)
            drawText(formatMinSec(maxT), padLeft + w - 68f, h - 6f, labelPaint)
        }
    }
}

@Composable
fun RouteMapCanvas(
    track: List<TrackPoint>,
    events: List<DrivingEvent>,
    modifier: Modifier = Modifier,
    zoomScale: Float = 1f,
    panOffset: Offset = Offset.Zero,
    inspectedPoint: TrackPoint? = null,
    onSelectTrackPoint: ((Double, Double) -> Unit)? = null
) {
    val labelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.parseColor("#94A3B8")
            textSize = 24f
            isAntiAlias = true
        }
    }

    val valid = remember(track) {
        downsampleTrack(track.filter { it.lat != 0.0 || it.lon != 0.0 }, maxPoints = 350)
    }

    Canvas(
        modifier = modifier
            .clipToBounds()
            .pointerInput(valid, zoomScale, panOffset, onSelectTrackPoint) {
                if (onSelectTrackPoint != null && valid.size >= 2) {
                    detectTapGestures { tap ->
                        val refLat = valid.first().lat
                        val refLon = valid.first().lon
                        val cosLat = cos(Math.toRadians(refLat))
                        val pts = valid.map {
                            val east = ((it.lon - refLon) * 111_111.0 * cosLat).toFloat()
                            val north = ((it.lat - refLat) * 111_111.0).toFloat()
                            east to north
                        }
                        val minX = pts.minOf { it.first }
                        val maxX = pts.maxOf { it.first }
                        val minY = pts.minOf { it.second }
                        val maxY = pts.maxOf { it.second }
                        val spanX = (maxX - minX).coerceAtLeast(20f)
                        val spanY = (maxY - minY).coerceAtLeast(20f)
                        val pad = 28f
                        val baseScale = min((size.width - pad * 2) / spanX, (size.height - pad * 2) / spanY)
                        val z = zoomScale.coerceIn(1f, 8f)
                        val scale = baseScale * z
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val midE = (minX + maxX) / 2f
                        val midN = (minY + maxY) / 2f

                        var bestIdx = 0
                        var bestDist = Float.MAX_VALUE
                        pts.forEachIndexed { idx, (e, n) ->
                            val sx = cx + (e - midE) * scale + panOffset.x
                            val sy = cy - (n - midN) * scale + panOffset.y
                            val dx = sx - tap.x
                            val dy = sy - tap.y
                            val d = dx * dx + dy * dy
                            if (d < bestDist) {
                                bestDist = d
                                bestIdx = idx
                            }
                        }
                        val picked = valid[bestIdx]
                        onSelectTrackPoint(picked.lat, picked.lon)
                    }
                }
            }
    ) {
        if (valid.size < 2) {
            drawContext.canvas.nativeCanvas.drawText(
                "Need >= 2 GPS points to draw route",
                24f,
                size.height / 2f,
                labelPaint
            )
            return@Canvas
        }

        val refLat = valid.first().lat
        val refLon = valid.first().lon
        val cosLat = cos(Math.toRadians(refLat))

        fun toMeters(lat: Double, lon: Double): Pair<Float, Float> {
            val east = ((lon - refLon) * 111_111.0 * cosLat).toFloat()
            val north = ((lat - refLat) * 111_111.0).toFloat()
            return east to north
        }

        val pts = valid.map { toMeters(it.lat, it.lon) to it.speedMph }
        val minX = pts.minOf { it.first.first }
        val maxX = pts.maxOf { it.first.first }
        val minY = pts.minOf { it.first.second }
        val maxY = pts.maxOf { it.first.second }

        val spanX = (maxX - minX).coerceAtLeast(20f)
        val spanY = (maxY - minY).coerceAtLeast(20f)
        val pad = 28f
        val baseScale = min((size.width - pad * 2) / spanX, (size.height - pad * 2) / spanY)
        val scale = baseScale * zoomScale.coerceIn(1f, 8f)
        val cx = size.width / 2f
        val cy = size.height / 2f
        val midE = (minX + maxX) / 2f
        val midN = (minY + maxY) / 2f

        fun toScreen(east: Float, north: Float): Offset {
            val sx = cx + (east - midE) * scale + panOffset.x
            val sy = cy - (north - midN) * scale + panOffset.y
            return Offset(sx, sy)
        }

        for (i in 1 until pts.size) {
            val (m0, _) = pts[i - 1]
            val (m1, spd) = pts[i]
            val segColor = when {
                spd >= 60f -> TelemetryColors.Red
                spd >= 40f -> TelemetryColors.Amber
                spd >= 20f -> TelemetryColors.Teal
                else -> TelemetryColors.Cyan
            }
            drawLine(
                color = segColor,
                start = toScreen(m0.first, m0.second),
                end = toScreen(m1.first, m1.second),
                strokeWidth = 6f,
                cap = StrokeCap.Round
            )
        }

        val startPos = toScreen(pts.first().first.first, pts.first().first.second)
        val endPos = toScreen(pts.last().first.first, pts.last().first.second)
        drawCircle(TelemetryColors.Green, radius = 9f, center = startPos)
        drawCircle(Color.White, radius = 9f, center = endPos)
        drawCircle(TelemetryColors.Cyan, radius = 5f, center = endPos)

        events.filter { it.lat != 0.0 || it.lon != 0.0 }.forEach { ev ->
            val (e, n) = toMeters(ev.lat, ev.lon)
            val pos = toScreen(e, n)
            drawCircle(eventColor(ev.type), radius = 8f, center = pos)
            drawCircle(Color.White, radius = 3f, center = pos)
        }

        inspectedPoint?.takeIf { it.lat != 0.0 || it.lon != 0.0 }?.let { ins ->
            val (e, n) = toMeters(ins.lat, ins.lon)
            val pos = toScreen(e, n)
            drawCircle(Color.Yellow.copy(alpha = 0.35f), radius = 18f, center = pos)
            drawCircle(Color.Yellow, radius = 10f, center = pos)
            drawCircle(Color.Black, radius = 4f, center = pos)
        }
    }
}

@Composable
fun EventWaveformZoomChart(
    samples: List<ImuSample>,
    modifier: Modifier = Modifier
) {
    val labelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.parseColor("#94A3B8")
            textSize = 22f
            isAntiAlias = true
        }
    }
    var inspectedSampleIdx by remember(samples) { mutableStateOf<Int?>(null) }

    Column(modifier = modifier) {
        inspectedSampleIdx?.takeIf { it in samples.indices }?.let { idx ->
            val s = samples[idx]
            val dt = ((s.timestampMs - (samples.firstOrNull()?.timestampMs ?: s.timestampMs)) / 1000f)
            Text(
                "Sample +%.2fs • Long %+.2fg • Lat %+.2fg • %d mph".format(dt, s.longG, s.latG, s.speedMph.toInt()),
                fontSize = 11.sp,
                color = Color.Yellow,
                modifier = Modifier.padding(bottom = 3.dp)
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds()
                .pointerInput(samples) {
                    if (samples.size >= 2) {
                        detectTapGestures { tap ->
                            val padLeft = 48f
                            val w = (size.width - padLeft - 8f).coerceAtLeast(10f)
                            val frac = ((tap.x - padLeft) / w).coerceIn(0f, 1f)
                            inspectedSampleIdx = (frac * samples.lastIndex).roundToInt().coerceIn(0, samples.lastIndex)
                        }
                    }
                }
        ) {
            if (samples.size < 2) {
                drawContext.canvas.nativeCanvas.drawText("No high-rate snippet captured", 16f, size.height / 2f, labelPaint)
                return@Canvas
            }
            val padLeft = 48f
            val w = (size.width - padLeft - 8f).coerceAtLeast(10f)
            val h = size.height
            val cy = h / 2f
            val maxG = max(0.6f, samples.maxOf { max(kotlin.math.abs(it.longG), kotlin.math.abs(it.latG)) } * 1.15f)

            drawLine(TelemetryColors.Slate.copy(alpha = 0.45f), Offset(padLeft, cy), Offset(padLeft + w, cy), 1.5f)
            drawContext.canvas.nativeCanvas.apply {
                drawText("+${"%.1f".format(maxG)}g", 2f, 22f, labelPaint)
                drawText("-${"%.1f".format(maxG)}g", 2f, h - 6f, labelPaint)
            }

            val longPath = Path()
            val latPath = Path()
            samples.forEachIndexed { i, s ->
                val x = padLeft + (i.toFloat() / (samples.size - 1)) * w
                val yl = cy - (s.longG / maxG).coerceIn(-1f, 1f) * (h * 0.43f)
                val ylt = cy - (s.latG / maxG).coerceIn(-1f, 1f) * (h * 0.43f)
                if (i == 0) {
                    longPath.moveTo(x, yl)
                    latPath.moveTo(x, ylt)
                } else {
                    longPath.lineTo(x, yl)
                    latPath.lineTo(x, ylt)
                }
            }
            drawPath(longPath, TelemetryColors.Cyan, style = Stroke(3.5f, cap = StrokeCap.Round))
            drawPath(latPath, TelemetryColors.Amber, style = Stroke(3f, cap = StrokeCap.Round))

            inspectedSampleIdx?.takeIf { it in samples.indices }?.let { idx ->
                val x = padLeft + (idx.toFloat() / (samples.size - 1)) * w
                drawLine(Color.Yellow, Offset(x, 6f), Offset(x, h - 6f), 2f)
            }
        }
    }
}

@Composable
fun StatPill(label: String, value: String, color: Color = TelemetryColors.Cyan, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(10.dp))
            .background(TelemetryColors.SubCardDark)
            .border(1.dp, TelemetryColors.BorderDark, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = TelemetryColors.Slate,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
    }
}
