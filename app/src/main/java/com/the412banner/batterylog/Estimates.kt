package com.the412banner.batterylog

import java.io.File

/**
 * Time-left / time-to-full. Two sources:
 *  - the fuel gauge's own estimate (time_to_empty_avg / time_to_full_now / time_to_full_avg in sysfs);
 *  - the learned average %/hour from the last 14 days of logs ([Analysis.rates]).
 */
object Estimates {

    private const val SYS = "/sys/class/power_supply/battery"

    data class Result(
        val charging: Boolean,
        val capacity: Int,
        val gaugeToEmptyMin: Int?,
        val gaugeToFullMin: Int?,
        val rates: Analysis.Rates?,
    ) {
        val learnedRemainingMin: Int? get() =
            rates?.drainPctPerH?.takeIf { it > 0 }?.let { (capacity / it * 60).toInt() }
        val learnedFullRuntimeMin: Int? get() =
            rates?.drainPctPerH?.takeIf { it > 0 }?.let { (100.0 / it * 60).toInt() }
        val learnedToFullMin: Int? get() =
            rates?.chargePctPerH?.takeIf { it > 0 }?.let { ((100 - capacity) / it * 60).toInt() }

        /** Best single figure for the headline: the gauge when it has one, else the learned average. */
        val headlineMin: Int? get() =
            if (charging) gaugeToFullMin ?: learnedToFullMin else gaugeToEmptyMin ?: learnedRemainingMin
    }

    @Volatile private var times: Pair<Int?, Int?> = null to null
    @Volatile private var timesAt = 0L

    /** Fuel-gauge estimates in minutes (cached 20 s). */
    fun gaugeTimes(): Pair<Int?, Int?> {
        if (System.currentTimeMillis() - timesAt < 20_000) return times
        var empty = node("time_to_empty_avg") ?: node("time_to_empty_now")
        var full = node("time_to_full_now") ?: node("time_to_full_avg")
        if ((empty == null || full == null) && RootHelper.knownRoot()) {
            val out = RootHelper.run(
                "for n in time_to_empty_avg time_to_empty_now time_to_full_now time_to_full_avg; do " +
                    "echo \"\$n=\$(cat $SYS/\$n 2>/dev/null)\"; done",
                timeoutSec = 10,
            ).out
            fun grab(n: String) = Regex("$n=(-?\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()?.takeIf(::plausible)
            empty = empty ?: grab("time_to_empty_avg") ?: grab("time_to_empty_now")
            full = full ?: grab("time_to_full_now") ?: grab("time_to_full_avg")
        }
        times = empty?.div(60) to full?.div(60)
        timesAt = System.currentTimeMillis()
        return times
    }

    private fun node(n: String): Int? =
        runCatching { File("$SYS/$n").readText().trim().toIntOrNull() }.getOrNull()?.takeIf(::plausible)

    /** Seconds; reject sentinels (-1, 0) and anything over 72 h. */
    private fun plausible(sec: Int): Boolean = sec in 60..(72 * 3600)

    fun compute(info: BatteryInfo, rates: Analysis.Rates?): Result {
        val (e, f) = gaugeTimes()
        return Result(
            charging = info.onCharger,
            capacity = info.capacityPct.coerceIn(0, 100),
            gaugeToEmptyMin = if (!info.onCharger) e else null,
            gaugeToFullMin = if (info.onCharger && info.capacityPct < 100) f else null,
            rates = rates,
        )
    }

    /** "8h 05m" / "47m" / "—". */
    fun fmtMin(min: Int?): String {
        if (min == null || min <= 0) return "—"
        val h = min / 60
        val m = min % 60
        return if (h > 0) "${h} س %02d د".format(m) else "${m} د"
    }
}
