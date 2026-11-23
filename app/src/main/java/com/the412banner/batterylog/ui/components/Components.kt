package com.the412banner.batterylog.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.the412banner.batterylog.ui.theme.LocalChartColors
import com.the412banner.batterylog.ui.theme.NumberStyle

val CardShape = RoundedCornerShape(24.dp)

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = color)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = CardShape, colors = colors) {
            Column(Modifier.padding(18.dp), content = content)
        }
    } else {
        Card(modifier = modifier.fillMaxWidth(), shape = CardShape, colors = colors) {
            Column(Modifier.padding(18.dp), content = content)
        }
    }
}

/** Card with a tinted round icon + title row. */
@Composable
fun SectionCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppCard(modifier, onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, tint)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            trailing?.invoke()
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
fun IconBadge(icon: ImageVector, tint: Color, size: Dp = 36.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

/** Text that slides when its value changes (live numbers). */
@Composable
fun RollingText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (slideInVertically(tween(260)) { it / 2 } + fadeIn(tween(260))) togetherWith
                (slideOutVertically(tween(200)) { -it / 2 } + fadeOut(tween(160))) using SizeTransform(clip = false)
        },
        modifier = modifier,
        label = "rolling",
    ) { value ->
        Text(value, style = style.merge(NumberStyle), color = color, maxLines = 1)
    }
}

/** Small metric tile: icon, label, big live value, optional sparkline. */
@Composable
fun StatTile(
    label: String,
    value: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    sub: String? = null,
    spark: Series? = null,
    onClick: (() -> Unit)? = null,
) {
    val m = if (onClick != null) modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick) else modifier
    Surface(m, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Spacer(Modifier.height(6.dp))
            RollingText(value, MaterialTheme.typography.titleLarge)
            if (sub != null) {
                Text(sub, style = MaterialTheme.typography.bodySmall.merge(NumberStyle), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (spark != null) {
                Spacer(Modifier.height(8.dp))
                Sparkline(spark, color, Modifier.fillMaxWidth().height(30.dp))
            }
        }
    }
}

/** Plain stat: small label over a value (for summary grids). */
@Composable
fun MiniStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    sub: String? = null,
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleMedium.merge(NumberStyle), color = color, maxLines = 1)
        if (sub != null) {
            Text(sub, style = MaterialTheme.typography.bodySmall.merge(NumberStyle), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** "205 %" over a period reads oddly; from one full battery up it is shown as batteries (2.1 batteries). */
fun batteriesValue(pct: Int): String = when {
    pct < 100 -> "$pct %"
    pct < 105 -> "بطارية واحدة"
    else -> "%.1f بطارية".format(pct / 100.0)
}

fun batteriesSub(pct: Int): String? = if (pct >= 100) "$pct %" else null

@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium.merge(NumberStyle), color = valueColor, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

/** Pulsing dot for "recording" states. */
@Composable
fun PulseDot(color: Color, active: Boolean, modifier: Modifier = Modifier) {
    val anim = if (active) {
        rememberInfiniteTransition(label = "dot")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "dotP")
    } else null
    Canvas(modifier.size(14.dp)) {
        val r = size.minDimension / 2
        val p = anim?.value ?: 0f
        if (active) drawCircle(color.copy(alpha = 0.4f * (1 - p)), radius = r * (0.5f + 0.5f * p))
        drawCircle(if (active) color else color.copy(alpha = 0.5f), radius = r * 0.45f)
    }
}

/** Battery level colour: red below 15 %, amber below 35 %, else green. */
@Composable
fun levelColor(pct: Int): Color {
    val c = LocalChartColors.current
    return when {
        pct < 0 -> MaterialTheme.colorScheme.outline
        pct < 15 -> c.bad
        pct < 35 -> lerp(c.bad, c.warn, ((pct - 15) / 20f).coerceIn(0f, 1f))
        pct < 50 -> lerp(c.warn, c.level, ((pct - 35) / 15f).coerceIn(0f, 1f))
        else -> c.level
    }
}

/**
 * 270° gauge ring with animated fill and gradient; [flowing] adds a light running along the arc
 * (used while charging). The slot draws in the middle.
 */
@Composable
fun GaugeRing(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    stroke: Dp = 16.dp,
    flowing: Boolean = false,
    center: @Composable BoxScope.() -> Unit,
) {
    val intro = remember { Animatable(0f) }
    LaunchedEffect(Unit) { intro.animateTo(1f, tween(1200, easing = FastOutSlowInEasing)) }
    val target by animateFloatAsState(fraction.coerceIn(0f, 1f), spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow), label = "ring")
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val flowAnim = if (flowing) {
        rememberInfiniteTransition(label = "flow")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart), label = "flowP")
    } else null
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val sw = stroke.toPx()
            val d = size.minDimension - sw * 1.6f
            val topLeft = Offset((size.width - d) / 2f, (size.height - d) / 2f)
            val arcSize = Size(d, d)
            val sweepTotal = 270f
            val value = target * intro.value
            rotate(135f) {
                drawArc(track, 0f, sweepTotal, false, topLeft, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
                if (value > 0.001f) {
                    val brush = Brush.sweepGradient(
                        0f to color.copy(alpha = 0.55f),
                        (sweepTotal / 360f) * value to color,
                        1f to color,
                        center = Offset(size.width / 2f, size.height / 2f),
                    )
                    drawArc(color.copy(alpha = 0.16f), 0f, sweepTotal * value, false, topLeft, arcSize, style = Stroke(sw * 1.9f, cap = StrokeCap.Round))
                    drawArc(brush, 0f, sweepTotal * value, false, topLeft, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
                    if (flowAnim != null) {
                        val fp = flowAnim.value
                        val seg = 26f
                        val start = (sweepTotal * value + seg) * fp - seg
                        val s0 = start.coerceAtLeast(0f)
                        val s1 = (start + seg).coerceAtMost(sweepTotal * value)
                        if (s1 > s0) {
                            drawArc(Color.White.copy(alpha = 0.45f), s0, s1 - s0, false, topLeft, arcSize, style = Stroke(sw * 0.55f, cap = StrokeCap.Round))
                        }
                    }
                }
            }
        }
        center()
    }
}

/** Horizontal level bar from [from] % to [to] %, coloured by direction. */
@Composable
fun LevelBar(from: Int, to: Int, minPct: Int, maxPct: Int, modifier: Modifier = Modifier) {
    val c = LocalChartColors.current
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier.height(8.dp)) {
        val r = size.height / 2
        drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
        val lo = (minPct.coerceIn(0, 100) / 100f) * size.width
        val hi = (maxPct.coerceIn(0, 100) / 100f) * size.width
        val color = if (to >= from) c.charging else c.drain
        if (hi > lo) {
            drawRoundRect(
                Brush.horizontalGradient(listOf(color.copy(alpha = 0.45f), color), startX = lo, endX = hi),
                topLeft = Offset(lo, 0f), size = Size(hi - lo, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r),
            )
        }
    }
}

/** Minimal Markdown for release notes: headings, bullets, quotes, **bold**, `code`, [links](…). */
@Composable
fun MarkdownLite(text: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        text.lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.height(6.dp))
                line.startsWith("#") -> {
                    val level = line.takeWhile { it == '#' }.length
                    Text(
                        line.drop(level).trim(),
                        style = if (level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                }
                line.startsWith(">") -> Row(Modifier.height(IntrinsicSize.Min).padding(vertical = 2.dp)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(10.dp))
                    Text(inline(line.drop(1).trim()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                    val indent = (line.length - line.trimStart().length) / 2
                    Row(Modifier.padding(start = (indent * 14).dp, top = 2.dp)) {
                        Text("•", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(8.dp))
                        Text(inline(line.trimStart().drop(2)), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                line.startsWith("---") -> Spacer(Modifier.height(4.dp))
                else -> Text(inline(line), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 1.dp))
            }
        }
    }
}

private fun inline(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) -> {
                val end = s.indexOf("**", i + 2)
                if (end < 0) { append(s.substring(i)); return@buildAnnotatedString }
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(s.substring(i + 2, end)) }
                i = end + 2
            }
            s[i] == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end < 0) { append(s.substring(i)); return@buildAnnotatedString }
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(s.substring(i + 1, end)) }
                i = end + 1
            }
            s[i] == '[' && s.indexOf("](", i) > 0 && s.indexOf(')', s.indexOf("](", i)) > 0 -> {
                val mid = s.indexOf("](", i)
                val end = s.indexOf(')', mid)
                withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(s.substring(i + 1, mid)) }
                i = end + 1
            }
            else -> {
                append(s[i])
                i++
            }
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
fun Gap(h: Dp = 12.dp) = Spacer(Modifier.height(h))
