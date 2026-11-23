package com.the412banner.batterylog.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ElectricalServices
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.the412banner.batterylog.SampleSet
import com.the412banner.batterylog.TempUnit
import com.the412banner.batterylog.ui.components.Series
import com.the412banner.batterylog.ui.theme.ChartColors
import kotlin.math.abs

/** What a chart shows. Power and current are signed: above zero = charging, below = drawing from the battery. */
enum class Metric(val label: String) {
    Level("البطارية"),
    Power("الطاقة"),
    Current("التيار"),
    Voltage("الجهد"),
    Temp("الحرارة");

    val icon: ImageVector
        get() = when (this) {
            Level -> Icons.Rounded.BatteryStd
            Power -> Icons.Rounded.Bolt
            Current -> Icons.Rounded.Speed
            Voltage -> Icons.Rounded.ElectricalServices
            Temp -> Icons.Rounded.Thermostat
        }

    fun color(c: ChartColors): Color = when (this) {
        Level -> c.level
        Power -> c.power
        Current -> c.current
        Voltage -> c.voltage
        Temp -> c.temp
    }

    /** Colour for the part below zero (power/current only). */
    fun negativeColor(c: ChartColors): Color? = when (this) {
        Power, Current -> c.drain
        else -> null
    }

    fun series(s: SampleSet, unit: TempUnit): Series = when (this) {
        Level -> Series.of(s) { s.cap[it].toFloat() }
        Power -> Series.of(s) { s.watts(it) }
        Current -> Series.of(s) { i -> if (s.ua[i] == 0) Float.NaN else s.ua[i] / 1000f }
        Voltage -> Series.of(s) { i -> if (s.mv[i] > 0) s.mv[i] / 1000f else Float.NaN }
        Temp -> Series.of(s) { i -> s.tempC(i).let { c -> if (unit == TempUnit.F && !c.isNaN()) c * 9f / 5f + 32f else c } }
    }

    fun axis(unit: TempUnit): (Float) -> String = when (this) {
        Level -> { v -> "${v.toInt()}%" }
        Power -> { v -> if (abs(v) >= 10f) "%.0fW".format(v) else "%.1fW".format(v) }
        Current -> { v -> if (abs(v) >= 1000f) "%.1fA".format(v / 1000f) else "%.0f".format(v) }
        Voltage -> { v -> "%.2f".format(v) }
        Temp -> { v -> "%.0f°".format(v) }
    }

    fun value(unit: TempUnit): (Float) -> String = when (this) {
        Level -> { v -> "${v.toInt()} %" }
        Power -> { v -> (if (v >= 0f) "+" else "−") + "%.2f W".format(abs(v)) }
        Current -> { v -> (if (v >= 0f) "+" else "−") + "%,.0f mA".format(abs(v)) }
        Voltage -> { v -> "%.3f V".format(v) }
        Temp -> { v -> if (unit == TempUnit.F) "%.1f °F".format(v) else "%.1f °C".format(v) }
    }

    val fixedMin: Float? get() = if (this == Level) 0f else null
    val fixedMax: Float? get() = if (this == Level) 100f else null

    val minRange: Float
        get() = when (this) {
            Level -> 100f
            Power -> 2f
            Current -> 400f
            Voltage -> 0.1f
            Temp -> 4f
        }
}
