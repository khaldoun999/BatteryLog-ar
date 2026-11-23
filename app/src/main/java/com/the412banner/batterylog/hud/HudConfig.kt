package com.the412banner.batterylog.hud

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the HUD can show. */
@Serializable
enum class HudMetric(val label: String) {
    Fps("FPS"), Cpu("CPU %"), Gpu("GPU %"), GpuMhz("GPU MHz"),
    CpuTemp("CPU °"), GpuTemp("GPU °"), CaseTemp("الهيكل °"), BattTemp("البطارية °"),
    BattPct("البطارية %"), Watts("الواط"), Clock("الساعة"),
}

/** HUD look + which apps show it (and with which metrics). */
@Serializable
data class HudConfig(
    val enabled: Boolean = false,
    /** Older settings: 0 top left, 1 top right, 2 bottom left, 3 bottom right. Used while [pos] is -1. */
    val corner: Int = 0,
    /** 3×3 grid, row by row: 0 top left, 1 top center, 2 top right … 4 center … 8 bottom right. */
    val pos: Int = -1,
    /** Distance from that edge in px, or from the middle on a centered axis (changed by dragging). */
    val x: Int = 24,
    val y: Int = 24,
    /** 0 small, 1 medium, 2 large. */
    val size: Int = 1,
    /** Background darkness in %. */
    val opacity: Int = 60,
    /** true = horizontal (one line), false = vertical (one item per row). */
    val oneLine: Boolean = true,
    /** Locked = touches go through to the game; unlocked = the HUD can be dragged. */
    val locked: Boolean = false,
    val apps: Map<String, Set<HudMetric>> = emptyMap(),
) {
    val cell: Int get() = if (pos in 0..8) pos else when (corner) { 1 -> 2; 2 -> 6; 3 -> 8; else -> 0 }
    /** 0 top, 1 middle, 2 bottom. */
    val row: Int get() = cell / 3
    /** 0 left, 1 center, 2 right. */
    val col: Int get() = cell % 3

    /** Moves the HUD to [row] / [col] and resets the drag offset. */
    fun at(row: Int, col: Int): HudConfig =
        copy(pos = row.coerceIn(0, 2) * 3 + col.coerceIn(0, 2), x = if (col == 1) 0 else 24, y = if (row == 1) 0 else 24)

    companion object {
        val DEFAULT_METRICS: Set<HudMetric> = setOf(
            HudMetric.Fps, HudMetric.Cpu, HudMetric.Gpu, HudMetric.CpuTemp,
            HudMetric.CaseTemp, HudMetric.BattTemp, HudMetric.Watts,
        )
    }
}

object HudStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var prefs: SharedPreferences? = null
    private val _flow = MutableStateFlow(HudConfig())
    val flow: StateFlow<HudConfig> = _flow.asStateFlow()

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("hud", Context.MODE_PRIVATE)
        prefs = p
        _flow.value = p.getString("config", null)?.let { runCatching { json.decodeFromString<HudConfig>(it) }.getOrNull() } ?: HudConfig()
    }

    fun current(): HudConfig = _flow.value

    @Synchronized
    fun update(f: (HudConfig) -> HudConfig) {
        val n = f(_flow.value)
        _flow.value = n
        prefs?.edit()?.putString("config", json.encodeToString(HudConfig.serializer(), n))?.apply()
    }
}
