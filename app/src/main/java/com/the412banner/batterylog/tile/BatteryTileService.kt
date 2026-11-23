package com.the412banner.batterylog.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.the412banner.batterylog.BatteryInfo
import com.the412banner.batterylog.BatteryLogService
import com.the412banner.batterylog.Fmt
import com.the412banner.batterylog.MainActivity
import com.the412banner.batterylog.R

/** Quick settings tile: level + live watts; active while logging. Tap opens the app. */
class BatteryTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val b = BatteryInfo.read(this)
        val s = BatteryLogService.state.value
        tile.label = if (b.capacityPct >= 0) "${b.capacityPct} %" else "سجل البطارية"
        val sub = buildList {
            if (!b.watts.isNaN()) add(Fmt.watts(b.watts))
            add(if (b.onCharger) "يُشحن" else "على البطارية")
        }.joinToString(" · ")
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = sub
        tile.state = if (s.recording || s.auto) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_battery)
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
