package com.the412banner.batterylog.ui.history

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.TrendingDown
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.the412banner.batterylog.Analysis
import com.the412banner.batterylog.BatteryLogApp
import com.the412banner.batterylog.Fmt
import com.the412banner.batterylog.LogInfo
import com.the412banner.batterylog.LogKind
import com.the412banner.batterylog.SampleSet
import com.the412banner.batterylog.TempUnit
import com.the412banner.batterylog.ui.Metric
import com.the412banner.batterylog.ui.components.AppCard
import com.the412banner.batterylog.ui.components.ChartState
import com.the412banner.batterylog.ui.components.IconBadge
import com.the412banner.batterylog.ui.components.LevelBar
import com.the412banner.batterylog.ui.components.MiniStat
import com.the412banner.batterylog.ui.components.batteriesSub
import com.the412banner.batterylog.ui.components.batteriesValue
import com.the412banner.batterylog.ui.components.SectionLabel
import com.the412banner.batterylog.ui.components.TimeChart
import com.the412banner.batterylog.ui.components.rememberChartState
import com.the412banner.batterylog.ui.theme.LocalChartColors
import com.the412banner.batterylog.ui.theme.NumberStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Range(val label: String, val ms: Long) {
    H6("6 س", 6 * 3_600_000L),
    H24("24 س", 24 * 3_600_000L),
    D7("7 أيام", 7 * 86_400_000L),
    D30("30 يوماً", 30 * 86_400_000L),
    D90("90 يوماً", 90 * 86_400_000L),
}

data class WindowStats(
    val batteryMs: Long = 0,
    val chargeMs: Long = 0,
    val drained: Int = 0,
    val charged: Int = 0,
    val drainPctPerH: Double? = null,
    val drainWatts: Double? = null,
    val minMv: Int = 0,
    val minMvAtCap: Int = -1,
    val tMin: Float? = null,
    val tMax: Float? = null,
    val sessions: Int = 0,
) {
    companion object {
        fun of(s: SampleSet): WindowStats {
            if (s.size < 2) return WindowStats()
            val r = Analysis.rates(s)
            var drained = 0; var charged = 0; var minMv = Int.MAX_VALUE; var minAt = -1
            var tMin = Float.MAX_VALUE; var tMax = -Float.MAX_VALUE; var sessions = 1
            for (i in 0 until s.size) {
                if (s.mv[i] in 1 until minMv) { minMv = s.mv[i]; minAt = s.cap[i].toInt() }
                val t = s.tempC(i)
                if (!t.isNaN()) { if (t < tMin) tMin = t; if (t > tMax) tMax = t }
                if (i > 0) {
                    if (s.t[i] - s.t[i - 1] > Analysis.GAP_MS) { sessions++; continue }
                    val d = s.cap[i] - s.cap[i - 1]
                    if (d < 0) drained -= d else charged += d
                }
            }
            return WindowStats(
                batteryMs = (r.batteryHours * 3_600_000).toLong(),
                chargeMs = (r.chargeHours * 3_600_000).toLong(),
                drained = drained, charged = charged,
                drainPctPerH = r.drainPctPerH, drainWatts = r.drainWatts,
                minMv = if (minMv == Int.MAX_VALUE) 0 else minMv, minMvAtCap = minAt,
                tMin = if (tMin == Float.MAX_VALUE) null else tMin,
                tMax = if (tMax == -Float.MAX_VALUE) null else tMax,
                sessions = sessions,
            )
        }
    }
}

data class HistoryUi(
    val range: Range = Range.H24,
    val metric: Metric = Metric.Level,
    val loading: Boolean = true,
    val set: SampleSet = SampleSet.EMPTY,
    val bands: List<LongRange> = emptyList(),
    val stats: WindowStats = WindowStats(),
    val runs: List<LogInfo> = emptyList(),
    val tempUnit: TempUnit = TempUnit.C,
    val activePath: String? = null,
)

class HistoryViewModel(private val app: BatteryLogApp) : ViewModel() {
    private val _ui = MutableStateFlow(HistoryUi(tempUnit = app.settings.current().tempUnit))
    val ui: StateFlow<HistoryUi> = _ui.asStateFlow()
    private var job: Job? = null

    init {
        load()
        viewModelScope.launch { app.logs.version.collect { if (it > 0) load() } }
    }

    fun setRange(r: Range) {
        if (r == _ui.value.range) return
        _ui.update { it.copy(range = r) }
        load()
    }

    fun setMetric(m: Metric) = _ui.update { it.copy(metric = m) }

    fun load() {
        job?.cancel()
        _ui.update { it.copy(loading = true, tempUnit = app.settings.current().tempUnit) }
        job = viewModelScope.launch {
            val range = _ui.value.range
            val runs = runCatching { app.logs.index() }.getOrDefault(emptyList())
            val set = runCatching { app.logs.window(System.currentTimeMillis() - range.ms) }.getOrDefault(SampleSet.EMPTY)
            val stats = WindowStats.of(set)
            val bands = Analysis.chargingBands(set)
            _ui.update {
                it.copy(
                    loading = false, set = set, bands = bands, stats = stats, runs = runs,
                    activePath = com.the412banner.batterylog.BatteryLogService.currentLogPath,
                )
            }
        }
    }
}

@Composable
fun HistoryScreen(openRun: (String) -> Unit) {
    val app = BatteryLogApp.get(LocalContext.current)
    val vm: HistoryViewModel = viewModel { HistoryViewModel(app) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    HistoryContent(ui, vm::setRange, vm::setMetric, openRun)
}

@Composable
fun HistoryContent(
    ui: HistoryUi,
    onRange: (Range) -> Unit,
    onMetric: (Metric) -> Unit,
    openRun: (String) -> Unit,
    chart: ChartState = rememberChartState(),
) {
    val c = LocalChartColors.current
    val unit = ui.tempUnit
    val series = remember(ui.set, ui.metric, unit) { ui.metric.series(ui.set, unit) }
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("السجل", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 12.dp))
        }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Range.entries.forEachIndexed { i, r ->
                    SegmentedButton(
                        selected = ui.range == r,
                        onClick = { onRange(r) },
                        shape = SegmentedButtonDefaults.itemShape(i, Range.entries.size),
                        icon = {},
                    ) { Text(r.label, maxLines = 1) }
                }
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric.entries.forEach { m ->
                    FilterChip(
                        selected = ui.metric == m,
                        onClick = { onMetric(m) },
                        label = { Text(m.label) },
                        leadingIcon = { Icon(m.icon, null, Modifier.size(16.dp)) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = m.color(c).copy(alpha = 0.2f)),
                    )
                }
            }
        }
        item {
            AppCard {
                Box(Modifier.fillMaxWidth().height(3.dp)) {
                    androidx.compose.animation.AnimatedVisibility(ui.loading, enter = fadeIn(), exit = fadeOut()) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
                TimeChart(
                    series = series,
                    state = chart,
                    color = ui.metric.color(c),
                    negativeColor = ui.metric.negativeColor(c),
                    bands = ui.bands,
                    bandColor = c.charging,
                    axisFormat = ui.metric.axis(unit),
                    valueFormat = ui.metric.value(unit),
                    fixedMin = ui.metric.fixedMin,
                    fixedMax = ui.metric.fixedMax,
                    minRange = ui.metric.minRange,
                    animateKey = Triple(ui.metric, ui.range, ui.set.size > 0),
                    emptyText = if (ui.loading) "جارٍ التحميل…" else "لم يُسجل شيء في هذا النطاق",
                    modifier = Modifier.fillMaxWidth().height(260.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "اسحب للقراءة · قرّب للتكبير · انقر مرتين لإعادة الضبط",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    AnimatedVisibility(chart.zoomed) {
                        TextButton(onClick = { chart.reset() }) { Text("إعادة") }
                    }
                }
            }
        }
        item { StatsGrid(ui) }
        item { SectionLabel("السجلات المسجلة") }
        if (ui.runs.isEmpty() && !ui.loading) {
            item {
                AppCard {
                    Text("لا سجلات بعد. فعّل الوضع التلقائي أو انقر «سجل الآن» في تبويب «الآن».", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        items(ui.runs, key = { it.path }) { run -> RunRow(run, run.path == ui.activePath) { openRun(run.path) } }
    }
}

@Composable
private fun StatsGrid(ui: HistoryUi) {
    val s = ui.stats
    val c = LocalChartColors.current
    AnimatedContent(targetState = s, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "stats") { st ->
        AppCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MiniStat("على البطارية", Fmt.duration(st.batteryMs), Modifier.weight(1f))
                MiniStat("استُخدم", batteriesValue(st.drained), Modifier.weight(1f), color = c.drain, sub = batteriesSub(st.drained))
                MiniStat("شُحن", batteriesValue(st.charged), Modifier.weight(1f), color = c.charging, sub = batteriesSub(st.charged))
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MiniStat("التفريغ", st.drainPctPerH?.let { "%.1f %%/س".format(it) } ?: "—", Modifier.weight(1f))
                MiniStat("متوسط الطاقة", st.drainWatts?.let { Fmt.watts(it) } ?: "—", Modifier.weight(1f))
                MiniStat("الشحن", Fmt.duration(st.chargeMs), Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MiniStat("أدنى جهد", if (st.minMv > 0) "${Fmt.voltsShort(st.minMv)} عند ${st.minMvAtCap}%" else "—", Modifier.weight(1f))
                MiniStat(
                    "الحرارة",
                    if (st.tMin != null && st.tMax != null) "${Fmt.tempShort(st.tMin.toDouble(), ui.tempUnit)}–${Fmt.tempShort(st.tMax.toDouble(), ui.tempUnit)}" else "—",
                    Modifier.weight(1f),
                )
                MiniStat("الجلسات", "${st.sessions}", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RunRow(run: LogInfo, active: Boolean, onClick: () -> Unit) {
    val c = LocalChartColors.current
    val net = run.endCap - run.startCap
    val icon = when {
        run.kind == LogKind.Boot -> Icons.Rounded.Memory
        net > 0 -> Icons.Rounded.BatteryChargingFull
        net < 0 -> Icons.Rounded.TrendingDown
        else -> Icons.Rounded.ShowChart
    }
    val tint = when {
        active -> c.bad
        run.kind == LogKind.Boot -> MaterialTheme.colorScheme.secondary
        net >= 0 -> c.charging
        else -> c.drain
    }
    AppCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, tint, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (run.start > 0) Fmt.relativeDateTime(run.start) else run.name,
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false),
                    )
                    if (active) {
                        Spacer(Modifier.width(8.dp))
                        com.the412banner.batterylog.ui.components.Pill("مباشر", c.bad)
                    }
                }
                val kind = when (run.kind) {
                    LogKind.Boot -> "سجل إقلاع"
                    LogKind.App -> "سجل التطبيق"
                    LogKind.Other -> "سجل"
                }
                Text(
                    "$kind · ${Fmt.duration(run.durationMs)} · ${run.count} عينة",
                    style = MaterialTheme.typography.bodySmall.merge(NumberStyle), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LevelBar(run.startCap, run.endCap, run.minCap, run.maxCap, Modifier.fillMaxWidth())
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text("${run.startCap} → ${run.endCap} %", style = MaterialTheme.typography.labelLarge.merge(NumberStyle))
                val parts = buildList {
                    if (run.drained > 0) add("−${run.drained}")
                    if (run.charged > 0) add("+${run.charged}")
                }
                if (parts.isNotEmpty()) {
                    Text(parts.joinToString(" / ") + " %", style = MaterialTheme.typography.bodySmall.merge(NumberStyle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
