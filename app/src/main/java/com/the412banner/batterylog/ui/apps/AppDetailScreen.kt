package com.the412banner.batterylog.ui.apps

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.the412banner.batterylog.BatteryLogApp
import com.the412banner.batterylog.Fmt
import com.the412banner.batterylog.TempUnit
import com.the412banner.batterylog.apps.AppStore
import com.the412banner.batterylog.apps.AppSummary
import com.the412banner.batterylog.apps.MRow
import com.the412banner.batterylog.apps.Stat
import com.the412banner.batterylog.apps.TRow
import com.the412banner.batterylog.ui.components.AppCard
import com.the412banner.batterylog.ui.components.MiniStat
import com.the412banner.batterylog.ui.components.SectionCard
import com.the412banner.batterylog.ui.components.Series
import com.the412banner.batterylog.ui.components.TimeChart
import com.the412banner.batterylog.ui.components.rememberChartState
import com.the412banner.batterylog.ui.theme.LocalChartColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private class Detail(val summary: AppSummary?, val timeline: List<TRow>, val minutes: List<MRow>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(pkg: String, back: () -> Unit) {
    val ctx = LocalContext.current
    val app = BatteryLogApp.get(ctx)
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    var period by rememberSaveable { mutableStateOf(Period.Today) }
    var data by remember { mutableStateOf<Detail?>(null) }
    LaunchedEffect(pkg, period) {
        data = null
        val r = period.range()
        data = withContext(Dispatchers.IO) {
            runCatching {
                val tl = AppStore.readTimeline(ctx, r.first, r.last)
                val mins = AppStore.readMinutes(ctx, r.first, r.last, pkg)
                val sum = AppStore.summaries(ctx, r.first, r.last).firstOrNull { it.pkg == pkg }
                Detail(sum, tl, mins)
            }.getOrDefault(Detail(null, emptyList(), emptyList()))
        }
    }
    val label = remember(pkg) { appLabel(ctx, pkg) }
    val icon = remember(pkg) { appIcon(ctx, pkg) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (icon != null) Image(icon, null, Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(label, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                            Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "رجوع") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        val d = data
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HudCard(pkg)
            Choice(Period.entries.map { it.label }, period.ordinal) { period = Period.entries[it] }
            if (d == null) {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            val s = d.summary
            if (s == null) {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    Text("لم يُسجل شيء لهذا التطبيق في هذه الفترة", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Column
            }
            Charts(pkg, d, settings.tempUnit, period)
            Summary(s, settings.tempUnit)
            if (settings.showAllMetrics) AllMetrics(pkg, d, settings.tempUnit)
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun tempStr(st: Stat, unit: TempUnit): String =
    if (st.n == 0) "—" else Fmt.tempShort(st.avg.toDouble(), unit) + " / " + Fmt.tempShort(st.max.toDouble(), unit)

@Composable
private fun Summary(s: AppSummary, unit: TempUnit) {
    val c = LocalChartColors.current
    SectionCard("أثناء الاستخدام", Icons.Rounded.Bolt, tint = c.power) {
        Grid(
            listOf(
                "وقت الشاشة" to Fmt.duration(s.fgMs),
                "الطاقة" to "%.2f Wh".format(s.energyWh),
                "الطاقة المتوسط / الذروة" to if (s.drawW.n == 0) "—" else "%.2f / %.2f W".format(s.drawW.avg, s.drawW.max),
                "حمل GPU المتوسط / الذروة" to if (s.gpuPct.n == 0) "—" else "%.0f / %.0f %%".format(s.gpuPct.avg, s.gpuPct.max),
                "CPU التطبيق المتوسط / الذروة" to if (s.fgCpuPct.n == 0) "—" else "%.1f / %.1f %%".format(s.fgCpuPct.avg, s.fgCpuPct.max),
                "وقت CPU (الكل)" to Fmt.duration(s.cpuMs),
            ),
        )
    }
    SectionCard("الحرارة (المتوسط / الأعلى)", Icons.Rounded.Thermostat, tint = c.temp) {
        Grid(
            listOf(
                "CPU" to tempStr(s.cpuC, unit),
                "GPU" to tempStr(s.gpuC, unit),
                "السطح" to tempStr(s.skinC, unit),
                "البطارية" to tempStr(s.battC, unit),
            ),
        )
    }
    SectionCard("الذاكرة", Icons.Rounded.Memory, tint = c.level) {
        Grid(
            listOf(
                "متوسط RAM" to mb(s.rssAvgKb),
                "ذروة RAM" to mb(s.rssMaxKb),
                "ذروة ذاكرة GPU" to mb(s.gpuMaxKb),
            ),
        )
    }
}

@Composable
private fun Grid(items: List<Pair<String, String>>) {
    items.chunked(2).forEachIndexed { i, row ->
        if (i > 0) Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            row.forEach { (l, v) -> MiniStat(l, v, Modifier.weight(1f)) }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

private fun seriesOf(rows: List<TRow>, pkg: String, f: (TRow) -> Float): Series {
    val t = LongArray(rows.size) { rows[it].time }
    val v = FloatArray(rows.size) { i -> val r = rows[i]; if (r.fg == pkg) f(r) else Float.NaN }
    return Series(t, v)
}

private fun minuteSeries(rows: List<MRow>, f: (MRow) -> Float): Series =
    Series(LongArray(rows.size) { rows[it].minute }, FloatArray(rows.size) { f(rows[it]) })

/** Metrics the app chart can show (chips like the History screen). */
private enum class AppMetric(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Power("الطاقة", Icons.Rounded.Bolt),
    Cpu("CPU التطبيق", Icons.Rounded.Speed),
    Gpu("GPU", Icons.Rounded.Videocam),
    CpuTemp("حرارة CPU", Icons.Rounded.Thermostat),
    GpuTemp("حرارة GPU", Icons.Rounded.Thermostat),
    Ram("RAM", Icons.Rounded.Memory),
    GpuMem("ذاكرة GPU", Icons.Rounded.Memory),
    CpuAll("CPU (دائماً)", Icons.Rounded.Speed),
}

/** Charging periods (shaded on the chart). */
private fun chargingBands(rows: List<TRow>): List<LongRange> {
    val out = ArrayList<LongRange>()
    var start = -1L
    var last = -1L
    for (r in rows) {
        if (r.charging) {
            if (start < 0) start = r.time
            last = r.time
        } else if (start >= 0) {
            out += start..last; start = -1L
        }
    }
    if (start >= 0) out += start..last
    return out
}

@Composable
private fun Charts(pkg: String, d: Detail, unit: TempUnit, period: Period) {
    val c = LocalChartColors.current
    val chart = rememberChartState()
    var metric by rememberSaveable { mutableStateOf(AppMetric.Power) }
    val cores = remember { Runtime.getRuntime().availableProcessors().coerceAtLeast(1) }
    fun tc(v: Float) = if (v.isNaN()) v else if (unit == TempUnit.F) v * 9f / 5f + 32f else v
    val tempAxis: (Float) -> String = { "%.0f°".format(it) }
    val tempValue: (Float) -> String = { if (unit == TempUnit.F) "%.1f °F".format(it) else "%.1f °C".format(it) }
    val mbAxis: (Float) -> String = { if (it >= 1024) "%.1fG".format(it / 1024f) else "%.0fM".format(it) }
    val mbValue: (Float) -> String = { if (it >= 1024) "%.2f GB".format(it / 1024f) else "%.0f MB".format(it) }
    val pctAxis: (Float) -> String = { "%.0f%%".format(it) }
    val pctValue: (Float) -> String = { "%.1f %%".format(it) }
    val bands = remember(d) { chargingBands(d.timeline) }

    val series: Series
    val color: Color
    var axis: (Float) -> String = pctAxis
    var value: (Float) -> String = pctValue
    var minRange = 5f
    var fixedMin: Float? = null
    var fixedMax: Float? = null
    when (metric) {
        AppMetric.Power -> {
            series = seriesOf(d.timeline, pkg) { it.drawW.toFloat() }; color = c.power
            axis = { "%.1fW".format(it) }; value = { "%.2f W".format(it) }; minRange = 1f; fixedMin = 0f
        }
        AppMetric.Cpu -> { series = seriesOf(d.timeline, pkg) { it.fgCpuPct }; color = c.voltage; fixedMin = 0f }
        AppMetric.Gpu -> { series = seriesOf(d.timeline, pkg) { it.gpuPct }; color = c.current; fixedMin = 0f; fixedMax = 100f }
        AppMetric.CpuTemp -> { series = seriesOf(d.timeline, pkg) { tc(it.cpuC) }; color = c.temp; axis = tempAxis; value = tempValue; minRange = 4f }
        AppMetric.GpuTemp -> { series = seriesOf(d.timeline, pkg) { tc(it.gpuC) }; color = c.warn; axis = tempAxis; value = tempValue; minRange = 4f }
        AppMetric.Ram -> { series = minuteSeries(d.minutes) { it.rssAvgKb / 1024f }; color = c.level; axis = mbAxis; value = mbValue; minRange = 50f }
        AppMetric.GpuMem -> { series = seriesOf(d.timeline, pkg) { it.fgGpuKb / 1024f }; color = c.level; axis = mbAxis; value = mbValue; minRange = 20f }
        AppMetric.CpuAll -> { series = minuteSeries(d.minutes) { it.cpuMs * 100f / (60_000f * cores) }; color = c.voltage; fixedMin = 0f }
    }

    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppMetric.entries.forEach { m ->
            FilterChip(
                selected = metric == m,
                onClick = { metric = m },
                label = { Text(m.label) },
                leadingIcon = { Icon(m.icon, null, Modifier.size(16.dp)) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
            )
        }
    }
    AppCard {
        TimeChart(
            series = series,
            state = chart,
            color = color,
            bands = bands,
            bandColor = c.charging,
            axisFormat = axis,
            valueFormat = value,
            fixedMin = fixedMin,
            fixedMax = fixedMax,
            minRange = minRange,
            gapMs = 90_000L,
            animateKey = Triple(metric, period, d.timeline.size > 0),
            emptyText = if (metric == AppMetric.Ram || metric == AppMetric.CpuAll) "لم يُسجل شيء في هذا النطاق" else "لم يكن على الشاشة في هذا النطاق",
            modifier = Modifier.fillMaxWidth().height(260.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "اسحب للقراءة · قرّب للتكبير · انقر مرتين لإعادة الضبط",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.animation.AnimatedVisibility(chart.zoomed) {
                TextButton(onClick = { chart.reset() }) { Text("إعادة") }
            }
        }
    }
}

/** Every recorded metric for this app: min / avg / max tables and the raw samples (behind "Show all metrics"). */
@Composable
private fun AllMetrics(pkg: String, d: Detail, unit: TempUnit) {
    val c = LocalChartColors.current
    val fg = d.timeline.filter { it.fg == pkg }
    fun tc(v: Float) = if (v.isNaN()) v else if (unit == TempUnit.F) v * 9f / 5f + 32f else v
    val metrics: List<Pair<String, Stat>> = listOf(
        "سحب الطاقة (W)" to Stat.of(fg.asSequence().map { it.drawW.toFloat() }),
        "البطارية (%)" to Stat.of(fg.asSequence().map { it.level.toFloat().takeIf { v -> v >= 0 } ?: Float.NaN }),
        "CPU الجهاز (%)" to Stat.of(fg.asSequence().map { it.cpuPct }),
        "CPU التطبيق (%)" to Stat.of(fg.asSequence().map { it.fgCpuPct }),
        "حمل GPU (%)" to Stat.of(fg.asSequence().map { it.gpuPct }),
        "ساعة GPU (MHz)" to Stat.of(fg.asSequence().map { it.gpuMhz }),
        "حرارة CPU" to Stat.of(fg.asSequence().map { tc(it.cpuC) }),
        "حرارة GPU" to Stat.of(fg.asSequence().map { tc(it.gpuC) }),
        "حرارة السطح" to Stat.of(fg.asSequence().map { tc(it.skinC) }),
        "حرارة الذاكرة" to Stat.of(fg.asSequence().map { tc(it.ddrC) }),
        "حرارة البطارية" to Stat.of(fg.asSequence().map { tc(it.battC) }),
        "RAM التطبيق (MB)" to Stat.of(fg.asSequence().map { it.fgRssKb / 1024f }),
        "ذاكرة GPU للتطبيق (MB)" to Stat.of(fg.asSequence().map { it.fgGpuKb / 1024f }),
        "CPU مللي ثانية في الدقيقة (الكل)" to Stat.of(d.minutes.asSequence().map { it.cpuMs.toFloat() }),
        "متوسط RAM في الدقيقة (MB)" to Stat.of(d.minutes.asSequence().map { it.rssAvgKb / 1024f }),
        "ذروة RAM في الدقيقة (MB)" to Stat.of(d.minutes.asSequence().map { it.rssMaxKb / 1024f }),
        "ذروة ذاكرة GPU في الدقيقة (MB)" to Stat.of(d.minutes.asSequence().map { it.gpuMaxKb / 1024f }),
        "الطاقة في الدقيقة (mWh)" to Stat.of(d.minutes.asSequence().map { (it.fgEnergyMj / 3600.0).toFloat() }),
    )
    SectionCard("كل المقاييس", Icons.Rounded.TableChart, tint = c.current, subtitle = "${fg.size} عينة أثناء الاستخدام · ${d.minutes.size} دقيقة") {
        Row(Modifier.fillMaxWidth()) {
            Text("المقياس", Modifier.weight(1.6f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf("الأدنى", "المتوسط", "الأعلى").forEach {
                Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        metrics.forEach { (name, st) ->
            HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth()) {
                Text(name, Modifier.weight(1.6f), style = MaterialTheme.typography.bodySmall)
                listOf(st.min, st.avg, st.max).forEach { v ->
                    Text(if (st.n == 0) "—" else "%.1f".format(v), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    SectionCard("العينات", Icons.Rounded.Videocam, tint = c.voltage, subtitle = "الأحدث ${minOf(300, fg.size)} أثناء الاستخدام") {
        val header = "time      W     CPU%  app%  GPU%  MHz   CPU°  GPU°  skin° batt° RAM MB GPU MB"
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Text(header, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            fg.takeLast(300).asReversed().forEach { r ->
                fun n(v: Float, w: Int, dec: Int = 0) = (if (v.isNaN()) "—" else "%.${dec}f".format(v)).padEnd(w)
                val line = Fmt.time(r.time).padEnd(10) + n(r.drawW.toFloat(), 6, 2) + n(r.cpuPct, 6) + n(r.fgCpuPct, 6) +
                    n(r.gpuPct, 6) + n(r.gpuMhz, 6) + n(tc(r.cpuC), 6) + n(tc(r.gpuC), 6) + n(tc(r.skinC), 6) +
                    n(tc(r.battC), 6) + n(r.fgRssKb / 1024f, 7) + n(r.fgGpuKb / 1024f, 6)
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}

/** Per-app HUD switch + which metrics it shows in this app. */
@Composable
private fun HudCard(pkg: String) {
    val hud by com.the412banner.batterylog.hud.HudStore.flow.collectAsStateWithLifecycle()
    val metrics = hud.apps[pkg]
    val on = metrics != null
    SectionCard(
        "العارض العلوي", Icons.Rounded.Speed, tint = LocalChartColors.current.temp,
        subtitle = when {
            !hud.enabled -> "العارض العلوي متوقف في الإعدادات"
            on -> "يظهر فوق هذا التطبيق"
            else -> "متوقف لهذا التطبيق"
        },
        trailing = {
            androidx.compose.material3.Switch(checked = on, onCheckedChange = { v ->
                com.the412banner.batterylog.hud.HudStore.update { c ->
                    val m = c.apps.toMutableMap()
                    if (v) m[pkg] = com.the412banner.batterylog.hud.HudConfig.DEFAULT_METRICS else m.remove(pkg)
                    // Switching it on for an app also turns the HUD itself on.
                    c.copy(apps = m, enabled = if (v) true else c.enabled)
                }
            })
        },
    ) {
        if (metrics != null) com.the412banner.batterylog.ui.hud.HudMetricChips(pkg)
    }
}
