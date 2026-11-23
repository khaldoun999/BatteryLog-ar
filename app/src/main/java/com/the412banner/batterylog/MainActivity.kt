package com.the412banner.batterylog

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.the412banner.batterylog.ui.AppRoot
import com.the412banner.batterylog.ui.theme.BatteryLogTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val pendingRoute = MutableStateFlow<String?>(null)

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }


    override fun attachBaseContext(base: Context) {
        val locale = java.util.Locale("ar")
        java.util.Locale.setDefault(locale)
        val config = android.content.res.Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        super.attachBaseContext(base.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = BatteryLogApp.get(this)
        pendingRoute.value = intent?.getStringExtra(Notifier.EXTRA_ROUTE)
        setContent {
            val settings by app.settings.flow.collectAsStateWithLifecycle()
            BatteryLogTheme(settings.theme, settings.dynamicColor) {
                AppRoot(pendingRoute) { pendingRoute.value = null }
            }
        }
        if (savedInstanceState == null) askNotificationsOnce()
        // Logs left in the old app-private folder are copied to /sdcard/BatteryLog once access is granted.
        lifecycleScope.launch(Dispatchers.IO) { runCatching { if (Storage.canUsePublic()) Storage.migrateLegacy(this@MainActivity) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(Notifier.EXTRA_ROUTE)?.let { pendingRoute.value = it }
    }

    private fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33 || Notifier.canPost(this)) return
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (prefs.getBoolean("askedNotifications", false)) return
        prefs.edit().putBoolean("askedNotifications", true).apply()
        notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
