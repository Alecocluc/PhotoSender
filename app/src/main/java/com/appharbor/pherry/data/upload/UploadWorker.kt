package com.appharbor.pherry.data.upload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.appharbor.pherry.MainActivity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Drains the pending upload queue while running as a foreground service, so the transfer keeps
 * going with the screen off / app backgrounded and the user sees live progress in a notification.
 *
 * The actual upload logic lives in [UploadManager] (the same singleton the UI observes), so this
 * worker is just the foreground host: it promotes itself, mirrors [UploadManager.transferState]
 * into the notification, and asks the manager to process the queue.
 */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val uploadManager: UploadManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        setForeground(createForegroundInfo(uploadManager.transferState.value))

        // Keep the WiFi radio at full performance for the whole transfer. The foreground service
        // already keeps CPU priority, but with the screen off the radio can drop into power-save
        // and throttle throughput; this pins it so screen-off speed matches screen-on.
        val wifiLock = acquireWifiLock()

        return try {
            coroutineScope {
                // Mirror transfer state into the notification (throttled) while the queue drains.
                val notifier = launch {
                    uploadManager.transferState
                        .sample(NOTIFICATION_INTERVAL_MS)
                        .collect { state ->
                            NotificationManagerCompat.from(appContext)
                                .notify(NOTIFICATION_ID, buildNotification(state))
                        }
                }
                try {
                    uploadManager.runQueue()
                } finally {
                    notifier.cancel()
                }
            }
            postSummaryNotification(uploadManager.transferState.value)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Transient failure (e.g. server briefly unreachable) — let WorkManager retry.
            Result.retry()
        } finally {
            if (wifiLock?.isHeld == true) wifiLock.release()
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock(): WifiManager.WifiLock? =
        (appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            // FULL_HIGH_PERF is deprecated but remains the throughput-oriented lock (no power
            // save); LOW_LATENCY targets latency, not bulk transfer.
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Pherry:transfer")
            ?.apply { acquire() }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        createForegroundInfo(uploadManager.transferState.value)

    private fun createForegroundInfo(state: TransferState): ForegroundInfo =
        ForegroundInfo(
            NOTIFICATION_ID,
            buildNotification(state),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

    @Suppress("MissingPermission")
    private fun buildNotification(state: TransferState): Notification {
        ensureChannel()

        val done = state.completedFiles + state.failedFiles
        val percent = (state.progressPercent * 100).toInt().coerceIn(0, 100)
        val title = "Transferring photos"
        val text = if (state.totalFiles > 0) {
            "$done/${state.totalFiles} files · $percent%"
        } else {
            "Preparing…"
        }

        val openIntent = androidx.core.app.PendingIntentCompat.getActivity(
            appContext,
            0,
            Intent(appContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            0,
            false,
        )

        val cancelIntent = WorkManager.getInstance(appContext).createCancelPendingIntent(id)

        return NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .setProgress(100, percent, state.totalFiles == 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** One-shot summary shown after a batch finishes, e.g. "142 files · 2.3 GB backed up". */
    @Suppress("MissingPermission")
    private fun postSummaryNotification(state: TransferState) {
        val backedUp = state.completedFiles
        if (backedUp <= 0 && state.failedFiles <= 0) return
        ensureChannel()

        val sent = (backedUp - state.skippedFiles).coerceAtLeast(0)
        val title = if (state.failedFiles > 0) "Backup finished with issues" else "Backup complete"
        val text = buildString {
            append("$sent file${if (sent == 1) "" else "s"} · ${formatBytes(state.transferredBytes)} backed up")
            if (state.skippedFiles > 0) append(" · ${state.skippedFiles} already on PC")
            if (state.failedFiles > 0) append(" · ${state.failedFiles} failed")
        }

        val openIntent = androidx.core.app.PendingIntentCompat.getActivity(
            appContext,
            1,
            Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            0,
            false,
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .build()

        NotificationManagerCompat.from(appContext).notify(SUMMARY_NOTIFICATION_ID, notification)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun ensureChannel() {
        val manager = appContext.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Photo transfers",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows progress while photos are being sent"
                    setShowBadge(false)
                }
            )
        }
    }

    companion object {
        const val WORK_NAME = "photo_upload_transfer"
        private const val CHANNEL_ID = "photo_transfer"
        private const val NOTIFICATION_ID = 1001
        private const val SUMMARY_NOTIFICATION_ID = 1002
        private const val NOTIFICATION_INTERVAL_MS = 1000L
    }
}
