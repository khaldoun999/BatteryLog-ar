package com.the412banner.batterylog

import android.app.Application
import android.content.Context
import com.the412banner.batterylog.update.UpdateRepository
import com.the412banner.batterylog.update.UpdateScheduler
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Process-wide singletons (settings, logs, updater). */
class BatteryLogApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var logs: LogRepository
        private set
    lateinit var updates: UpdateRepository
        private set


    override fun attachBaseContext(base: Context) {
        val locale = java.util.Locale("ar")
        java.util.Locale.setDefault(locale)
        val config = android.content.res.Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        super.attachBaseContext(base.createConfigurationContext(config))
    }

    override fun onCreate() {
        super.onCreate()
        setup(this)
        Notifier.createChannels(this)
        com.the412banner.batterylog.apps.ShizukuAccess.init()
        com.the412banner.batterylog.hud.HudStore.init(this)
        updates.clearDownloads()
        runCatching { UpdateScheduler.schedule(this, settings.current().autoUpdateCheck) }
        // Per-app monitoring follows its setting (switching off is handled by the service loop).
        kotlinx.coroutines.MainScope().launch {
            settings.flow.map { it.perApp }.distinctUntilChanged().collect { on ->
                if (on) com.the412banner.batterylog.apps.AppMonitorService.start(this@BatteryLogApp)
            }
        }
    }

    private fun setup(ctx: Context) {
        settings = SettingsStore(ctx)
        logs = LogRepository(ctx)
        updates = UpdateRepository(ctx, settings)
    }

    companion object {
        @Volatile private var fallback: BatteryLogApp? = null

        /**
         * The app object. Under tests (plain Application) a stand-in is built once so screens and
         * repositories still work.
         */
        fun get(ctx: Context): BatteryLogApp {
            val app = ctx.applicationContext
            if (app is BatteryLogApp) return app
            return fallback ?: synchronized(this) {
                fallback ?: BatteryLogApp().also { it.setup(app); fallback = it }
            }
        }
    }
}
