package com.the412banner.batterylog

import android.os.Build

/** Plain-text version of [Analysis.Report] for sharing (also saved next to the CSV as *_report.txt). */
object ReportText {
    fun render(r: Analysis.Report, unit: TempUnit): String = buildString {
        fun line(s: String = "") = append(s).append('\n')
        fun row(k: String, v: String) = line(k.padEnd(24) + v)
        line("تقرير سجل البطارية")
        line("=================")
        row("الجهاز", "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
        row("السجل", r.info.name)
        row("من", Fmt.dateTime(r.info.start))
        row("إلى", Fmt.dateTime(r.info.end))
        row("المدة", Fmt.duration(r.durationMs))
        row("العينات", "${r.info.count}")
        row("المستوى", "${r.startCap} % -> ${r.endCap} %  (استُخدم ${r.drained} %، وشُحن ${r.charged} %)")
        line()
        line("السعة")
        row("  استُخدم على البطارية", r.usedMah?.let { "%.0f mAh".format(it) } ?: "-")
        row("  السعة الكاملة المقاسة", r.fullMah?.let { "%.0f mAh".format(it) } ?: "-")
        row("  التصميمية", r.designMah?.let { "$it mAh" } ?: "غير معروف")
        row("  مقياس الوقود الكامل", r.kernelFullMah?.let { "$it mAh" } ?: "غير معروف")
        r.sohPct?.let { row("  الصحة (هذه الجلسة)", "%.1f %%".format(it)) }
        line()
        line("الجهد (${r.cells}S)")
        row("  البداية -> النهاية", "${r.vStart?.let { Fmt.volts(it) } ?: "-"} -> ${r.vEnd?.let { Fmt.volts(it) } ?: "-"}")
        row("  الأدنى", r.vMin?.let { "${Fmt.volts(it)} عند ${r.vMinAtCap} % (${r.vMinAt?.let(Fmt::time) ?: ""})" } ?: "-")
        row("  الأعلى", r.vMax?.let { Fmt.volts(it) } ?: "-")
        if (r.curve.isNotEmpty()) {
            line("  لكل 10 %:")
            r.curve.forEach { (p, mv) -> line("    %3d %% : %s".format(p, Fmt.volts(mv))) }
        }
        line()
        line("الطاقة / التيار")
        row("  متوسط السحب", r.avgDrainW?.let { Fmt.watts(it) } ?: "-")
        row("  ذروة السحب", r.peakDrainW?.let { Fmt.watts(it) } ?: "-")
        row("  متوسط التيار", r.avgDrainMa?.let { Fmt.ma(it) } ?: "-")
        row("  ذروة التيار", r.peakDrainMa?.let { "${Fmt.ma(it)} عند ${r.peakDrainAtCap} %" } ?: "-")
        r.avgChargeW?.let { row("  متوسط الشحن", Fmt.watts(it)) }
        r.peakChargeW?.let { row("  ذروة الشحن", Fmt.watts(it)) }
        line()
        line("السرعة")
        row("  التفريغ", r.drainPctPerH?.let { "%.1f %%/h".format(it) } ?: "-")
        r.drainPctPerH?.takeIf { it > 0 }?.let { row("  الشحنة الكاملة تدوم", Fmt.duration((100 / it * 3_600_000).toLong())) }
        row("  الشحن", r.chargePctPerH?.let { "%.1f %%/h".format(it) } ?: "-")
        line()
        line("الحرارة")
        row("  الأدنى / المتوسط / الأعلى", listOf(r.tMin, r.tAvg, r.tMax).joinToString(" / ") { t -> t?.let { Fmt.temp(it.toDouble(), unit) } ?: "-" })
        line()
        line("الملاحظات")
        if (r.findings.isEmpty()) line("  لا شيء غير معتاد.")
        r.findings.forEach { f -> line("  [!] ${f.title}: ${f.detail}") }
        line()
        line("ملف CSV الخام: ${r.info.path}")
    }
}
