package com.the412banner.batterylog.ui.run

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.the412banner.batterylog.Analysis
import com.the412banner.batterylog.BatteryLogApp
import com.the412banner.batterylog.Fmt
import com.the412banner.batterylog.LogInfo
import com.the412banner.batterylog.LogKind
import com.the412banner.batterylog.ReportText
import com.the412banner.batterylog.SampleSet
import com.the412banner.batterylog.TempUnit
import com.the412banner.batterylog.ui.Metric
import com.the412banner.batterylog.ui.components.AppCard
import com.the412banner.batterylog.ui.components.ChartState
import com.the412banner.batterylog.ui.components.IconBadge
import com.the412banner.batterylog.ui.components.InfoRow
import com.the412banner.batterylog.ui.components.MiniStat
import com.the412banner.batterylog.ui.components.batteriesSub
import com.the412banner.batterylog.ui.components.batteriesValue
import com.the412banner.batterylog.ui.components.SectionCard
import com.the412banner.batterylog.ui.components.TimeChart
import com.the412banner.batterylog.ui.components.rememberChartState
import com.the412banner.batterylog.ui.theme.LocalChartColors
import com.the412banner.batterylog.ui.theme.NumberStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class RunUi(
    val loading: Boolean = true,
    val info: LogInfo? = null,
    val set: SampleSet = SampleSet.EMPTY,
    val report: Analysis.Report? = null,
    val bands: List<LongRange> = emptyList(),
    val tempUnit: TempUnit = TempUnit.C,
)

class RunViewModel(private val app: BatteryLogApp, private val path: String) : ViewModel() {
    private val _ui = MutableStateFlow(RunUi(tempUnit = app.settings.current().tempUnit))
    val ui: StateFlow<RunUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val all = runCatching { app.logs.index() }.getOrDefault(emptyList())
            val info = all.firstOrNull { it.path == path }
            if (info == null) { _ui.value = _ui.value.copy(loading = false); return@launch }
            val set = app.logs.load(File(path))
            val next = all.filter { it.start > info.end - 60_000 && it.path != path }.minByOrNull { it.start }
            val report = withContext(Dispatchers.Default) { Analysis.report(info, set, next) }
            _ui.value = RunUi(false, info, set, report, Analysis.chargingBands(set), app.settings.current().tempUnit)
        }
    }

    suspend fun delete(): Boolean {
        val info = _ui.value.info ?: return false
        return app.logs.delete(info)
    }
}

@Composable
fun RunScreen(path: String, back: () -> Unit) {
    val app = BatteryLogApp.get(LocalContext.current)
    val vm: RunViewModel = viewModel(key = path) { RunViewModel(app, path) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    RunContent(
        ui = ui,
        back = back,
        onShareReport = { ui.report?.let { shareReport(context, it, ui.tempUnit) } },
        onShareCsv = { ui.info?.let { shareFile(context, File(it.path), "text/csv") } },
        onDelete = { confirmDelete = true },
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("حذف هذا السجل؟") },
            text = { Text(ui.info?.name.orEmpty() + "\nلا يمكن التراجع عن هذا.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { if (vm.delete()) back() }
                }) { Text("حذف") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("إلغاء") } },
        )
    }
}

@Composable
fun RunContent(
    ui: RunUi,
    back: () -> Unit,
    onShareReport: () -> Unit,
    onShareCsv: () -> Unit,
    onDelete: () -> Unit,
    chart: ChartState = rememberChartState(),
) {
    var menu by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(ui.info?.start?.takeIf { it > 0 }?.let { Fmt.relativeDateTime(it) } ?: "سجل", style = MaterialTheme.typography.titleLarge)
                        ui.info?.let {
                            Text(it.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "رجوع") } },
                actions = {
                    IconButton(onClick = onShareReport, enabled = ui.report != null) { Icon(Icons.Rounded.Share, "مشاركة التقرير") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "المزيد") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("مشاركة ملف CSV") },
                                leadingIcon = { Icon(Icons.Rounded.Description, null) },
                                onClick = { menu = false; onShareCsv() },
                            )
                            DropdownMenuItem(
                                text = { Text("حذف السجل") },
                                leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                                onClick = { menu = false; onDelete() },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        if (ui.loading) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val r = ui.report
        val info = ui.info
        if (r == null || info == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) { Text("لم يعد هذا السجل موجوداً.") }
            return@Scaffold
        }
        val c = LocalChartColors.current
        val unit = ui.tempUnit
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MiniStat("المدة", Fmt.duration(r.durationMs))
                    MiniStat("المستوى", "${r.startCap} → ${r.endCap} %")
                    MiniStat("استُخدم", batteriesValue(r.drained), color = c.drain, sub = batteriesSub(r.drained))
                    MiniStat("شُحن", batteriesValue(r.charged), color = c.charging, sub = batteriesSub(r.charged))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    (if (info.kind == LogKind.Boot) "سجل إقلاع · " else "") + "${info.count} عينة · ${Fmt.dateTime(info.start)} → ${Fmt.time(info.end)}",
                    style = MaterialTheme.typography.bodySmall.merge(NumberStyle), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Findings(r)

            // Charts share zoom and the readout, so dragging on one shows the same moment on all.
            listOf(Metric.Level, Metric.Power, Metric.Voltage, Metric.Temp, Metric.Current).forEach { m ->
                val series = remember(ui.set, m, unit) { m.series(ui.set, unit) }
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(m.icon, null, tint = m.color(c), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(m.label, style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(Modifier.height(6.dp))
                    TimeChart(
                        series = series,
                        state = chart,
                        color = m.color(c),
                        negativeColor = m.negativeColor(c),
                        bands = ui.bands,
                        bandColor = c.charging,
                        axisFormat = m.axis(unit),
                        valueFormat = m.value(unit),
                        fixedMin = m.fixedMin,
                        fixedMax = m.fixedMax,
                        minRange = m.minRange,
                        modifier = Modifier.fillMaxWidth().height(if (m == Metric.Level) 200.dp else 150.dp),
                    )
                }
            }

            SectionCard("السعة", Icons.Rounded.Insights, tint = c.level) {
                InfoRow("استُخدم على البطارية", r.usedMah?.let { "%,.0f mAh".format(it) } ?: "—")
                InfoRow("الشحنة الكاملة المقاسة", r.fullMah?.let { "%,.0f mAh".format(it) } ?: "يحتاج إلى هبوط ≥ 5 %")
                InfoRow("السعة التصميمية", r.designMah?.let { "%,d mAh".format(it) } ?: "غير معروف")
                InfoRow("مقياس الوقود الكامل", r.kernelFullMah?.let { "%,d mAh".format(it) } ?: "غير معروف")
                r.sohPct?.let { InfoRow("الصحة من هذه الجلسة", "%.0f %%".format(it), valueColor = if (it < 70) c.bad else c.level) }
            }
            SectionCard("الجهد", Metric.Voltage.icon, tint = c.voltage, subtitle = "حزمة ${r.cells}S") {
                InfoRow("البداية ← النهاية", "${r.vStart?.let { Fmt.volts(it) } ?: "—"} → ${r.vEnd?.let { Fmt.volts(it) } ?: "—"}")
                InfoRow("الأدنى", r.vMin?.let { "${Fmt.volts(it)} عند ${r.vMinAtCap} %" } ?: "—")
                r.vMin?.let { InfoRow("الأدنى لكل خلية", "%.3f V".format(it / 1000.0 / r.cells)) }
                InfoRow("الأعلى", r.vMax?.let { Fmt.volts(it) } ?: "—")
                if (r.curve.size >= 2) {
                    Spacer(Modifier.height(8.dp))
                    VoltageCurve(r.curve, c.voltage)
                }
            }
            SectionCard("الطاقة والتيار", Metric.Power.icon, tint = c.power) {
                InfoRow("متوسط السحب", r.avgDrainW?.let { Fmt.watts(it) } ?: "—")
                InfoRow("ذروة السحب", r.peakDrainW?.let { Fmt.watts(it) } ?: "—")
                InfoRow("متوسط التيار", r.avgDrainMa?.let { Fmt.ma(it) } ?: "—")
                InfoRow("ذروة التيار", r.peakDrainMa?.let { "${Fmt.ma(it)} عند ${r.peakDrainAtCap} %" } ?: "—")
                if (r.avgChargeW != null) {
                    InfoRow("متوسط الشحن", Fmt.watts(r.avgChargeW))
                    InfoRow("ذروة الشحن", r.peakChargeW?.let { Fmt.watts(it) } ?: "—")
                }
            }
            SectionCard("السرعة", Icons.Rounded.Insights, tint = c.current) {
                InfoRow("التفريغ", r.drainPctPerH?.let { "%.1f %%/س".format(it) } ?: "—")
                InfoRow("الشحنة الكاملة ستدوم", r.drainPctPerH?.takeIf { it > 0 }?.let { Fmt.duration((100 / it * 3_600_000).toLong()) } ?: "—")
                InfoRow("الشحن", r.chargePctPerH?.let { "%.1f %%/س".format(it) } ?: "—")
            }
            SectionCard("الحرارة", Metric.Temp.icon, tint = c.temp) {
                InfoRow("الأدنى", r.tMin?.let { Fmt.temp(it.toDouble(), unit) } ?: "—")
                InfoRow("المتوسط", r.tAvg?.let { Fmt.temp(it.toDouble(), unit) } ?: "—")
                InfoRow("الأعلى", r.tMax?.let { Fmt.temp(it.toDouble(), unit) } ?: "—", valueColor = if ((r.tMax ?: 0f) >= 45f) c.bad else MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun Findings(r: Analysis.Report) {
    val c = LocalChartColors.current
    if (r.findings.isEmpty()) {
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.CheckCircle, c.level)
                Spacer(Modifier.width(12.dp))
                Text("لا شيء غير معتاد في هذا السجل", style = MaterialTheme.typography.titleSmall)
            }
        }
        return
    }
    r.findings.forEach { f ->
        AppCard(color = c.warn.copy(alpha = 0.12f)) {
            Row(verticalAlignment = Alignment.Top) {
                IconBadge(Icons.Rounded.WarningAmber, c.warn)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(f.title, style = MaterialTheme.typography.titleSmall)
                    Text(f.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Voltage at each 10 % step as horizontal bars (sag shows as bars that drop off early). */
@Composable
private fun VoltageCurve(curve: List<Pair<Int, Int>>, color: Color) {
    val lo = curve.minOf { it.second }
    val hi = curve.maxOf { it.second }
    val span = (hi - lo).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        curve.forEach { (pct, mv) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("%3d %%".format(pct), style = MaterialTheme.typography.labelSmall.merge(NumberStyle), modifier = Modifier.width(44.dp))
                val frac = 0.25f + 0.75f * (mv - lo).toFloat() / span
                Box(Modifier.weight(1f).height(10.dp)) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        drawRoundRect(color.copy(alpha = 0.15f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
                        drawRoundRect(
                            color, size = androidx.compose.ui.geometry.Size(size.width * frac, size.height),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2),
                        )
                    }
                }
                Text(Fmt.volts(mv), style = MaterialTheme.typography.labelSmall.merge(NumberStyle), modifier = Modifier.padding(start = 8.dp).width(60.dp))
            }
        }
    }
}

fun shareFile(context: Context, file: File, mime: String) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

fun shareReport(context: Context, report: Analysis.Report, unit: TempUnit) {
    val text = ReportText.render(report, unit)
    runCatching {
        val csv = File(report.info.path)
        File(csv.parentFile, csv.nameWithoutExtension + "_report.txt").writeText(text)
    }
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        .putExtra(Intent.EXTRA_SUBJECT, "تقرير سجل البطارية")
    runCatching { context.startActivity(Intent.createChooser(send, "مشاركة التقرير").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
