package com.the412banner.batterylog.ui.hud

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.the412banner.batterylog.hud.HudConfig
import com.the412banner.batterylog.hud.HudMetric
import com.the412banner.batterylog.hud.HudStore
import com.the412banner.batterylog.ui.apps.appIcon
import com.the412banner.batterylog.ui.apps.appLabel
import com.the412banner.batterylog.ui.components.Pill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Metric chips for one app's HUD. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HudMetricChips(pkg: String) {
    val hud by HudStore.flow.collectAsStateWithLifecycle()
    val metrics = hud.apps[pkg] ?: return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        HudMetric.entries.forEach { m ->
            FilterChip(
                selected = m in metrics,
                onClick = {
                    HudStore.update { c ->
                        val cur = c.apps[pkg] ?: return@update c
                        c.copy(apps = c.apps + (pkg to if (m in cur) cur - m else cur + m))
                    }
                },
                label = { Text(m.label) },
            )
        }
    }
}

fun addHudApp(pkg: String) = HudStore.update { c ->
    if (pkg in c.apps) c else c.copy(apps = c.apps + (pkg to HudConfig.DEFAULT_METRICS), enabled = true)
}

fun removeHudApp(pkg: String) = HudStore.update { c -> c.copy(apps = c.apps - pkg) }

/** The apps that show the HUD (tap to edit metrics, ✕ to remove) + "Add a game". */
@Composable
fun HudAppsSection() {
    val ctx = LocalContext.current
    val hud by HudStore.flow.collectAsStateWithLifecycle()
    var picking by rememberSaveable { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val pkgs = remember(hud.apps.keys) { hud.apps.keys.sortedBy { appLabel(ctx, it).lowercase() } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        pkgs.forEach { pkg ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { open = if (open == pkg) null else pkg }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icon = remember(pkg) { appIcon(ctx, pkg) }
                if (icon != null) Image(icon, null, Modifier.size(32.dp).clip(RoundedCornerShape(8.dp))) else Spacer(Modifier.size(32.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(appLabel(ctx, pkg), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    Text("${hud.apps[pkg]?.size ?: 0} مقياس", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (open == pkg) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                IconButton(onClick = { removeHudApp(pkg); if (open == pkg) open = null }) { Icon(Icons.Rounded.Close, "إزالة") }
            }
            if (open == pkg) HudMetricChips(pkg)
        }
        FilledTonalButton(onClick = { picking = true }) {
            Icon(Icons.Rounded.Add, null)
            Spacer(Modifier.width(6.dp))
            Text("إضافة لعبة")
        }
    }
    if (picking) {
        AppPickerDialog(exclude = hud.apps.keys, onPick = { addHudApp(it); open = it; picking = false }, onDismiss = { picking = false })
    }
}

private class Launchable(val pkg: String, val label: String, val game: Boolean)

/** Installed apps with a launcher icon; games first. */
@Composable
private fun AppPickerDialog(exclude: Set<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<Launchable>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            runCatching {
                val pm = ctx.packageManager
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .map { it.activityInfo.applicationInfo }
                    .distinctBy { it.packageName }
                    .filter { it.packageName != ctx.packageName }
                    .map { ai ->
                        @Suppress("DEPRECATION")
                        val game = ai.category == ApplicationInfo.CATEGORY_GAME || (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0
                        Launchable(ai.packageName, pm.getApplicationLabel(ai).toString(), game)
                    }
                    .sortedWith(compareBy<Launchable>({ !it.game }, { it.label.lowercase() }))
            }.getOrDefault(emptyList())
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
        title = { Text("إضافة لعبة") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("بحث") }, modifier = Modifier.fillMaxWidth())
                val list = apps
                if (list == null) {
                    CircularProgressIndicator(Modifier.padding(24.dp))
                } else {
                    val shown = list.filter { it.pkg !in exclude && (query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true)) }
                    LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = 8.dp)) {
                        items(shown, key = { it.pkg }) { a ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(a.pkg) }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val icon = remember(a.pkg) { appIcon(ctx, a.pkg) }
                                if (icon != null) Image(icon, null, Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))) else Spacer(Modifier.size(36.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(a.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                    Text(a.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                                if (a.game) Pill("لعبة")
                            }
                        }
                    }
                }
            }
        },
    )
}
