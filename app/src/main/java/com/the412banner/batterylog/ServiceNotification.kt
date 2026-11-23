package com.the412banner.batterylog

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.the412banner.batterylog.apps.AppMonitorService

/**
 * One notification for both background services (battery logging + per-app monitoring).
 * Both use [Notifier.ID_SERVICE]; when one stops it detaches and the other keeps the notification.
 */
object ServiceNotification {
    private val labels = HashMap<String, String>()

    fun label(ctx: Context, pkg: String): String = labels.getOrPut(pkg) {
        runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    fun build(ctx: Context, b: BatteryInfo = BatteryInfo.read(ctx)): Notification {
        val bs = BatteryLogService.state.value
        val appsOn = AppMonitorService.running.value
        val unit = BatteryLogApp.get(ctx).settings.current().tempUnit
        val title = when {
            bs.auto && bs.recording -> "تلقائي · يسجل على البطارية"
            bs.auto -> "تلقائي · بانتظار فصل الشاحن"
            bs.recording -> "يسجل"
            appsOn -> "مراقبة التطبيقات"
            else -> "سجل البطارية"
        }
        val battery = buildList {
            if (b.capacityPct >= 0) add("${b.capacityPct} %")
            if (!b.watts.isNaN()) add(Fmt.watts(b.watts))
            if (!b.tempC.isNaN()) add(Fmt.temp(b.tempC, unit))
            if (b.voltageMv > 0) add(Fmt.voltsShort(b.voltageMv))
        }.joinToString("  ·  ")
        val apps = if (!appsOn) null else AppMonitorService.lastError.value ?: AppMonitorService.latest.value?.let { t ->
            buildList {
                add(if (t.fg.isEmpty()) "الشاشة مطفأة" else label(ctx, t.fg))
                if (!t.cpuPct.isNaN()) add("CPU ${"%.0f".format(t.cpuPct)}%")
                if (!t.gpuPct.isNaN()) add("GPU ${"%.0f".format(t.gpuPct)}%")
                if (!t.cpuC.isNaN()) add(Fmt.tempShort(t.cpuC.toDouble(), unit))
            }.joinToString("  ·  ")
        } ?: "جارٍ البدء…"
        val text = if (apps != null) "$battery\n$apps" else battery
        val builder = NotificationCompat.Builder(ctx, Notifier.CH_LOGGING)
            .setSmallIcon(R.drawable.ic_stat_battery)
            .setContentTitle(title)
            .setContentText(if (apps != null) "$battery  |  $apps" else battery)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(Notifier.openAppIntent(ctx, if (appsOn && !bs.active) "apps" else null))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (bs.active) {
            builder.addAction(
                0, "إيقاف التسجيل",
                PendingIntent.getService(
                    ctx, 0, Intent(ctx, BatteryLogService::class.java).setAction(BatteryLogService.ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        if (appsOn) {
            builder.addAction(
                0, "إيقاف التطبيقات",
                PendingIntent.getService(
                    ctx, 1, Intent(ctx, AppMonitorService::class.java).setAction(AppMonitorService.ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        return builder.build()
    }

    /** Re-posts the shared notification, or removes it when neither service runs any more. */
    fun refreshOrCancel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (BatteryLogService.state.value.active || AppMonitorService.running.value) {
            runCatching { nm.notify(Notifier.ID_SERVICE, build(ctx)) }
        } else {
            nm.cancel(Notifier.ID_SERVICE)
        }
    }
}
