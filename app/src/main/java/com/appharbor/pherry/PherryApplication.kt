package com.appharbor.pherry

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.appharbor.pherry.data.share.SharedMediaImporter
import com.appharbor.pherry.data.upload.BackupScheduler
import com.appharbor.pherry.data.upload.UploadManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class PherryApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var uploadManager: UploadManager

    @Inject lateinit var backupScheduler: BackupScheduler

    @Inject lateinit var sharedMediaImporter: SharedMediaImporter

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setJobSchedulerJobIdRange(1_000, 70_000)
            .build()

    override fun onCreate() {
        super.onCreate()
        // If a transfer was interrupted (process death, reboot, WiFi loss), pick it back up.
        uploadManager.resumeIfPending()
        // Re-apply the periodic auto-backup schedule from saved settings.
        appScope.launch { backupScheduler.sync() }
        // Drop stale copies of media shared in from other apps so the cache can't grow forever.
        appScope.launch { sharedMediaImporter.pruneCache() }
    }
}
