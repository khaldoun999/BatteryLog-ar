package com.the412banner.batterylog.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.the412banner.batterylog.AppSettings
import com.the412banner.batterylog.BatteryLogApp
import com.the412banner.batterylog.BatteryLogService
import com.the412banner.batterylog.BuildConfig
import com.the412banner.batterylog.Fmt
import com.the412banner.batterylog.TempUnit
import com.the412banner.batterylog.ThemeMode
import com.the412banner.batterylog.update.UpdateScheduler
import com.the412banner.batterylog.update.UpdateState
import com.the412banner.batterylog.ui.components.Pill
import com.the412banner.batterylog.ui.components.SectionCard
import com.the412banner.batterylog.ui.theme.LocalChartColors

class SettingsActions(
    val change: ((AppSettings) -> AppSettings) -> Unit = {},
    val openUpdates: () -> Unit = {},
    val openRepo: () -> Unit = {},
    val startAuto: () -> Unit = {},
)

@Composable
fun SettingsScreen(openUpdates: () -> Unit) {
    val context = LocalContext.current
    val app = BatteryLogApp.get(context)
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    val service by BatteryLogService.state.collectAsStateWithLifecycle()
    val update by app.updates.state.collectAsStateWithLifecycle()
    SettingsContent(
        settings = settings,
        serviceActive = service.active,
        updateAvailable = update is UpdateState.Available,
        actions = SettingsActions(
            change = { f ->
                val before = app.settings.current()
                app.settings.update(f)
                val after = app.settings.current()
                if (before.autoUpdateCheck != after.autoUpdateCheck) runCatching { UpdateScheduler.schedule(context, after.autoUpdateCheck) }
            },
            openUpdates = openUpdates,
            openRepo = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${BuildConfig.UPDATE_REPO}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
            startAuto = { BatteryLogService.startAuto(context) },
        ),
    )
}

@Composable
fun SettingsContent(settings: AppSettings, serviceActive: Boolean, updateAvailable: Boolean, actions: SettingsActions) {
    val c = LocalChartColors.current
    val s = settings
    val set = actions.change
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("الإعدادات", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 12.dp))

        SectionCard("المظهر", Icons.Rounded.Palette, tint = c.power) {
            Label("السمة")
            Segmented(listOf("النظام", "فاتح", "داكن", "أسود"), ThemeMode.entries.indexOf(s.theme)) { i -> set { it.copy(theme = ThemeMode.entries[i]) } }
            if (Build.VERSION.SDK_INT >= 31) {
                SwitchRow("ألوان الخلفية", s.dynamicColor) { v -> set { it.copy(dynamicColor = v) } }
            }
            Spacer(Modifier.height(8.dp))
            Label("الحرارة")
            Segmented(listOf("°C", "°F"), if (s.tempUnit == TempUnit.C) 0 else 1) { i -> set { it.copy(tempUnit = if (i == 0) TempUnit.C else TempUnit.F) } }
        }

        SectionCard("التسجيل", Icons.Rounded.Tune, tint = c.current) {
            Label("التسجيل كل")
            val choices = AppSettings.SAMPLE_CHOICES
            Segmented(choices.map { "$it ث" }, choices.indexOf(s.sampleSeconds).coerceAtLeast(0)) { i -> set { it.copy(sampleSeconds = choices[i]) } }
            Spacer(Modifier.height(6.dp))
            SwitchRow("الاستئناف بعد إعادة التشغيل", s.resumeAfterRestart) { v -> set { it.copy(resumeAfterRestart = v) } }
            Spacer(Modifier.height(6.dp))
            Label("حذف السجلات الأقدم من")
            val keep = AppSettings.KEEP_CHOICES
            Segmented(keep.map { if (it == 0) "أبداً" else "${it} يوم" }, keep.indexOf(s.keepDays).coerceAtLeast(0)) { i -> set { it.copy(keepDays = keep[i]) } }
        }

        SectionCard("التنبيهات", Icons.Rounded.NotificationsActive, tint = c.warn, subtitle = "تعمل أثناء تشغيل الوضع التلقائي أو التسجيل") {
            SwitchRow("منبه الشحن", s.chargeAlarm, sub = "عند ${s.chargeAlarmPct} %") { v -> set { it.copy(chargeAlarm = v) } }
            AnimatedVisibility(s.chargeAlarm) {
                IntSlider(s.chargeAlarmPct, 50..100, 5) { v -> set { it.copy(chargeAlarmPct = v) } }
            }
            SwitchRow("بطارية منخفضة", s.lowAlert, sub = "عند ${s.lowAlertPct} %") { v -> set { it.copy(lowAlert = v) } }
            AnimatedVisibility(s.lowAlert) {
                IntSlider(s.lowAlertPct, 5..50, 5) { v -> set { it.copy(lowAlertPct = v) } }
            }
            SwitchRow("بطارية ساخنة", s.tempAlert, sub = "عند ${Fmt.temp(s.tempAlertC.toDouble(), s.tempUnit)}") { v -> set { it.copy(tempAlert = v) } }
            AnimatedVisibility(s.tempAlert) {
                IntSlider(s.tempAlertC, 35..60, 1) { v -> set { it.copy(tempAlertC = v) } }
            }
            AnimatedVisibility(s.anyAlert && !serviceActive) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("لا شيء يعمل الآن", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    FilledTonalButton(onClick = actions.startAuto) { Text("تفعيل التلقائي") }
                }
            }
        }

        SectionCard("مراقبة التطبيقات", Icons.Rounded.Apps, tint = c.temp, subtitle = "تحتاج إلى روت أو Shizuku") {
            SwitchRow("مراقبة كل تطبيق", s.perApp) { v -> set { it.copy(perApp = v) } }
            AnimatedVisibility(s.perApp) {
                Column {
                    Spacer(Modifier.height(6.dp))
                    Label("التسجيل كل")
                    val pc = AppSettings.PER_APP_CHOICES
                    Segmented(pc.map { "$it ث" }, pc.indexOf(s.perAppSeconds).coerceAtLeast(0)) { i -> set { it.copy(perAppSeconds = pc[i]) } }
                    Spacer(Modifier.height(6.dp))
                    SwitchRow("إظهار كل المقاييس", s.showAllMetrics) { v -> set { it.copy(showAllMetrics = v) } }
                    SwitchRow("استخدام Shizuku بدلاً من الروت", s.perAppShizuku) { v -> set { it.copy(perAppShizuku = v) } }
                }
            }
            Spacer(Modifier.height(6.dp))
            SensorReportButton()
        }

        HudSettingsCard()

        SectionCard(
            "التحديثات", Icons.Rounded.SystemUpdate, tint = MaterialTheme.colorScheme.primary,
            onClick = actions.openUpdates,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (updateAvailable) Pill("إصدار جديد")
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
                }
            },
        ) {
            SwitchRow("التحقق تلقائياً", s.autoUpdateCheck) { v -> set { it.copy(autoUpdateCheck = v) } }
            SwitchRow("تضمين الإصدارات التجريبية", s.includePrereleases) { v -> set { it.copy(includePrereleases = v) } }
        }

        SectionCard("حول التطبيق", Icons.Rounded.Code, tint = MaterialTheme.colorScheme.secondary, onClick = actions.openRepo) {
            Text("سجل البطارية ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})", style = MaterialTheme.typography.bodyMedium)
            Text("github.com/${BuildConfig.UPDATE_REPO}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun Segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
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

@Composable
private fun SwitchRow(title: String, checked: Boolean, sub: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun IntSlider(value: Int, range: IntRange, step: Int, onDone: (Int) -> Unit) {
    var pos by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val steps = ((range.last - range.first) / step - 1).coerceAtLeast(0)
    Slider(
        value = pos,
        onValueChange = { pos = it },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = steps,
        onValueChangeFinished = { onDone(kotlin.math.round(pos).toInt()) },
    )
}

/** Collects what the device exposes (sensors, GPU, battery) and shares it as a text file; also copied. */
@Composable
private fun SensorReportButton() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    FilledTonalButton(enabled = !busy, onClick = {
        busy = true
        scope.launch {
            val f = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { com.the412banner.batterylog.apps.SensorReport.build(ctx) }.getOrNull()
            }
            busy = false
            if (f != null) {
                runCatching {
                    ctx.getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("تقرير حساسات سجل البطارية", f.readText()))
                }
                com.the412banner.batterylog.ui.run.shareFile(ctx, f, "text/plain")
            }
        }
    }) { Text(if (busy) "جارٍ قراءة الحساسات…" else "مشاركة تقرير الحساسات") }
}

@Composable
private fun HudSettingsCard() {
    val ctx = LocalContext.current
    val app = BatteryLogApp.get(ctx)
    val hud by com.the412banner.batterylog.hud.HudStore.flow.collectAsStateWithLifecycle()
    var canDraw by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(ctx)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        canDraw = android.provider.Settings.canDrawOverlays(ctx)
        onPauseOrDispose { }
    }
    val c = LocalChartColors.current
    fun set(f: (com.the412banner.batterylog.hud.HudConfig) -> com.the412banner.batterylog.hud.HudConfig) =
        com.the412banner.batterylog.hud.HudStore.update(f)
    fun askOverlay() {
        runCatching {
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
    val scope = rememberCoroutineScope()
    // With root or Shizuku the monitor service allows it itself (works where Android marks the setting
    // "restricted" for apps installed from a file); otherwise Android's settings page.
    fun allowOverlay() {
        scope.launch {
            val priv = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.the412banner.batterylog.apps.ShizukuAccess.refresh() == com.the412banner.batterylog.apps.ShizukuAccess.State.Ready ||
                    com.the412banner.batterylog.RootHelper.rootCached()
            }
            if (priv) {
                com.the412banner.batterylog.apps.AppMonitorService.requestOverlay()
                repeat(40) {
                    if (android.provider.Settings.canDrawOverlays(ctx)) { canDraw = true; return@launch }
                    kotlinx.coroutines.delay(250)
                }
            }
            askOverlay()
        }
    }
    LaunchedEffect(hud.enabled, canDraw) {
        while (hud.enabled && !canDraw) {
            kotlinx.coroutines.delay(1000)
            canDraw = android.provider.Settings.canDrawOverlays(ctx)
        }
    }
    SectionCard(
        "العارض العلوي", Icons.Rounded.Tune, tint = c.temp,
        subtitle = if (hud.apps.isEmpty()) "لم تُضف أي لعبة" else "مفعّل في ${hud.apps.size} تطبيق",
    ) {
        SwitchRow("العارض العلوي", hud.enabled) { v ->
            set { it.copy(enabled = v) }
            if (v) {
                // The HUD rides on per-app monitoring (app on screen + sensors).
                app.settings.update { it.copy(perApp = true) }
                if (!canDraw) allowOverlay()
            }
        }
        AnimatedVisibility(hud.enabled) {
            Column {
                if (!canDraw) {
                    FilledTonalButton(onClick = { allowOverlay() }) { Text("السماح بالعرض فوق التطبيقات الأخرى") }
                    Spacer(Modifier.height(6.dp))
                }
                Label("الموضع")
                Segmented(listOf("الأعلى", "الوسط", "الأسفل"), hud.row) { r -> set { it.at(r, it.col) } }
                Spacer(Modifier.height(6.dp))
                Segmented(listOf("يسار", "وسط", "يمين"), hud.col) { c -> set { it.at(it.row, c) } }
                Spacer(Modifier.height(8.dp))
                Label("التخطيط")
                Segmented(listOf("أفقي", "عمودي"), if (hud.oneLine) 0 else 1) { i -> set { it.copy(oneLine = i == 0) } }
                Spacer(Modifier.height(8.dp))
                Label("الحجم")
                Segmented(listOf("صغير", "متوسط", "كبير"), hud.size) { i -> set { it.copy(size = i) } }
                Spacer(Modifier.height(8.dp))
                Label("الخلفية ${hud.opacity} %")
                IntSlider(hud.opacity, 0..100, 10) { v -> set { it.copy(opacity = v) } }
                SwitchRow("قفل الموضع", hud.locked, sub = if (hud.locked) "اللمسات تمر إلى اللعبة" else "اسحب العارض لنقله") { v -> set { it.copy(locked = v) } }
                Spacer(Modifier.height(8.dp))
                Label("الألعاب")
                com.the412banner.batterylog.ui.hud.HudAppsSection()
            }
        }
    }
}
