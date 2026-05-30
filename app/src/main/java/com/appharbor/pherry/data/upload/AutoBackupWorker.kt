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

        // Need a reachable desktop to send to; otherwise wait for the next period.
        if (!connectionManager.ensureConnectedToLast()) return Result.success()

        val since = appPreferences.lastAutoBackupAt.first()
        val now = System.currentTimeMillis()

        val completedIds = uploadRecordDao.getCompletedMediaStoreIds().toHashSet()
        val queuedIds = uploadRecordDao.getPendingAndUploading().mapTo(HashSet()) { it.mediaStoreId }

        val fresh = mediaRepository.loadAllMedia(MediaFilter.ALL).filter { item ->
            // MediaStore DATE_MODIFIED is in seconds.
            val modifiedMs = item.dateModified * 1000L
            modifiedMs >= since && item.id !in completedIds && item.id !in queuedIds
        }

        if (fresh.isNotEmpty()) {
            uploadManager.start(fresh)
        }
        appPreferences.setLastAutoBackupAt(now)
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "pherry_auto_backup"
    }
}
