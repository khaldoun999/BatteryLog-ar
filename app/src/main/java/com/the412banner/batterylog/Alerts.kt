package com.the412banner.batterylog

import android.content.Context

/**
 * Charge alarm, low-battery and hot-battery alerts. Evaluated by [BatteryLogService] on every battery
 * change while it runs (Auto or Monitoring). Each alert fires once, then re-arms after the condition clears.
 */
class Alerts(private val context: Context) {
    private var chargeFired = false
    private var lowFired = false
    private var tempFiredAt = 0L

    fun check(b: BatteryInfo, s: AppSettings) {
        val pct = b.capacityPct
        if (pct < 0) return

        if (s.chargeAlarm) {
            if (b.onCharger && pct >= s.chargeAlarmPct && !chargeFired) {
                chargeFired = true
                Notifier.show(
                    context, Notifier.ID_CHARGE, Notifier.CH_ALERTS,
                    "تم الشحن إلى $pct %",
                    "منبه الشحن مضبوط على ${s.chargeAlarmPct} %. يمكنك فصل الشاحن الآن.",
                )
            }
            if (!b.onCharger || pct < s.chargeAlarmPct - 2) chargeFired = false
        }

        if (s.lowAlert) {
            if (!b.onCharger && pct <= s.lowAlertPct && !lowFired) {
                lowFired = true
                Notifier.show(context, Notifier.ID_LOW, Notifier.CH_ALERTS, "البطارية عند $pct %", "وصّل الشاحن قريباً.")
            }
            if (b.onCharger || pct > s.lowAlertPct + 2) lowFired = false
        }

        if (s.tempAlert && !b.tempC.isNaN()) {
            val now = System.currentTimeMillis()
            if (b.tempC >= s.tempAlertC && now - tempFiredAt > 15 * 60_000L) {
                tempFiredAt = now
                Notifier.show(
                    context, Notifier.ID_TEMP, Notifier.CH_ALERTS,
                    "البطارية ساخنة: ${Fmt.temp(b.tempC, s.tempUnit)}",
                    "تجاوزت حد التنبيه الذي ضبطته: ${Fmt.temp(s.tempAlertC.toDouble(), s.tempUnit)}.",
                )
            }
            if (b.tempC < s.tempAlertC - 2) tempFiredAt = 0L
        }
    }
}
