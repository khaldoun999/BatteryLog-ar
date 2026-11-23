package com.the412banner.batterylog

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs

/** Display formatting shared by the UI, notifications and reports. */
object Fmt {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val timeF: DateTimeFormatter by lazy { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT) }
    private val dateF: DateTimeFormatter by lazy { DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()) }
    private val shortDateF: DateTimeFormatter by lazy { DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()) }
    private val dayF: DateTimeFormatter by lazy { DateTimeFormatter.ofPattern("EEE", Locale.getDefault()) }
    private val fullF: DateTimeFormatter by lazy { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT) }

    fun time(ms: Long): String = timeF.format(Instant.ofEpochMilli(ms).atZone(zone))
    fun date(ms: Long): String = dateF.format(Instant.ofEpochMilli(ms).atZone(zone))
    fun shortDate(ms: Long): String = shortDateF.format(Instant.ofEpochMilli(ms).atZone(zone))
    fun day(ms: Long): String = dayF.format(Instant.ofEpochMilli(ms).atZone(zone))
    fun dateTime(ms: Long): String = fullF.format(Instant.ofEpochMilli(ms).atZone(zone))

    /** "Today 6:21 PM", "Yesterday 9:02 AM", "Mon 3 Nov 9:02 AM". */
    fun relativeDateTime(ms: Long): String {
        val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        val prefix = when (d) {
            today -> "اليوم"
            today.minusDays(1) -> "أمس"
            else -> date(ms)
        }
        return "$prefix ${time(ms)}"
    }

    /** "2h 05m", "47m", "35s". */
    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val d = s / 86_400
        val h = (s % 86_400) / 3600
        val m = (s % 3600) / 60
        return when {
            d > 0 -> "${d} يوم ${h} س"
            h > 0 -> "${h} س %02d د".format(m)
            m > 0 -> "${m} د"
            else -> "${s} ث"
        }
    }

    fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
        val d = now - ms
        return if (d < 60_000) "الآن" else "منذ ${duration(d)}"
    }

    fun temp(c: Double, unit: TempUnit): String =
        if (c.isNaN()) "—" else when (unit) {
            TempUnit.C -> "%.1f °C".format(c)
            TempUnit.F -> "%.1f °F".format(c * 9 / 5 + 32)
        }

    fun tempShort(c: Double, unit: TempUnit): String =
        if (c.isNaN()) "—" else when (unit) {
            TempUnit.C -> "%.1f°".format(c)
            TempUnit.F -> "%.0f°F".format(c * 9 / 5 + 32)
        }

    fun watts(w: Double): String = if (w.isNaN()) "—" else if (abs(w) >= 10) "%.1f W".format(abs(w)) else "%.2f W".format(abs(w))
    fun volts(mv: Int): String = if (mv <= 0) "—" else "%.3f V".format(mv / 1000.0)
    fun voltsShort(mv: Int): String = if (mv <= 0) "—" else "%.2f V".format(mv / 1000.0)
    fun ma(ma: Double): String = if (ma.isNaN()) "—" else "%,.0f mA".format(abs(ma))
    fun amps(ua: Int): String = "%.1f A".format(ua / 1_000_000.0)

    fun bytes(b: Long): String = when {
        b >= 1_048_576 -> "%.1f MB".format(b / 1_048_576.0)
        b >= 1024 -> "%.0f KB".format(b / 1024.0)
        else -> "$b B"
    }
}
