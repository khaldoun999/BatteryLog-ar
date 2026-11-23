package com.the412banner.batterylog.hud

import com.the412banner.batterylog.TempUnit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One HUD item: short label, value, and 0 normal / 1 warm / 2 hot (for colour). */
data class HudPart(val label: String, val value: String, val level: Int = 0)

/** Values for one HUD refresh (NaN = unknown). */
data class HudValues(
    val fps: Float, val cpuPct: Float, val gpuPct: Float, val gpuMhz: Float,
    val cpuC: Float, val gpuC: Float, val caseC: Float, val battC: Float,
    val battPct: Int, val watts: Double, val charging: Boolean, val time: Long,
)

/** Pure text building for the HUD (unit-tested). */
object HudText {
    fun parts(v: HudValues, metrics: Set<HudMetric>, unit: TempUnit): List<HudPart> {
        val out = ArrayList<HudPart>()
        for (m in HudMetric.entries) {
            if (m !in metrics) continue
            out += when (m) {
                HudMetric.Fps -> HudPart("FPS", num(v.fps, 0))
                HudMetric.Cpu -> HudPart("CPU", pct(v.cpuPct))
                HudMetric.Gpu -> HudPart("GPU", pct(v.gpuPct))
                HudMetric.GpuMhz -> HudPart("GPU", if (v.gpuMhz.isNaN()) "—\u00A0MHz" else "%.0f\u00A0MHz".format(v.gpuMhz))
                HudMetric.CpuTemp -> HudPart("CPU", temp(v.cpuC, unit), level(v.cpuC, 85f, 100f))
                HudMetric.GpuTemp -> HudPart("GPU", temp(v.gpuC, unit), level(v.gpuC, 85f, 100f))
                HudMetric.CaseTemp -> HudPart("الهيكل", temp(v.caseC, unit), level(v.caseC, 45f, 55f))
                HudMetric.BattTemp -> HudPart("البطارية", temp(v.battC, unit), level(v.battC, 40f, 45f))
                HudMetric.BattPct -> HudPart("البطارية", if (v.battPct in 0..100) "${v.battPct}%" else "—", if (v.battPct in 0..10) 2 else if (v.battPct in 11..20) 1 else 0)
                HudMetric.Watts -> HudPart("", if (v.charging) "يُشحن" else if (v.watts.isNaN()) "—\u00A0W" else "%.1f\u00A0W".format(kotlin.math.abs(v.watts)))
                HudMetric.Clock -> HudPart("", SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(v.time)))
            }
        }
        return out
    }

    /** Plain text, items separated by "  " (one line) or newlines. */
    fun plain(parts: List<HudPart>, oneLine: Boolean): String =
        parts.joinToString(if (oneLine) "  " else "\n") { if (it.label.isEmpty()) it.value else "${it.label}\u00A0${it.value}" }

    private fun num(f: Float, dec: Int) = if (f.isNaN()) "—" else "%.${dec}f".format(f)
    private fun pct(f: Float) = if (f.isNaN()) "—" else "%.0f%%".format(f)
    private fun temp(c: Float, unit: TempUnit): String {
        if (c.isNaN()) return "—"
        return if (unit == TempUnit.F) "%.0f°F".format(c * 9f / 5f + 32f) else "%.0f°C".format(c)
    }
    private fun level(c: Float, warm: Float, hot: Float) = if (c.isNaN()) 0 else if (c >= hot) 2 else if (c >= warm) 1 else 0
}
