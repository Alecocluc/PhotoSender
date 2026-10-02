package com.appharbor.pherry.data.upload

import android.content.Context
import com.appharbor.pherry.data.db.UploadRecordDao
import com.appharbor.pherry.data.media.MediaRepository
import com.appharbor.pherry.data.model.MediaFilter
import com.appharbor.pherry.data.network.ConnectionManager
import com.appharbor.pherry.data.preferences.AppPreferences
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import androidx.hilt.work.HiltWorker
import kotlinx.coroutines.flow.first

/**
 * Periodic background backup. When enabled (and the phone is on Wi-Fi, optionally charging),
 * WorkManager wakes us up; we reconnect to the last desktop and queue any photos/videos created
 * since the previous run. The actual transfer is handled by the foreground [UploadWorker], so this
 * worker just decides *what* to send and hands it off.
 */
@HiltWorker
class AutoBackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val appPreferences: AppPreferences,
    private val mediaRepository: MediaRepository,
    private val uploadRecordDao: UploadRecordDao,
    private val connectionManager: ConnectionManager,
    private val uploadManager: UploadManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!appPreferences.autoBackupEnabled.first()) return Result.success()
        if (appPreferences.userPaused.first()) return Result.success()

        // Need a reachable desktop to send to; otherwise wait for the next period.
        if (!connectionManager.ensureConnectedToLast()) return Result.success()

        val now = System.currentTimeMillis()
        // A timestamp checkpoint misses restored/imported originals with old modification dates.
        // Receipt fingerprints cover edited rows and the currently selected destination.
        val fresh = uploadManager.filterUnsent(mediaRepository.loadAllMedia(MediaFilter.ALL))

        // Await the enqueue/schedule so the checkpoint only advances once these records are
        // persisted and the worker is queued. start() would fire-and-forget on UploadManager's own
        // scope, letting us return success — and move the checkpoint past these items — before they
        // were durable, so a process kill at the wrong moment would skip them forever.
        if (fresh.isNotEmpty()) {
            uploadManager.enqueueAndSchedule(fresh)
        }
        appPreferences.setLastAutoBackupAt(now)
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "pherry_auto_backup"
    }
}
