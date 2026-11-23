package com.the412banner.batterylog

import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Number crunching over [SampleSet]s: run summaries, learned rates, "since last charge", run reports. */
object Analysis {

    /** Two samples further apart than this are treated as a logging gap (device off, app stopped). */
    const val GAP_MS = 5 * 60_000L

    fun summarize(file: File, s: SampleSet, firstUptime: Long): LogInfo {
        var drained = 0
        var charged = 0
        var minMv = Int.MAX_VALUE
        var maxTemp = Int.MIN_VALUE
        var minCap = 100
        var maxCap = 0
        for (i in 0 until s.size) {
            val c = s.cap[i].toInt()
            minCap = min(minCap, c); maxCap = max(maxCap, c)
            if (s.mv[i] > 0) minMv = min(minMv, s.mv[i])
            val tp = s.temp[i].toInt()
            if (tp > -400 && tp != -1) maxTemp = max(maxTemp, tp)
            if (i > 0 && s.t[i] - s.t[i - 1] <= GAP_MS) {
                val d = c - s.cap[i - 1]
                if (d < 0) drained -= d else charged += d
            }
        }
        return LogInfo(
            path = file.absolutePath,
            name = file.name,
            size = file.length(),
            modified = file.lastModified(),
            kind = LogRepository.kindOf(file.name),
            start = s.first,
            end = s.last,
            count = s.size,
            startCap = if (s.size > 0) s.cap[0].toInt() else -1,
            endCap = if (s.size > 0) s.cap[s.size - 1].toInt() else -1,
            minCap = if (s.size > 0) minCap else -1,
            maxCap = if (s.size > 0) maxCap else -1,
            drained = drained,
            charged = charged,
            minMv = if (minMv == Int.MAX_VALUE) 0 else minMv,
            maxTemp = if (maxTemp == Int.MIN_VALUE) -1000 else maxTemp,
            firstUptime = firstUptime,
        )
    }

    // ---- learned rates ----

    data class Rates(
        val drainPctPerH: Double?,
        val chargePctPerH: Double?,
        val drainWatts: Double?,
        val chargeWatts: Double?,
        val batteryHours: Double,
        val chargeHours: Double,
    )

    /** Average drain/charge speed over consecutive on-battery / on-charger samples (gaps skipped). */
    fun rates(s: SampleSet): Rates {
        var dMs = 0L; var dPct = 0; var dWs = 0.0; var dWms = 0L
        var cMs = 0L; var cPct = 0; var cWs = 0.0; var cWms = 0L
        for (i in 1 until s.size) {
            val dt = s.t[i] - s.t[i - 1]
            if (dt <= 0 || dt > GAP_MS) continue
            val onC = s.onCharger(i) && s.onCharger(i - 1)
            val onB = !s.onCharger(i) && !s.onCharger(i - 1)
            val dc = s.cap[i] - s.cap[i - 1]
            val w = s.watts(i)
            if (onB) {
                dMs += dt; if (dc < 0) dPct -= dc
                if (!w.isNaN() && w < 0) { dWs += -w * dt; dWms += dt }
            } else if (onC) {
                cMs += dt; if (dc > 0) cPct += dc
                if (!w.isNaN() && w > 0) { cWs += w * dt; cWms += dt }
            }
        }
        val dH = dMs / 3_600_000.0
        val cH = cMs / 3_600_000.0
        return Rates(
            drainPctPerH = if (dH >= 0.5 && dPct >= 3) dPct / dH else null,
            chargePctPerH = if (cH >= 0.25 && cPct >= 3) cPct / cH else null,
            drainWatts = if (dWms > 60_000) dWs / dWms else null,
            chargeWatts = if (cWms > 60_000) cWs / cWms else null,
            batteryHours = dH,
            chargeHours = cH,
        )
    }

    // ---- since last charge ----

    data class Session(
        val charging: Boolean,
        val since: Long,
        val fromPct: Int,
        val nowPct: Int,
        val lastSample: Long,
    ) {
        val elapsedMs: Long get() = (lastSample - since).coerceAtLeast(0)
        val deltaPct: Int get() = nowPct - fromPct
        val ratePctPerH: Double? get() {
            val h = elapsedMs / 3_600_000.0
            return if (h >= 0.25 && deltaPct != 0) abs(deltaPct) / h else null
        }
    }

    /** The current on-battery (or on-charger) stretch, walking back from the newest sample until the state flips or a gap. */
    fun currentSession(s: SampleSet): Session? {
        if (s.size < 2) return null
        val lastI = s.size - 1
        val charging = s.onCharger(lastI)
        var i = lastI
        while (i > 0) {
            if (s.t[i] - s.t[i - 1] > GAP_MS * 6) break
            if (s.onCharger(i - 1) != charging) break
            i--
        }
        return Session(charging, s.t[i], s.cap[i].toInt(), s.cap[lastI].toInt(), s.t[lastI])
    }

    /** Time ranges spent on the charger (for shading charts). */
    fun chargingBands(s: SampleSet): List<LongRange> {
        val out = ArrayList<LongRange>()
        var start = -1L
        for (i in 0 until s.size) {
            val on = s.onCharger(i)
            val gap = i > 0 && s.t[i] - s.t[i - 1] > GAP_MS
            if (start >= 0 && (!on || gap)) {
                out += start..s.t[i - 1]
                start = -1
            }
            if (on && start < 0) start = s.t[i]
        }
        if (start >= 0 && s.size > 0) out += start..s.t[s.size - 1]
        return out
    }

    // ---- measured capacity ----

    data class Measured(val fullMah: Int, val runs: Int)

    /**
     * Full capacity measured from the charge counter over on-battery stretches that dropped at least 15 %:
     * mAh used / % dropped × 100. Median of the stretches.
     */
    fun measuredCapacity(s: SampleSet): Measured? {
        val results = ArrayList<Double>()
        var a = -1
        fun close(b: Int) {
            if (a < 0 || b <= a) return
            val drop = s.cap[a] - s.cap[b]
            val q0 = s.chg[a]; val q1 = s.chg[b]
            if (drop >= 15 && q0 > 0 && q1 > 0 && q0 > q1) {
                val mah = (q0 - q1) / 1000.0 / drop * 100.0
                if (mah in 200.0..50_000.0) results += mah
            }
        }
        for (i in 0 until s.size) {
            val onB = !s.onCharger(i)
            val gap = i > 0 && s.t[i] - s.t[i - 1] > GAP_MS
            if (a >= 0 && (!onB || gap)) { close(i - 1); a = -1 }
            if (onB && a < 0) a = i
        }
        if (a >= 0) close(s.size - 1)
        if (results.isEmpty()) return null
        results.sort()
        return Measured(results[results.size / 2].toInt(), results.size)
    }

    // ---- run report ----

    enum class Severity { Info, Warning }
    data class Finding(val severity: Severity, val title: String, val detail: String)

    data class Report(
        val info: LogInfo,
        val durationMs: Long,
        val startCap: Int,
        val endCap: Int,
        val drained: Int,
        val charged: Int,
        val usedMah: Double?,
        val fullMah: Double?,
        val designMah: Int?,
        val kernelFullMah: Int?,
        val vStart: Int?,
        val vEnd: Int?,
        val vMin: Int?,
        val vMinAtCap: Int?,
        val vMinAt: Long?,
        val vMax: Int?,
        val avgDrainMa: Double?,
        val peakDrainMa: Double?,
        val peakDrainAtCap: Int?,
        val avgDrainW: Double?,
        val peakDrainW: Double?,
        val avgChargeW: Double?,
        val peakChargeW: Double?,
        val tMin: Float?,
        val tMax: Float?,
        val tAvg: Float?,
        val drainPctPerH: Double?,
        val chargePctPerH: Double?,
        val cells: Int,
        val curve: List<Pair<Int, Int>>,
        val findings: List<Finding>,
    ) {
        val sohPct: Double? get() = if (fullMah != null && designMah != null && designMah > 0) fullMah / designMah * 100 else null
    }

    /**
     * Report for one log. [next] is the log that started after this one (used to spot a shut-off:
     * the log ended on battery and the device booted again soon after).
     */
    fun report(info: LogInfo, s: SampleSet, next: LogInfo?): Report {
        val stats = LifetimeStats.read()
        var vMin = Int.MAX_VALUE; var vMinI = -1; var vMax = Int.MIN_VALUE
        var dSum = 0.0; var dN = 0; var dPeak = 0.0; var dPeakI = -1
        var wdSum = 0.0; var wdN = 0; var wdPeak = 0.0
        var wcSum = 0.0; var wcN = 0; var wcPeak = 0.0
        var tMin = Float.MAX_VALUE; var tMax = -Float.MAX_VALUE; var tSum = 0.0; var tN = 0
        for (i in 0 until s.size) {
            val v = s.mv[i]
            if (v > 0) {
                if (v < vMin) { vMin = v; vMinI = i }
                if (v > vMax) vMax = v
            }
            val ma = s.ua[i] / 1000.0
            val w = s.watts(i)
            if (!s.onCharger(i)) {
                if (ma < 0) { dSum += -ma; dN++; if (-ma > dPeak) { dPeak = -ma; dPeakI = i } }
                if (!w.isNaN() && w < 0) { wdSum += -w; wdN++; wdPeak = max(wdPeak, -w.toDouble()) }
            } else if (!w.isNaN() && w > 0) {
                wcSum += w; wcN++; wcPeak = max(wcPeak, w.toDouble())
            }
            val tc = s.tempC(i)
            if (!tc.isNaN()) { tMin = min(tMin, tc); tMax = max(tMax, tc); tSum += tc; tN++ }
        }

        // mAh used while on battery, from the charge counter, over stretches without gaps.
        var usedUah = 0L
        var usedPct = 0
        for (i in 1 until s.size) {
            if (s.t[i] - s.t[i - 1] > GAP_MS) continue
            if (s.onCharger(i) || s.onCharger(i - 1)) continue
            val q0 = s.chg[i - 1]; val q1 = s.chg[i]
            if (q0 > 0 && q1 > 0 && q0 > q1) usedUah += (q0 - q1)
            val d = s.cap[i - 1] - s.cap[i]
            if (d > 0) usedPct += d
        }
        val usedMah = if (usedUah > 0) usedUah / 1000.0 else null
        val fullMah = if (usedMah != null && usedPct >= 5) usedMah / usedPct * 100 else null

        val r = rates(s)
        val cells = LifetimeStats.cells(if (vMax == Int.MIN_VALUE) 0 else vMax)

        // Voltage at each 10 % step while on battery (first time the level was seen).
        val curve = ArrayList<Pair<Int, Int>>()
        for (bucket in 100 downTo 0 step 10) {
            for (i in 0 until s.size) {
                if (s.cap[i].toInt() == bucket && s.mv[i] > 0 && !s.onCharger(i)) { curve += bucket to s.mv[i]; break }
            }
        }

        val findings = ArrayList<Finding>()
        val lastI = s.size - 1
        if (lastI >= 0 && next != null && next.kind == LogKind.Boot && !s.onCharger(lastI)) {
            val gapMin = (next.start - s.t[lastI]) / 60_000
            val freshBoot = next.firstUptime in 0..(10 * 60_000L)
            val endCap = s.cap[lastI].toInt()
            if (freshBoot && gapMin in 0..20 && endCap > 3) {
                findings += Finding(
                    Severity.Warning, "إيقاف محتمل عند $endCap %",
                    "توقف السجل على البطارية عند $endCap % ثم عاد الجهاز للعمل بعد نحو $gapMin دقيقة. الإيقاف المفاجئ وإعادة التشغيل اليدوية يبدوان متشابهين هنا.",
                )
            }
        }
        if (vMinI >= 0) {
            val floor = 3_100 * cells
            val atCap = s.cap[vMinI].toInt()
            if (vMin < floor && atCap > 5) {
                findings += Finding(
                    Severity.Warning, "هبوط الجهد عند $atCap %",
                    "هبط الجهد إلى ${"%.2f".format(vMin / 1000.0)} V (${"%.2f".format(vMin / 1000.0 / cells)} V لكل خلية) بينما كان المستوى ما يزال $atCap %. انخفاض جهد الخلية تحت الحمل قد يسبب إيقافاً مفاجئاً.",
                )
            }
        }
        if (tN > 0 && tMax >= 48f) {
            findings += Finding(Severity.Warning, "بطارية ساخنة", "وصلت حرارة البطارية إلى ${"%.1f".format(tMax)} °C.")
        }
        val design = stats.designMah
        if (fullMah != null && design != null && fullMah < design * 0.7) {
            findings += Finding(
                Severity.Warning, "سعة مقاسة منخفضة",
                "قاست هذه الجلسة نحو ${fullMah.toInt()} mAh لشحنة كاملة، أي ${(fullMah / design * 100).toInt()} % من السعة التصميمية ${design} mAh.",
            )
        }

        return Report(
            info = info,
            durationMs = if (s.size > 1) s.last - s.first else 0,
            startCap = if (s.size > 0) s.cap[0].toInt() else -1,
            endCap = if (s.size > 0) s.cap[lastI].toInt() else -1,
            drained = info.drained,
            charged = info.charged,
            usedMah = usedMah,
            fullMah = fullMah,
            designMah = design,
            kernelFullMah = stats.fullMah,
            vStart = s.mv.firstOrNull { it > 0 },
            vEnd = if (s.size > 0) s.mv.take(s.size).lastOrNull { it > 0 } else null,
            vMin = if (vMinI >= 0) vMin else null,
            vMinAtCap = if (vMinI >= 0) s.cap[vMinI].toInt() else null,
            vMinAt = if (vMinI >= 0) s.t[vMinI] else null,
            vMax = if (vMax != Int.MIN_VALUE) vMax else null,
            avgDrainMa = if (dN > 0) dSum / dN else null,
            peakDrainMa = if (dN > 0) dPeak else null,
            peakDrainAtCap = if (dPeakI >= 0) s.cap[dPeakI].toInt() else null,
            avgDrainW = if (wdN > 0) wdSum / wdN else null,
            peakDrainW = if (wdN > 0) wdPeak else null,
            avgChargeW = if (wcN > 0) wcSum / wcN else null,
            peakChargeW = if (wcN > 0) wcPeak else null,
            tMin = if (tN > 0) tMin else null,
            tMax = if (tN > 0) tMax else null,
            tAvg = if (tN > 0) (tSum / tN).toFloat() else null,
            drainPctPerH = r.drainPctPerH,
            chargePctPerH = r.chargePctPerH,
            cells = cells,
            curve = curve,
            findings = findings,
        )
    }
}
