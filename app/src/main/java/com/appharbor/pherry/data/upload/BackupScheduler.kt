package com.appharbor.pherry.data.upload

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.appharbor.pherry.data.preferences.AppPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Schedules / cancels the periodic [AutoBackupWorker] based on the user's auto-backup settings. */
@Singleton
class BackupScheduler @Inject constructor(
    @ApplicationContext context: Context,
    private val appPreferences: AppPreferences,
) {
    private val workManager = WorkManager.getInstance(context)

    /** Re-apply scheduling from the persisted prefs (e.g. on app launch). */
    suspend fun sync() {
        if (appPreferences.autoBackupEnabled.first()) {
            schedule(appPreferences.autoBackupRequiresCharging.first())
        } else {
            cancel()
        }
    }

    fun schedule(requiresCharging: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED) // Wi-Fi / unmetered only
            .setRequiresCharging(requiresCharging)
            .build()
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(
            AutoBackupWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel() {
        workManager.cancelUniqueWork(AutoBackupWorker.WORK_NAME)
    }
}
