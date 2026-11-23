package com.the412banner.batterylog.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.the412banner.batterylog.AppSettings
import com.the412banner.batterylog.BatteryLogApp
import com.the412banner.batterylog.BuildConfig
import com.the412banner.batterylog.Fmt
import com.the412banner.batterylog.update.AppRelease
import com.the412banner.batterylog.update.UpdateInstaller
import com.the412banner.batterylog.update.UpdateState
import com.the412banner.batterylog.ui.components.InfoRow
import com.the412banner.batterylog.ui.components.MarkdownLite
import com.the412banner.batterylog.ui.components.Pill
import com.the412banner.batterylog.ui.components.SectionCard
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

@Composable
fun UpdatesScreen(back: () -> Unit) {
    val context = LocalContext.current
    val app = BatteryLogApp.get(context)
    val state by app.updates.state.collectAsStateWithLifecycle()
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        if (state is UpdateState.Idle || state is UpdateState.UpToDate || state is UpdateState.Failed) app.updates.check()
    }
    // Re-checked on every resume: the "install unknown apps" switch is flipped in Android's settings.
    var canInstall by remember { mutableStateOf(UpdateInstaller.canInstall(context)) }
    LifecycleResumeEffect(Unit) {
        canInstall = UpdateInstaller.canInstall(context)
        onPauseOrDispose { }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("التحديثات") },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "رجوع") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())) {
            UpdatesBody(
                state = state,
                settings = settings,
                canInstall = canInstall,
                onCheck = { scope.launch { app.updates.check() } },
                onDownload = { r -> scope.launch { app.updates.download(r) } },
                onInstall = { r, f ->
                    scope.launch {
                        app.updates.markInstalling(r)
                        runCatching { UpdateInstaller.install(context, f) }.onFailure { app.updates.installFinished(false, it.message) }
                    }
                },
                onSkip = { r ->
                    app.settings.update { it.copy(skippedVersion = r.version.toString()) }
                    app.updates.reset()
                },
                onAllowInstall = { runCatching { context.startActivity(UpdateInstaller.permissionIntent(context)) } },
                onOpenPage = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                onPrereleases = { on ->
                    app.settings.update { it.copy(includePrereleases = on) }
                    scope.launch { app.updates.check() }
                },
                samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true),
            )
        }
    }
}

@Composable
fun UpdatesBody(
    state: UpdateState,
    settings: AppSettings,
    canInstall: Boolean,
    onCheck: () -> Unit,
    onDownload: (AppRelease) -> Unit,
    onInstall: (AppRelease, File) -> Unit,
    onSkip: (AppRelease) -> Unit,
    onAllowInstall: () -> Unit,
    onOpenPage: (String) -> Unit,
    onPrereleases: (Boolean) -> Unit,
    samsung: Boolean = false,
) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("هذا الإصدار", Icons.Rounded.SystemUpdate) {
            InfoRow("المثبت", BuildConfig.VERSION_NAME)
            InfoRow("البناء", BuildConfig.GIT_SHA)
            InfoRow("آخر تحقق", if (settings.lastUpdateCheck > 0) Fmt.dateTime(settings.lastUpdateCheck) else "أبداً")
            InfoRow("القناة", if (settings.includePrereleases) "الإصدارات والنسخ التجريبية" else "الإصدارات فقط")
            TextButton(onClick = { onPrereleases(!settings.includePrereleases) }) {
                Text(if (settings.includePrereleases) "عرض الإصدارات الكاملة فقط" else "عرض النسخ التجريبية أيضاً")
            }
        }
        val release = when (state) {
            is UpdateState.Available -> state.release
            is UpdateState.Downloading -> state.release
            is UpdateState.Verifying -> state.release
            is UpdateState.ReadyToInstall -> state.release
            is UpdateState.Installing -> state.release
            is UpdateState.Failed -> state.release
            else -> null
        }
        when (state) {
            UpdateState.Idle, UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("  جارٍ التحقق من التحديثات في GitHub…")
            }
            is UpdateState.UpToDate -> SectionCard("أنت على أحدث إصدار", Icons.Rounded.CheckCircle) {
                Text(
                    state.latest?.let { "الأحدث ${if (it.prerelease) "نسخة تجريبية" else "إصدار"}: ${it.tag}" } ?: "لم يُعثر على إصدارات.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onCheck) { Text("التحقق مجدداً") }
            }
            is UpdateState.Failed -> SectionCard("مشكلة في التحديث", Icons.Rounded.ErrorOutline, tint = MaterialTheme.colorScheme.error) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                if (samsung && state.atInstall) AutoBlockerTip()
                TextButton(onClick = onCheck) { Text("حاول مجدداً") }
            }
            else -> {}
        }
        if (release != null) {
            SectionCard(release.title, Icons.Rounded.NewReleases, trailing = { if (release.prerelease) Pill("نسخة تجريبية") }) {
                Text(
                    listOfNotNull(
                        release.tag,
                        release.publishedAt?.let { runCatching { Fmt.dateTime(Instant.parse(it).toEpochMilli()) }.getOrNull() },
                        "%.1f MB".format(release.apk.size / 1_048_576.0),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                if (release.notes.isNotBlank()) MarkdownLite(release.notes.take(6_000))
                Spacer(Modifier.height(12.dp))
                when (state) {
                    is UpdateState.Downloading -> {
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                        Text("جارٍ التنزيل ${(state.progress * 100).toInt()} %", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    }
                    is UpdateState.Verifying -> Text("جارٍ التحقق من التنزيل…", style = MaterialTheme.typography.bodySmall)
                    is UpdateState.Installing -> Text("بانتظار مثبت أندرويد…", style = MaterialTheme.typography.bodySmall)
                    is UpdateState.ReadyToInstall -> {
                        if (!canInstall) {
                            Text("اسمح أولاً لسجل البطارية بتثبيت التحديثات.", style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = onAllowInstall) { Text("سماح") }
                            if (samsung) AutoBlockerTip()
                        }
                        Button(onClick = { onInstall(state.release, state.file) }, enabled = canInstall, modifier = Modifier.fillMaxWidth()) { Text("تثبيت") }
                    }
                    else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { onDownload(release) }) { Text("تنزيل وتثبيت") }
                        OutlinedButton(onClick = { onSkip(release) }) { Text("تخطي") }
                    }
                }
                release.pageUrl?.let { url -> TextButton(onClick = { onOpenPage(url) }) { Text("العرض في GitHub") } }
            }
        }
    }
}

/** Samsung's Auto Blocker (on by default since One UI 6.1.1) blocks installs from outside the Play Store. */
@Composable
private fun AutoBlockerTip() {
    Text(
        "أجهزة Samsung: إذا منع أندرويد التثبيت، أوقف الحظر التلقائي من الإعدادات ← الأمان والخصوصية ← الحظر التلقائي.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}
