package com.the412banner.batterylog.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.the412banner.batterylog.Analysis
import com.the412banner.batterylog.Fmt
import com.the412banner.batterylog.SampleSet
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

/** One chart line: times + values (NaN = no value). */
class Series(val t: LongArray, val v: FloatArray) {
    val size: Int get() = t.size
    val first: Long get() = if (t.isEmpty()) 0 else t[0]
    val last: Long get() = if (t.isEmpty()) 0 else t[t.size - 1]

    fun indexAtOrAfter(time: Long): Int {
        var lo = 0
        var hi = t.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (t[mid] < time) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Nearest index to [time] that has a value, or -1. */
    fun nearest(time: Long): Int {
        if (t.isEmpty()) return -1
        val i = indexAtOrAfter(time).coerceIn(0, t.size - 1)
        var best = -1
        var bestD = Long.MAX_VALUE
        for (k in max(0, i - 3)..min(t.size - 1, i + 3)) {
            if (v[k].isNaN()) continue
            val d = abs(t[k] - time)
            if (d < bestD) { bestD = d; best = k }
        }
        return best
    }

    companion object {
        val EMPTY = Series(LongArray(0), FloatArray(0))

        fun of(s: SampleSet, value: (Int) -> Float): Series {
            val v = FloatArray(s.size) { value(it) }
            return Series(s.t.copyOf(s.size), v)
        }
    }
}

/** Shared zoom/pan window + the press-and-drag readout time. Several charts can share one state. */
@Stable
class ChartState {
    var dataStart by mutableLongStateOf(0L)
        private set
    var dataEnd by mutableLongStateOf(0L)
        private set
    var viewStart by mutableLongStateOf(0L)
        private set
    var viewEnd by mutableLongStateOf(0L)
        private set
    var scrub by mutableStateOf<Long?>(null)

    val zoomed: Boolean get() = viewStart > dataStart || viewEnd < dataEnd
    private val dataSpan: Long get() = (dataEnd - dataStart).coerceAtLeast(1)
    private val minSpan: Long get() = max(10 * 60_000L, dataSpan / 400).coerceAtMost(dataSpan)

    fun setBounds(start: Long, end: Long) {
        if (start == dataStart && end == dataEnd) return
        val wasZoomed = zoomed && dataEnd > dataStart
        val pinnedToEnd = viewEnd >= dataEnd
        val span = viewEnd - viewStart
        dataStart = start
        dataEnd = max(end, start + 1)
        if (!wasZoomed) {
            viewStart = dataStart; viewEnd = dataEnd
        } else if (pinnedToEnd) {
            viewEnd = dataEnd; viewStart = max(dataStart, dataEnd - span)
        } else {
            clamp(viewStart, viewEnd)
        }
    }

    fun reset() {
        viewStart = dataStart; viewEnd = dataEnd
    }

    fun zoomBy(factor: Float, anchor: Float) {
        if (factor == 1f || factor.isNaN()) return
        val span = (viewEnd - viewStart).toDouble()
        val newSpan = (span / factor).toLong().coerceIn(minSpan, dataSpan)
        val a = viewStart + (span * anchor.coerceIn(0f, 1f)).toLong()
        val s = a - (newSpan * anchor.coerceIn(0f, 1f)).toLong()
        clamp(s, s + newSpan)
    }

    fun panBy(fraction: Float) {
        if (fraction == 0f || fraction.isNaN()) return
        val span = viewEnd - viewStart
        val d = (span * fraction).toLong()
        clamp(viewStart + d, viewEnd + d)
    }

    private fun clamp(s0: Long, e0: Long) {
        val span = (e0 - s0).coerceIn(1, dataSpan)
        var s = s0
        if (s < dataStart) s = dataStart
        if (s + span > dataEnd) s = dataEnd - span
        viewStart = s
        viewEnd = s + span
    }
}

@Composable
fun rememberChartState(): ChartState = remember { ChartState() }

private class Buckets(
    val x: FloatArray,
    val avg: FloatArray,
    val lo: FloatArray,
    val hi: FloatArray,
    val brk: BooleanArray,
    val n: Int,
    val bucketed: Boolean,
) {
    fun minMax(): Pair<Float, Float>? {
        if (n == 0) return null
        var a = Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (i in 0 until n) { a = min(a, lo[i]); b = max(b, hi[i]) }
        return a to b
    }
}

/** Reduces the visible part of [s] to at most [count] points (average + min/max per time bucket). */
private fun downsample(s: Series, from: Long, to: Long, count: Int, gapMs: Long): Buckets {
    val span = (to - from).coerceAtLeast(1).toDouble()
    val i0 = (s.indexAtOrAfter(from) - 1).coerceAtLeast(0)
    val i1 = min(s.size, s.indexAtOrAfter(to) + 1)
    val visible = (i1 - i0).coerceAtLeast(0)
    if (visible <= count * 3 / 2) {
        val x = FloatArray(visible); val y = FloatArray(visible); val brk = BooleanArray(visible)
        var n = 0
        var prevT = Long.MIN_VALUE
        for (i in i0 until i1) {
            val v = s.v[i]
            if (v.isNaN()) { prevT = Long.MIN_VALUE; continue }
            x[n] = ((s.t[i] - from) / span).toFloat()
            y[n] = v
            brk[n] = prevT == Long.MIN_VALUE || s.t[i] - prevT > gapMs
            prevT = s.t[i]
            n++
        }
        return Buckets(x, y, y, y, brk, n, bucketed = false)
    }
    val x = FloatArray(count); val avg = FloatArray(count); val lo = FloatArray(count); val hi = FloatArray(count)
    val brk = BooleanArray(count)
    val sum = DoubleArray(count); val tSum = DoubleArray(count); val cnt = IntArray(count)
    val firstT = LongArray(count) { Long.MIN_VALUE }; val lastT = LongArray(count)
    lo.fill(Float.MAX_VALUE); hi.fill(-Float.MAX_VALUE)
    for (i in i0 until i1) {
        val v = s.v[i]
        if (v.isNaN()) continue
        val b = (((s.t[i] - from) / span) * count).toInt().coerceIn(0, count - 1)
        sum[b] += v; tSum[b] += (s.t[i] - from).toDouble(); cnt[b]++
        if (v < lo[b]) lo[b] = v
        if (v > hi[b]) hi[b] = v
        if (firstT[b] == Long.MIN_VALUE) firstT[b] = s.t[i]
        lastT[b] = s.t[i]
    }
    var n = 0
    var prevLast = Long.MIN_VALUE
    for (b in 0 until count) {
        if (cnt[b] == 0) continue
        x[n] = (tSum[b] / cnt[b] / span).toFloat()
        avg[n] = (sum[b] / cnt[b]).toFloat()
        lo[n] = lo[b]; hi[n] = hi[b]
        brk[n] = prevLast == Long.MIN_VALUE || firstT[b] - prevLast > gapMs
        prevLast = lastT[b]
        n++
    }
    return Buckets(x, avg, lo, hi, brk, n, bucketed = true)
}

/** Monotone cubic (no overshoot) through points[from, to), same as d3's curveMonotoneX. */
private fun Path.monotone(xs: FloatArray, ys: FloatArray, from: Int, to: Int, moveFirst: Boolean = true) {
    val n = to - from
    if (n <= 0) return
    if (moveFirst) moveTo(xs[from], ys[from]) else lineTo(xs[from], ys[from])
    if (n == 1) return
    if (n == 2) { lineTo(xs[from + 1], ys[from + 1]); return }
    fun slope3(i: Int): Float {
        val h0 = xs[i] - xs[i - 1]; val h1 = xs[i + 1] - xs[i]
        if (h0 <= 0f || h1 <= 0f) return 0f
        val s0 = (ys[i] - ys[i - 1]) / h0; val s1 = (ys[i + 1] - ys[i]) / h1
        val p = (s0 * h1 + s1 * h0) / (h0 + h1)
        return (sign(s0) + sign(s1)) * min(min(abs(s0), abs(s1)), 0.5f * abs(p))
    }
    val m = FloatArray(n)
    for (k in 1 until n - 1) m[k] = slope3(from + k)
    fun slope2(i: Int, t: Float, forward: Boolean): Float {
        val a = if (forward) i else i - 1
        val h = xs[a + 1] - xs[a]
        return if (h > 0f) (3f * (ys[a + 1] - ys[a]) / h - t) / 2f else t
    }
    m[0] = slope2(from, m[1], true)
    m[n - 1] = slope2(from + n - 1, m[n - 2], false)
    for (k in 0 until n - 1) {
        val i = from + k
        val dx = (xs[i + 1] - xs[i]) / 3f
        cubicTo(xs[i] + dx, ys[i] + dx * m[k], xs[i + 1] - dx, ys[i + 1] - dx * m[k + 1], xs[i + 1], ys[i + 1])
    }
}

private fun niceStep(range: Float, ticks: Int): Float {
    if (range <= 0f || range.isNaN()) return 1f
    val raw = range / ticks
    val mag = 10.0.pow(floor(log10(raw.toDouble()))).toFloat()
    val f = raw / mag
    val nice = when {
        f < 1.5f -> 1f
        f < 3f -> 2f
        f < 7f -> 5f
        else -> 10f
    }
    return nice * mag
}

private val TIME_STEPS = longArrayOf(
    60_000L, 120_000L, 300_000L, 600_000L, 900_000L, 1_800_000L,
    3_600_000L, 7_200_000L, 10_800_000L, 21_600_000L, 43_200_000L,
    86_400_000L, 172_800_000L, 604_800_000L, 1_209_600_000L, 2_592_000_000L,
)

private class Geometry {
    var left = 0f
    var right = 1f
    var top = 0f
    var bottom = 1f
    val width get() = (right - left).coerceAtLeast(1f)
}

/**
 * Time chart: smooth line + gradient fill, optional min/max envelope when zoomed out, shaded
 * charging periods, animated reveal and y-range, press-and-drag readout, pinch zoom/pan,
 * double-tap to reset. With [negativeColor], values below zero are drawn in that colour.
 */
@Composable
fun TimeChart(
    series: Series,
    state: ChartState,
    color: Color,
    modifier: Modifier = Modifier,
    negativeColor: Color? = null,
    bands: List<LongRange> = emptyList(),
    bandColor: Color = color,
    axisFormat: (Float) -> String = { "%.0f".format(it) },
    valueFormat: (Float) -> String = axisFormat,
    fixedMin: Float? = null,
    fixedMax: Float? = null,
    minRange: Float = 1f,
    live: Boolean = false,
    interactive: Boolean = true,
    showAxes: Boolean = true,
    gapMs: Long = Analysis.GAP_MS,
    animateKey: Any? = null,
    emptyText: String = "لا بيانات بعد",
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val haptics = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    val labelStyle = TextStyle(fontSize = 10.sp, color = scheme.onSurfaceVariant, fontFeatureSettings = "tnum")
    val tipValueStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface, fontFeatureSettings = "tnum")
    val tipTimeStyle = TextStyle(fontSize = 11.sp, color = scheme.onSurfaceVariant, fontFeatureSettings = "tnum")
    val grid = scheme.outlineVariant.copy(alpha = 0.55f)
    val tipBg = scheme.surfaceContainerHighest
    val crossColor = scheme.onSurface.copy(alpha = 0.35f)

    var size by remember { mutableStateOf(IntSize.Zero) }
    val geo = remember { Geometry() }

    SideEffect { if (series.size > 0) state.setBounds(series.first, series.last) }

    val reveal = remember { Animatable(0f) }
    LaunchedEffect(animateKey) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(durationMillis = 900, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(state.scrub) {
        if (state.scrub != null) { delay(4_000); state.scrub = null }
    }

    val padL = with(density) { if (showAxes) 40.dp.toPx() else 2.dp.toPx() }
    val padR = with(density) { if (showAxes) 10.dp.toPx() else 2.dp.toPx() }
    val padT = with(density) { if (showAxes) 10.dp.toPx() else 4.dp.toPx() }
    val padB = with(density) { if (showAxes) 22.dp.toPx() else 4.dp.toPx() }
    val plotW = (size.width - padL - padR).coerceAtLeast(1f)
    val bucketCount = (plotW / with(density) { 2.5.dp.toPx() }).toInt().coerceIn(24, 700)

    val vs = state.viewStart
    val ve = if (state.viewEnd > state.viewStart) state.viewEnd else state.viewStart + 1
    val buckets = remember(series, vs, ve, bucketCount, gapMs) { downsample(series, vs, ve, bucketCount, gapMs) }

    // y range: data range with padding, snapped to nice ticks, animated.
    val mm = buckets.minMax()
    var tMin = fixedMin ?: (mm?.first ?: 0f)
    var tMax = fixedMax ?: (mm?.second ?: 1f)
    if (fixedMin == null || fixedMax == null) {
        if (tMax - tMin < minRange) {
            val c = (tMax + tMin) / 2f
            if (fixedMin == null) tMin = c - minRange / 2f
            if (fixedMax == null) tMax = c + minRange / 2f
        }
        val pad = (tMax - tMin) * 0.08f
        if (fixedMin == null) tMin -= pad
        if (fixedMax == null) tMax += pad
        val step = niceStep(tMax - tMin, 4)
        if (fixedMin == null) tMin = floor(tMin / step) * step
        if (fixedMax == null) tMax = ceil(tMax / step) * step
    }
    val yMin by animateFloatAsState(tMin, spring(stiffness = Spring.StiffnessMediumLow), label = "yMin")
    val yMax by animateFloatAsState(tMax, spring(stiffness = Spring.StiffnessMediumLow), label = "yMax")

    val pulse = pulseState(live)

    var lastTapAt by remember { mutableLongStateOf(0L) }
    val gestures = if (!interactive) Modifier else Modifier.pointerInput(state, series) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var mode = 0 // 0 undecided, 1 readout, 2 pinch, 3 let the page scroll
            val slop = viewConfiguration.touchSlop
            fun timeAt(x: Float): Long {
                val f = ((x - geo.left) / geo.width).coerceIn(0f, 1f)
                return state.viewStart + ((state.viewEnd - state.viewStart) * f).toLong()
            }
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (pressed.size >= 2) {
                    mode = 2
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    val c = event.calculateCentroid(useCurrent = true)
                    if (c != Offset.Unspecified) state.zoomBy(zoom, (c.x - geo.left) / geo.width)
                    state.panBy(-pan.x / geo.width)
                    state.scrub = null
                    event.changes.forEach { it.consume() }
                    continue
                }
                val ch = pressed.first()
                if (mode == 0) {
                    val d = ch.position - down.position
                    if (abs(d.x) > slop && abs(d.x) > abs(d.y)) {
                        mode = 1
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    } else if (abs(d.y) > slop) {
                        mode = 3
                    }
                }
                if (mode == 3) break
                if (mode == 1) {
                    state.scrub = timeAt(ch.position.x)
                    ch.consume()
                }
            }
            if (mode == 0) {
                val now = System.currentTimeMillis()
                if (now - lastTapAt < 320) {
                    state.reset(); state.scrub = null; lastTapAt = 0L
                } else {
                    lastTapAt = now
                    state.scrub = timeAt(down.position.x)
                }
            }
        }
    }

    Canvas(modifier.onSizeChanged { size = it }.then(gestures)) {
        geo.left = padL; geo.right = this.size.width - padR; geo.top = padT; geo.bottom = this.size.height - padB
        val left = geo.left; val right = geo.right; val top = geo.top; val bottom = geo.bottom
        val w = right - left
        val h = (bottom - top).coerceAtLeast(1f)
        val span = (ve - vs).coerceAtLeast(1)
        val range = (yMax - yMin).let { if (it <= 0f) 1f else it }
        fun yOf(v: Float) = bottom - (v - yMin) / range * h
        fun xOfTime(tt: Long) = left + (tt - vs).toFloat() / span * w

        // Charging bands.
        for (b in bands) {
            if (b.last < vs || b.first > ve) continue
            val x0 = xOfTime(b.first).coerceIn(left, right)
            val x1 = xOfTime(b.last).coerceIn(left, right)
            if (x1 - x0 < 0.5f) continue
            drawRect(bandColor.copy(alpha = 0.07f), Offset(x0, top), Size(x1 - x0, h))
            drawRect(bandColor.copy(alpha = 0.55f), Offset(x0, bottom - 3.dp.toPx()), Size(x1 - x0, 3.dp.toPx()))
        }

        if (showAxes) drawGrid(measurer, labelStyle, grid, left, right, top, bottom, yMin, yMax, axisFormat)
        if (showAxes) drawTimeAxis(measurer, labelStyle, left, right, bottom, vs, ve)

        if (buckets.n == 0) {
            val layout = measurer.measure(emptyText, labelStyle.copy(fontSize = 12.sp))
            drawText(layout, topLeft = Offset(left + (w - layout.size.width) / 2f, top + (h - layout.size.height) / 2f))
            return@Canvas
        }

        val xs = FloatArray(buckets.n) { left + buckets.x[it] * w }
        val ys = FloatArray(buckets.n) { yOf(buckets.avg[it]) }
        val line = Path()
        val fill = Path()
        val baseValue = if (negativeColor != null && yMin < 0f && yMax > 0f) 0f else yMin
        val baseY = yOf(baseValue)
        var segStart = 0
        for (i in 1..buckets.n) {
            if (i == buckets.n || buckets.brk[i]) {
                line.monotone(xs, ys, segStart, i)
                fill.monotone(xs, ys, segStart, i)
                fill.lineTo(xs[i - 1], baseY)
                fill.lineTo(xs[segStart], baseY)
                fill.close()
                segStart = i
            }
        }
        val envelope = if (buckets.bucketed) Path().also { p ->
            var s0 = 0
            for (i in 1..buckets.n) {
                if (i == buckets.n || buckets.brk[i]) {
                    if (i - s0 >= 2) {
                        p.moveTo(xs[s0], yOf(buckets.hi[s0]))
                        for (k in s0 + 1 until i) p.lineTo(xs[k], yOf(buckets.hi[k]))
                        for (k in i - 1 downTo s0) p.lineTo(xs[k], yOf(buckets.lo[k]))
                        p.close()
                    }
                    s0 = i
                }
            }
        } else null

        val revealRight = left + w * reveal.value
        clipRect(left = left - 8f, top = top - 8f, right = revealRight + 2f, bottom = bottom + 2f) {
            if (negativeColor != null && baseValue == 0f) {
                clipRect(top = top - 8f, bottom = baseY) { drawSeries(line, fill, envelope, color, top, baseY, false) }
                clipRect(top = baseY, bottom = bottom + 2f) { drawSeries(line, fill, envelope, negativeColor, baseY, bottom, true) }
                drawLine(crossColor.copy(alpha = 0.25f), Offset(left, baseY), Offset(right, baseY), 1.dp.toPx())
            } else {
                drawSeries(line, fill, envelope, color, top, bottom, false)
            }
        }

        // Live end dot.
        val pulseT = pulse?.value ?: 0f
        if (live && reveal.value >= 1f && series.size > 0 && series.last >= ve - 1) {
            val li = series.nearest(series.last)
            if (li >= 0) {
                val p = Offset(xOfTime(series.t[li]), yOf(series.v[li]))
                val c = if (negativeColor != null && series.v[li] < 0f) negativeColor else color
                drawCircle(c.copy(alpha = 0.35f * (1f - pulseT)), radius = 4.dp.toPx() + 10.dp.toPx() * pulseT, center = p)
                drawCircle(c, radius = 4.dp.toPx(), center = p)
            }
        }

        // Readout.
        val sc = state.scrub
        if (sc != null && sc in vs..ve) {
            val i = series.nearest(sc)
            if (i >= 0) {
                val tt = series.t[i]
                val vv = series.v[i]
                val px = xOfTime(tt).coerceIn(left, right)
                val py = yOf(vv).coerceIn(top, bottom)
                val c = if (negativeColor != null && vv < 0f) negativeColor else color
                drawLine(crossColor, Offset(px, top), Offset(px, bottom), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                drawCircle(c.copy(alpha = 0.25f), radius = 10.dp.toPx(), center = Offset(px, py))
                drawCircle(c, radius = 5.dp.toPx(), center = Offset(px, py))
                drawCircle(tipBg, radius = 2.dp.toPx(), center = Offset(px, py))
                val vLayout = measurer.measure(valueFormat(vv), tipValueStyle)
                val multiDay = ve - vs > 30 * 3_600_000L
                val tLabel = if (multiDay) "${Fmt.shortDate(tt)} ${Fmt.time(tt)}" else Fmt.time(tt)
                val tLayout = measurer.measure(tLabel, tipTimeStyle)
                val padX = 10.dp.toPx(); val padY = 6.dp.toPx()
                val bw = max(vLayout.size.width, tLayout.size.width) + padX * 2
                val bh = vLayout.size.height + tLayout.size.height + padY * 2
                var bx = px - bw / 2f
                bx = bx.coerceIn(left, right - bw)
                var by = py - bh - 14.dp.toPx()
                if (by < top) by = py + 14.dp.toPx()
                by = by.coerceIn(0f, this.size.height - bh)
                drawRoundRect(Color.Black.copy(alpha = 0.18f), Offset(bx, by + 2.dp.toPx()), Size(bw, bh), CornerRadius(12.dp.toPx()))
                drawRoundRect(tipBg, Offset(bx, by), Size(bw, bh), CornerRadius(12.dp.toPx()))
                drawRoundRect(c.copy(alpha = 0.6f), Offset(bx, by), Size(bw, bh), CornerRadius(12.dp.toPx()), style = Stroke(1.dp.toPx()))
                drawText(vLayout, topLeft = Offset(bx + (bw - vLayout.size.width) / 2f, by + padY))
                drawText(tLayout, topLeft = Offset(bx + (bw - tLayout.size.width) / 2f, by + padY + vLayout.size.height))
            }
        }
    }
}

/** Running 0→1 value for the live end dot; null (no animation, no redraws) when not live. */
@Composable
private fun pulseState(enabled: Boolean): androidx.compose.runtime.State<Float>? {
    if (!enabled) return null
    val t = rememberInfiniteTransition(label = "pulse")
    return t.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "pulseT",
    )
}

private fun DrawScope.drawSeries(line: Path, fill: Path, envelope: Path?, color: Color, gradTop: Float, gradBottom: Float, invert: Boolean) {
    val brush = if (!invert) {
        Brush.verticalGradient(listOf(color.copy(alpha = 0.32f), color.copy(alpha = 0.02f)), startY = gradTop, endY = gradBottom)
    } else {
        Brush.verticalGradient(listOf(color.copy(alpha = 0.02f), color.copy(alpha = 0.32f)), startY = gradTop, endY = gradBottom)
    }
    drawPath(fill, brush)
    envelope?.let { drawPath(it, color.copy(alpha = 0.14f)) }
    drawPath(line, color.copy(alpha = 0.18f), style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(line, color, style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawGrid(
    measurer: TextMeasurer, style: TextStyle, grid: Color,
    left: Float, right: Float, top: Float, bottom: Float, yMin: Float, yMax: Float, fmt: (Float) -> String,
) {
    val h = bottom - top
    val range = (yMax - yMin).let { if (it <= 0f) 1f else it }
    val step = niceStep(range, 4)
    var v = ceil(yMin / step) * step
    val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx()))
    var guard = 0
    while (v <= yMax + step * 0.01f && guard++ < 12) {
        val y = bottom - (v - yMin) / range * h
        if (y >= top - 1f && y <= bottom + 1f) {
            drawLine(grid, Offset(left, y), Offset(right, y), 1.dp.toPx(), pathEffect = dash)
            val layout = measurer.measure(fmt(v), style)
            drawText(layout, topLeft = Offset(left - layout.size.width - 6.dp.toPx(), y - layout.size.height / 2f))
        }
        v += step
    }
}

private fun DrawScope.drawTimeAxis(
    measurer: TextMeasurer, style: TextStyle, left: Float, right: Float, bottom: Float, vs: Long, ve: Long,
) {
    val span = (ve - vs).coerceAtLeast(1)
    val w = right - left
    val maxTicks = (w / 72.dp.toPx()).toInt().coerceAtLeast(2)
    val step = TIME_STEPS.firstOrNull { span / it <= maxTicks } ?: TIME_STEPS.last()
    val zone = ZoneId.systemDefault()
    val offset = zone.rules.getOffset(Instant.ofEpochMilli(vs)).totalSeconds * 1000L
    var t = ((vs + offset) / step + 1) * step - offset
    val dayStep = step >= 86_400_000L
    var guard = 0
    while (t < ve && guard++ < 40) {
        val x = left + (t - vs).toFloat() / span * w
        val label = when {
            !dayStep -> Fmt.time(t)
            span <= 8 * 86_400_000L -> Fmt.day(t)
            else -> Fmt.shortDate(t)
        }
        val layout = measurer.measure(label, style)
        val lx = (x - layout.size.width / 2f).coerceIn(left, right - layout.size.width)
        drawText(layout, topLeft = Offset(lx, bottom + 5.dp.toPx()))
        t += step
    }
}

/** Small no-axes trend line with gradient fill, for stat tiles. */
@Composable
fun Sparkline(series: Series, color: Color, modifier: Modifier = Modifier, gapMs: Long = Analysis.GAP_MS) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(1100, easing = FastOutSlowInEasing)) }
    Canvas(modifier) {
        if (series.size < 2) return@Canvas
        val from = series.first
        val to = max(series.last, from + 1)
        val b = downsample(series, from, to, (size.width / 3f).toInt().coerceIn(8, 160), gapMs)
        val mm = b.minMax() ?: return@Canvas
        var lo = mm.first; var hi = mm.second
        if (hi - lo < 1e-3f) { lo -= 1f; hi += 1f }
        val pad = (hi - lo) * 0.15f
        lo -= pad; hi += pad
        val xs = FloatArray(b.n) { b.x[it] * size.width }
        val ys = FloatArray(b.n) { size.height - (b.avg[it] - lo) / (hi - lo) * size.height }
        val line = Path(); val fill = Path()
        var s0 = 0
        for (i in 1..b.n) {
            if (i == b.n || b.brk[i]) {
                line.monotone(xs, ys, s0, i)
                fill.monotone(xs, ys, s0, i)
                fill.lineTo(xs[i - 1], size.height); fill.lineTo(xs[s0], size.height); fill.close()
                s0 = i
            }
        }
        clipRect(right = size.width * reveal.value + 2f) {
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f))))
            drawPath(line, color, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
