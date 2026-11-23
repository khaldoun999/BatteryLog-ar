package com.the412banner.batterylog.apps

import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.the412banner.batterylog.BatteryLogApp
import com.the412banner.batterylog.Notifier
import com.the412banner.batterylog.ServiceNotification
import com.the412banner.batterylog.hud.HudMetric
import com.the412banner.batterylog.hud.HudOverlay
import com.the412banner.batterylog.hud.HudStore
import com.the412banner.batterylog.hud.HudText
import com.the412banner.batterylog.hud.HudValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-app monitoring (root or Shizuku): every few seconds records device power, CPU/GPU load, temperatures and the
 * app on screen, plus per-app CPU time, memory and GPU memory. Runs as its own foreground service.
 */
class AppMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sampler: AppSampler? = null
    private var hud: HudOverlay? = null
    private var fgPkg = ""
    private var lastEventQuery = 0L
    private var minute = 0L
    private val acc = HashMap<String, MinuteAcc>()

    private class MinuteAcc(val uid: Int) {
        var cpuMs = 0L; var rssSum = 0L; var rssMax = 0L; var gpuMax = 0L; var fgMs = 0L; var energyMj = 0.0; var n = 0
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // "إيقاف التطبيقات" from the notification switches the setting off too.
            BatteryLogApp.get(this).settings.update { it.copy(perApp = false) }
            stopSelf()
            return START_NOT_STICKY
        }
        startFg("جارٍ البدء…")
        if (sampler == null) {
            sampler = AppSampler(this)
            _running.value = true
            scope.launch { loop() }
        }
        return START_STICKY
    }

    @Suppress("UNUSED_PARAMETER")
    private fun startFg(text: String) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        // Shares the battery logger's notification (one notification for both).
        ServiceCompat.startForeground(this, Notifier.ID_SERVICE, ServiceNotification.build(this), type)
    }

    private suspend fun loop() {
        val app = BatteryLogApp.get(this)
        // The app on screen comes from usage events; root or Shizuku grants that access.
        var usageGranted = false
        overlayAsk.set(true)
        AppStore.prune(this, app.settings.current().keepDays)
        lastEventQuery = System.currentTimeMillis() - 60_000
        var lastNotify = 0L
        while (scope.isActive) {
            val s = app.settings.current()
            if (!s.perApp) break
            val tick = sampler?.sample()
            if (tick == null) {
                hud?.hide()
                _access.value = null
                _lastError.value = "يحتاج إلى روت أو Shizuku"
                ServiceNotification.refreshOrCancel(this)
                nap(if (ShizukuAccess.state.value == ShizukuAccess.State.NotRunning) 30_000 else 5_000)
                continue
            }
            if (!usageGranted) { sampler?.grantUsageAccess(packageName); usageGranted = true }
            if (overlayAsk.getAndSet(false) && HudStore.current().enabled && !Settings.canDrawOverlays(this)) {
                sampler?.grantOverlay(packageName)
            }
            _access.value = sampler?.access
            _lastError.value = null
            val fg = foreground()
            if (tick.dtMs > 0) record(tick, fg)
            val hudShown = updateHud(tick, fg, s.tempUnit)
            val now = System.currentTimeMillis()
            if (now - lastNotify > 15_000) {
                lastNotify = now
                ServiceNotification.refreshOrCancel(this)
            }
            // While the HUD is on screen it (and the recording) refreshes every second.
            nap(if (hudShown) 1000L else s.perAppSeconds.coerceIn(1, 60) * 1000L)
        }
        stopSelf()
    }

    /** Waits [ms], or less when [requestOverlay] is called. */
    private suspend fun nap(ms: Long) { withTimeoutOrNull(ms) { wake.receive() } }

    /** Shows the HUD over [fg] when it has one; returns true while shown. */
    private fun updateHud(t: DeviceTick, fg: String, unit: com.the412banner.batterylog.TempUnit): Boolean {
        val cfg = HudStore.current()
        val metrics = if (cfg.enabled && fg.isNotEmpty()) cfg.apps[fg] else null
        val overlay = hud ?: HudOverlay(this).also { hud = it }
        if (metrics.isNullOrEmpty() || !overlay.allowed()) { overlay.hide(); return false }
        val fps = if (HudMetric.Fps in metrics) sampler?.fps(fg) ?: Float.NaN else Float.NaN
        val v = HudValues(
            fps = fps, cpuPct = t.cpuPct, gpuPct = t.gpuPct, gpuMhz = t.gpuMhz,
            cpuC = t.cpuC, gpuC = t.gpuC, caseC = t.skinC, battC = t.battC,
            battPct = t.level, watts = t.watts, charging = t.charging, time = t.time,
        )
        overlay.show(HudText.parts(v, metrics, unit), cfg)
        return true
    }

    private fun record(t: DeviceTick, fg: String) {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val fgTick = t.apps[fg]
        val fgCpuPct = if (fgTick != null && t.dtMs > 0) fgTick.cpuMs * 100f / (t.dtMs * cores) else Float.NaN
        val row = TRow(
            t.time, fg, t.watts, t.level, t.charging, t.cpuPct, t.gpuPct, t.gpuMhz, t.cpuC, t.gpuC, t.skinC, t.ddrC, t.battC,
            fgCpuPct, fgTick?.rssKb ?: 0, fgTick?.gpuKb ?: 0,
        )
        AppStore.appendTimeline(this, row)
        _latest.value = row

        val thisMinute = t.time / 60_000 * 60_000
        if (minute != 0L && thisMinute != minute) flushMinute()
        minute = thisMinute
        val drawW = row.drawW.takeIf { !it.isNaN() } ?: 0.0
        for ((pkg, a) in t.apps) {
            val m = acc.getOrPut(pkg) { MinuteAcc(a.uid) }
            m.cpuMs += a.cpuMs; m.rssSum += a.rssKb; m.rssMax = maxOf(m.rssMax, a.rssKb); m.gpuMax = maxOf(m.gpuMax, a.gpuKb); m.n++
            if (pkg == fg) { m.fgMs += t.dtMs; m.energyMj += drawW * t.dtMs }
        }
        if (fg.isNotEmpty() && fg !in t.apps) {
            val m = acc.getOrPut(fg) { MinuteAcc(-1) }
            m.fgMs += t.dtMs; m.energyMj += drawW * t.dtMs
        }
    }

    private fun flushMinute() {
        val rows = acc.filter { (_, m) -> m.cpuMs > 0 || m.fgMs > 0 }.map { (pkg, m) ->
            MRow(minute, pkg, m.uid, m.cpuMs, if (m.n > 0) m.rssSum / m.n else 0, m.rssMax, m.gpuMax, m.fgMs, m.energyMj, m.n)
        }
        acc.clear()
        runCatching { AppStore.appendMinutes(this, rows) }
    }

    /** Package on screen from usage events; "" while the screen is off. */
    private fun foreground(): String {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isInteractive) return ""
        val usm = getSystemService(UsageStatsManager::class.java) ?: return fgPkg
        val now = System.currentTimeMillis()
        runCatching {
            val events = usm.queryEvents(lastEventQuery, now)
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) fgPkg = e.packageName
            }
        }
        lastEventQuery = now
        return fgPkg
    }

    override fun onDestroy() {
        runCatching { flushMinute() }
        scope.cancel()
        sampler?.close()
        sampler = null
        hud?.hide()
        hud = null
        _access.value = null
        _running.value = false
        // Keep the shared notification if battery logging still runs; remove it otherwise.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        ServiceNotification.refreshOrCancel(this)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "stop"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()
        private val _access = MutableStateFlow<String?>(null)
        /** "root" or "Shizuku" while recording. */
        val access: StateFlow<String?> = _access.asStateFlow()
        private val _latest = MutableStateFlow<TRow?>(null)
        val latest: StateFlow<TRow?> = _latest.asStateFlow()
        private val _lastError = MutableStateFlow<String?>(null)
        val lastError: StateFlow<String?> = _lastError.asStateFlow()

        private val overlayAsk = AtomicBoolean(true)
        private val wake = Channel<Unit>(Channel.CONFLATED)

        /** Asks the running service to allow "display over other apps" through root or Shizuku on its next pass. */
        fun requestOverlay() {
            overlayAsk.set(true)
            wake.trySend(Unit)
        }

        fun start(ctx: Context) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, AppMonitorService::class.java)) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.startService(Intent(ctx, AppMonitorService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
