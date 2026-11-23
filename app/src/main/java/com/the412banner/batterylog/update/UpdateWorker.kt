package com.the412banner.batterylog.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.the412banner.batterylog.BatteryLogApp
import com.the412banner.batterylog.Notifier
import java.util.concurrent.TimeUnit

/** Periodic check of this app's GitHub releases; notifies once per new version. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = BatteryLogApp.get(applicationContext)
        val settings = app.settings.current()
        if (!settings.autoUpdateCheck) return Result.success()
        val release = app.updates.check() ?: return Result.success()
        val tag = release.version.toString()
        val prefs = applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE)
        if (settings.skippedVersion == tag || prefs.getString("notified", null) == tag) return Result.success()
        prefs.edit().putString("notified", tag).apply()
        Notifier.show(
            applicationContext, Notifier.ID_UPDATE, Notifier.CH_UPDATES,
            "يتوفر إصدار جديد من سجل البطارية ${release.tag}",
            "انقر لمعرفة الجديد وتثبيته.",
            route = "updates",
        )
        return Result.success()
    }
}

object UpdateScheduler {
    private const val NAME = "update-check"

    fun schedule(context: Context, enabled: Boolean) {
        val work = WorkManager.getInstance(context)
        if (!enabled) {
            work.cancelUniqueWork(NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(10, TimeUnit.MINUTES)
            .build()
        work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
