package com.the412banner.batterylog.ui.apps

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.the412banner.batterylog.BatteryLogApp
import com.the412banner.batterylog.Fmt
import com.the412banner.batterylog.apps.AppMonitorService
import com.the412banner.batterylog.apps.AppStore
import com.the412banner.batterylog.apps.AppSummary
import com.the412banner.batterylog.ui.components.AppCard
import com.the412banner.batterylog.ui.components.PulseDot
import com.the412banner.batterylog.ui.components.SectionCard
import com.the412banner.batterylog.ui.theme.LocalChartColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** Periods offered on the Apps screens. */
enum class Period(val label: String) {
    Today("اليوم"), Day("24 س"), Week("7 أيام");

    fun range(now: Long = System.currentTimeMillis()): LongRange = when (this) {
        Today -> LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()..now
        Day -> (now - 24 * 3_600_000L)..now
        Week -> (now - 7 * 24 * 3_600_000L)..now
    }
}

enum class SortBy(val label: String) { Energy("البطارية"), Screen("الشاشة"), Cpu("CPU"), Memory("RAM"), Gpu("GPU") }

private val iconCache = HashMap<String, ImageBitmap?>()

fun appLabel(ctx: Context, pkg: String): String =
    runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

fun appIcon(ctx: Context, pkg: String): ImageBitmap? = iconCache.getOrPut(pkg) {
    runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
}

fun mb(kb: Long): String = if (kb <= 0) "—" else if (kb >= 1024 * 1024) "%.1f GB".format(kb / 1048576.0) else "%.0f MB".format(kb / 1024.0)

@Composable
fun AppsScreen(openApp: (String) -> Unit) {
    val ctx = LocalContext.current
    val app = BatteryLogApp.get(ctx)
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    val running by AppMonitorService.running.collectAsStateWithLifecycle()
    val latest by AppMonitorService.latest.collectAsStateWithLifecycle()
    val error by AppMonitorService.lastError.collectAsStateWithLifecycle()
    val access by AppMonitorService.access.collectAsStateWithLifecycle()
    val shizuku by com.the412banner.batterylog.apps.ShizukuAccess.state.collectAsStateWithLifecycle()
    var period by rememberSaveable { mutableStateOf(Period.Today) }
    var sort by rememberSaveable { mutableStateOf(SortBy.Energy) }
    var list by remember { mutableStateOf<List<AppSummary>?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); tick++ } }
    LaunchedEffect(period, tick) {
        val r = period.range()
        list = withContext(Dispatchers.IO) { runCatching { AppStore.summaries(ctx, r.first, r.last) }.getOrDefault(emptyList()) }
    }
    val sorted = (list ?: emptyList()).sortedByDescending {
        when (sort) {
            SortBy.Energy -> it.energyMj
            SortBy.Screen -> it.fgMs.toDouble()
            SortBy.Cpu -> it.cpuMs.toDouble()
            SortBy.Memory -> it.rssMaxKb.toDouble()
            SortBy.Gpu -> it.gpuMaxKb.toDouble()
        }
    }.filter { it.fgMs > 0 || it.cpuMs > 0 }
    val top = sorted.firstOrNull()?.let { metricOf(it, sort) } ?: 1.0
    val c = LocalChartColors.current

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("التطبيقات", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 12.dp))
        }
        item {
            SectionCard(
                "مراقبة التطبيقات", Icons.Rounded.Apps, tint = c.temp,
                subtitle = when {
                    !settings.perApp -> "متوقفة"
                    error != null -> error
                    running -> "تسجل كل ${settings.perAppSeconds} ث" + (access?.let { " · $it" } ?: "")
                    else -> "جارٍ البدء…"
                },
                trailing = { PulseDot(c.temp, active = settings.perApp && running && error == null) },
            ) {
                if (!settings.perApp) {
                    FilledTonalButton(onClick = { app.settings.update { it.copy(perApp = true) } }) { Text("تفعيل") }
                } else if (error != null && shizuku == com.the412banner.batterylog.apps.ShizukuAccess.State.NeedsPermission) {
                    FilledTonalButton(onClick = { com.the412banner.batterylog.apps.ShizukuAccess.requestPermission() }) { Text("السماح لـ Shizuku") }
                } else latest?.let { t ->
                    val unit = settings.tempUnit
                    Text(
                        (if (t.fg.isEmpty()) "الشاشة مطفأة" else appLabel(ctx, t.fg)) + " · " +
                            (if (t.drawW.isNaN()) "يُشحن" else Fmt.watts(t.drawW)) +
                            " · CPU ${"%.0f".format(t.cpuPct)}% · GPU ${"%.0f".format(t.gpuPct)}%" +
                            (if (!t.cpuC.isNaN()) " · " + Fmt.tempShort(t.cpuC.toDouble(), unit) else ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        item { Choice(Period.entries.map { it.label }, period.ordinal) { period = Period.entries[it] } }
        item { Choice(SortBy.entries.map { it.label }, sort.ordinal) { sort = SortBy.entries[it] } }
        if (list != null && sorted.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(if (settings.perApp) "لم يُسجل نشاط لأي تطبيق بعد" else "فعّل مراقبة التطبيقات لرؤية التطبيقات",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(sorted, key = { it.pkg }) { s -> AppRow(s, sort, top, onClick = { openApp(s.pkg) }) }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

private fun metricOf(s: AppSummary, sort: SortBy): Double = when (sort) {
    SortBy.Energy -> s.energyMj
    SortBy.Screen -> s.fgMs.toDouble()
    SortBy.Cpu -> s.cpuMs.toDouble()
    SortBy.Memory -> s.rssMaxKb.toDouble()
    SortBy.Gpu -> s.gpuMaxKb.toDouble()
}.coerceAtLeast(1.0)

@Composable
private fun AppRow(s: AppSummary, sort: SortBy, top: Double, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalChartColors.current
    val label = remember(s.pkg) { appLabel(ctx, s.pkg) }
    val icon = remember(s.pkg) { appIcon(ctx, s.pkg) }
    AppCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Image(icon, null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
            else Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "الشاشة ${Fmt.duration(s.fgMs)}" +
                        (if (s.fgMs > 0 && s.energyMj <= 0.0) " · على الشاحن" else if (s.energyMj > 0.0) " · ${"%.2f".format(s.energyWh)} Wh" else "") +
                        (if (s.drawW.n > 0) " · المتوسط ${Fmt.watts(s.drawW.avg.toDouble())}" else ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
                Text(
                    "CPU ${Fmt.duration(s.cpuMs)} · RAM ${mb(s.rssMaxKb)} · ذاكرة GPU ${mb(s.gpuMaxKb)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        val frac = (metricOf(s, sort) / top).toFloat().coerceIn(0.02f, 1f)
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            Box(Modifier.fillMaxWidth(frac).height(4.dp).clip(CircleShape).background(c.power))
        }
    }
}

@Composable
fun Choice(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { i, l ->
            SegmentedButton(
                selected = i == selected,
                onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(i, labels.size),
                icon = {},
            ) { Text(l, maxLines = 1) }
        }
    }
}
