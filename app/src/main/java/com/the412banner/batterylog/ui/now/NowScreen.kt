package com.the412banner.batterylog.ui.now

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.the412banner.batterylog.Analysis
import com.the412banner.batterylog.AppSettings
import com.the412banner.batterylog.BatteryInfo
import com.the412banner.batterylog.BatteryLogApp
import com.the412banner.batterylog.BatteryLogService
import com.the412banner.batterylog.Estimates
import com.the412banner.batterylog.Fmt
import com.the412banner.batterylog.SampleSet
import com.the412banner.batterylog.Storage
import com.the412banner.batterylog.ui.Metric
import com.the412banner.batterylog.ui.components.AppCard
import com.the412banner.batterylog.ui.components.ChartState
import com.the412banner.batterylog.ui.components.GaugeRing
import com.the412banner.batterylog.ui.components.MiniStat
import com.the412banner.batterylog.ui.components.Pill
import com.the412banner.batterylog.ui.components.PulseDot
import com.the412banner.batterylog.ui.components.SectionCard
import com.the412banner.batterylog.ui.components.Series
import com.the412banner.batterylog.ui.components.StatTile
import com.the412banner.batterylog.ui.components.TimeChart
import com.the412banner.batterylog.ui.components.levelColor
import com.the412banner.batterylog.ui.components.rememberChartState
import com.the412banner.batterylog.ui.theme.LocalChartColors
import com.the412banner.batterylog.ui.theme.NumberStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

data class LiveTick(val battery: BatteryInfo, val estimates: Estimates.Result?)

data class NowUi(
    val battery: BatteryInfo = BatteryInfo(),
    val estimates: Estimates.Result? = null,
    val service: BatteryLogService.State = BatteryLogService.State(),
    val settings: AppSettings = AppSettings(),
    val recent: SampleSet = SampleSet.EMPTY,
    val live: List<BatteryInfo> = emptyList(),
    val session: Analysis.Session? = null,
    val needsFilesAccess: Boolean = false,
    val model: String = Build.MODEL ?: "",
)

class NowViewModel(private val app: BatteryLogApp) : ViewModel() {
    @Volatile private var rates: Analysis.Rates? = null
    @Volatile private var ratesAt = 0L
    private val liveBuffer = ArrayDeque<BatteryInfo>()

    private val ticks = flow {
        while (true) {
            val b = BatteryInfo.read(app)
            synchronized(liveBuffer) {
                liveBuffer.addLast(b)
                while (liveBuffer.size > 1 && b.time - liveBuffer.first().time > 30 * 60_000L) liveBuffer.removeFirst()
            }
            emit(LiveTick(b, Estimates.compute(b, rates)))
            delay(2_000)
        }
    }.flowOn(Dispatchers.IO)

    private val recent = flow {
        while (true) {
            val now = System.currentTimeMillis()
            val set = runCatching { app.logs.window(now - 24 * 3_600_000L) }.getOrDefault(SampleSet.EMPTY)
            if (now - ratesAt > 5 * 60_000L) {
                rates = runCatching { Analysis.rates(app.logs.window(now - 14 * 86_400_000L)) }.getOrNull()
                ratesAt = now
            }
            emit(set)
            delay(30_000)
        }
    }.flowOn(Dispatchers.IO)

    val ui: StateFlow<NowUi> = combine(ticks, recent, BatteryLogService.state, app.settings.flow) { tick, set, svc, s ->
        val live = synchronized(liveBuffer) { liveBuffer.toList() }
        NowUi(
            battery = tick.battery,
            estimates = tick.estimates,
            service = svc,
            settings = s,
            recent = set,
            live = live,
            session = Analysis.currentSession(set)?.takeIf { System.currentTimeMillis() - it.lastSample < 15 * 60_000L },
            needsFilesAccess = !Storage.canUsePublic(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NowUi(battery = BatteryInfo.read(app), settings = app.settings.current()))
}

class NowActions(
    val toggleAuto: (Boolean) -> Unit = {},
    val record: () -> Unit = {},
    val stop: () -> Unit = {},
    val openHistory: () -> Unit = {},
    val grantFiles: () -> Unit = {},
)

@Composable
fun NowScreen(actions: NowActions) {
    val app = BatteryLogApp.get(androidx.compose.ui.platform.LocalContext.current)
    val vm: NowViewModel = viewModel { NowViewModel(app) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    NowContent(ui, actions)
}

@Composable
fun NowContent(ui: NowUi, actions: NowActions, chart: ChartState = rememberChartState()) {
    val c = LocalChartColors.current
    val b = ui.battery
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(ui)
        Hero(ui)
        if (ui.needsFilesAccess) {
            AppCard(onClick = actions.grantFiles, color = MaterialTheme.colorScheme.tertiaryContainer) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.FolderOpen, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("حافظ على سلامة السجلات", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text(
                            "اسمح بالوصول إلى الملفات حتى تُحفظ السجلات في /sdcard/BatteryLog وتبقى بعد إعادة التثبيت.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }
        }

        // Live metric tiles with 30-minute sparklines.
        val unit = ui.settings.tempUnit
        val now = b.time
        fun spark(pick: (BatteryInfo) -> Double, fromLog: Metric): Series {
            val since = now - 30 * 60_000L
            val logPart = fromLog.series(ui.recent, unit)
            val start = logPart.indexAtOrAfter(since)
            val liveStart = ui.live.firstOrNull()?.time ?: Long.MAX_VALUE
            val ts = ArrayList<Long>(); val vs = ArrayList<Float>()
            for (i in start until logPart.size) if (logPart.t[i] < liveStart) { ts += logPart.t[i]; vs += logPart.v[i] }
            for (x in ui.live) { ts += x.time; vs += pick(x).toFloat() }
            return Series(ts.toLongArray(), vs.toFloatArray())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                "الطاقة", if (b.watts.isNaN()) "—" else Fmt.watts(b.watts), Metric.Power.icon,
                if (b.watts >= 0) c.charging else c.power, Modifier.weight(1f),
                sub = when {
                    b.watts.isNaN() -> null
                    b.watts >= 0 -> "إلى البطارية"
                    else -> "من البطارية"
                },
                spark = spark({ it.watts }, Metric.Power),
            )
            StatTile(
                "التيار", Fmt.ma(b.currentMa), Metric.Current.icon, c.current, Modifier.weight(1f),
                sub = if (!b.avgCurrentMa.isNaN()) "المتوسط ${Fmt.ma(b.avgCurrentMa)}" else null,
                spark = spark({ it.currentMa }, Metric.Current),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                "الجهد", Fmt.volts(b.voltageMv), Metric.Voltage.icon, c.voltage, Modifier.weight(1f),
                sub = b.technology.ifBlank { null },
                spark = spark({ it.voltageMv / 1000.0 }, Metric.Voltage),
            )
            StatTile(
                "الحرارة", Fmt.temp(b.tempC, unit), Metric.Temp.icon, c.temp, Modifier.weight(1f),
                sub = "الصحة: ${b.health}",
                spark = spark({ x -> if (unit == com.the412banner.batterylog.TempUnit.F) x.tempC * 9 / 5 + 32 else x.tempC }, Metric.Temp),
            )
        }

        ui.session?.let { SessionCard(it) }
        RecentChart(ui, chart, actions.openHistory)
        LoggingCard(ui, actions)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Header(ui: NowUi) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("سجل البطارية", style = MaterialTheme.typography.headlineMedium)
            Text(ui.model, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val s = ui.service
        val c = LocalChartColors.current
        when {
            s.recording -> Pill("يسجل", c.bad)
            s.auto -> Pill("تلقائي", MaterialTheme.colorScheme.secondary)
            else -> Pill("خامل", MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun Hero(ui: NowUi) {
    val b = ui.battery
    val pct = b.capacityPct
    val color = if (b.onCharger) LocalChartColors.current.charging else levelColor(pct)
    AppCard {
        GaugeRing(
            fraction = if (pct < 0) 0f else pct / 100f,
            color = color,
            modifier = Modifier.fillMaxWidth().height(250.dp),
            flowing = b.isCharging,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (b.onCharger) Icon(Icons.Rounded.BatteryChargingFull, null, tint = color, modifier = Modifier.size(22.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(if (pct < 0) "—" else "$pct", style = MaterialTheme.typography.displayLarge)
                    Text("%", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp, start = 2.dp))
                }
                val status = buildString {
                    append(if (b.onCharger) (if (b.isCharging) "يُشحن" else b.status) else "على البطارية")
                    if (!b.watts.isNaN()) append(" · ").append(Fmt.watts(b.watts))
                }
                Text(status, style = MaterialTheme.typography.bodyMedium.merge(NumberStyle), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val e = ui.estimates
        if (e != null) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth().animateContentSize(), horizontalArrangement = Arrangement.SpaceEvenly) {
                if (e.charging) {
                    EstimateItem("تمتلئ خلال", Estimates.fmtMin(e.gaugeToFullMin), "النظام")
                    EstimateItem("متوسطك", Estimates.fmtMin(e.learnedToFullMin), e.rates?.chargePctPerH?.let { "%.0f %%/س".format(it) } ?: "جارٍ التعلم")
                } else {
                    EstimateItem("الوقت المتبقي", Estimates.fmtMin(e.gaugeToEmptyMin), "النظام")
                    EstimateItem("متوسطك", Estimates.fmtMin(e.learnedRemainingMin), e.rates?.drainPctPerH?.let { "%.1f %%/س".format(it) } ?: "جارٍ التعلم")
                    EstimateItem("شحنة كاملة", Estimates.fmtMin(e.learnedFullRuntimeMin), "100 → 0 %")
                }
            }
        }
    }
}

@Composable
private fun EstimateItem(label: String, value: String, sub: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge.merge(NumberStyle))
        Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SessionCard(s: Analysis.Session) {
    val c = LocalChartColors.current
    val color = if (s.charging) c.charging else c.drain
    SectionCard(
        title = if (s.charging) "يُشحن منذ ${Fmt.time(s.since)}" else "على البطارية منذ ${Fmt.time(s.since)}",
        icon = if (s.charging) Icons.Rounded.Power else Icons.Rounded.Timeline,
        tint = color,
        subtitle = Fmt.relativeDateTime(s.since),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MiniStat("المستوى", "${s.fromPct} → ${s.nowPct} %")
            MiniStat("التغير", (if (s.deltaPct > 0) "+" else "") + "${s.deltaPct} %", color = color)
            MiniStat("الوقت", Fmt.duration(s.elapsedMs))
            MiniStat("المعدل", s.ratePctPerH?.let { "%.1f %%/س".format(it) } ?: "—")
        }
    }
}

@Composable
private fun RecentChart(ui: NowUi, chart: ChartState, openHistory: () -> Unit) {
    val c = LocalChartColors.current
    var metric by rememberSaveable { mutableStateOf(Metric.Level) }
    val unit = ui.settings.tempUnit
    val series = remember(ui.recent, metric, unit) { metric.series(ui.recent, unit) }
    val bands = remember(ui.recent) { Analysis.chargingBands(ui.recent) }
    SectionCard(
        title = "آخر 24 ساعة",
        icon = Icons.Rounded.History,
        tint = metric.color(c),
        trailing = { androidx.compose.material3.TextButton(onClick = openHistory) { Text("السجل") } },
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Metric.Level, Metric.Power, Metric.Temp, Metric.Voltage).forEach { m ->
                FilterChip(
                    selected = metric == m,
                    onClick = { metric = m },
                    label = { Text(m.label) },
                    leadingIcon = { Icon(m.icon, null, Modifier.size(16.dp)) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = m.color(c).copy(alpha = 0.2f)),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        TimeChart(
            series = series,
            state = chart,
            color = metric.color(c),
            negativeColor = metric.negativeColor(c),
            bands = bands,
            bandColor = c.charging,
            axisFormat = metric.axis(unit),
            valueFormat = metric.value(unit),
            fixedMin = metric.fixedMin,
            fixedMax = metric.fixedMax,
            minRange = metric.minRange,
            live = ui.service.recording,
            animateKey = metric,
            emptyText = "لم يُسجل شيء في آخر 24 ساعة",
            modifier = Modifier.fillMaxWidth().height(190.dp),
        )
        AnimatedVisibility(chart.zoomed) {
            androidx.compose.material3.TextButton(onClick = { chart.reset() }) { Text("إعادة التكبير") }
        }
    }
}

@Composable
private fun LoggingCard(ui: NowUi, actions: NowActions) {
    val s = ui.service
    val c = LocalChartColors.current
    SectionCard(
        title = "التسجيل",
        icon = Icons.Rounded.FiberManualRecord,
        tint = if (s.recording) c.bad else MaterialTheme.colorScheme.secondary,
        subtitle = when {
            s.recording && s.auto -> "تلقائي: يسجل هذا التفريغ"
            s.recording -> "يسجل كل ${s.intervalSec} ث"
            s.auto -> "تلقائي: يبدأ عند فصل الشاحن"
            else -> "لا يسجل"
        },
        trailing = { PulseDot(if (s.recording) c.bad else MaterialTheme.colorScheme.outline, s.recording) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("الوضع التلقائي", style = MaterialTheme.typography.titleSmall)
                Text(
                    "يسجل كل تفريغ من تلقاء نفسه",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = s.auto, onCheckedChange = actions.toggleAuto)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (s.recording && !s.auto) {
                Button(onClick = actions.stop, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Stop, null); Spacer(Modifier.width(6.dp)); Text("إيقاف")
                }
            } else if (!s.recording) {
                FilledTonalButton(onClick = actions.record, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("سجل الآن")
                }
            }
            if (s.active) {
                OutlinedButton(onClick = actions.stop, modifier = Modifier.weight(1f)) { Text("إيقاف الكل") }
            }
        }
        if (s.recording && s.logPath != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                "${java.io.File(s.logPath).name} · ${s.samples} عينة · منذ ${Fmt.time(s.startedAt)}",
                style = MaterialTheme.typography.bodySmall.merge(NumberStyle), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
