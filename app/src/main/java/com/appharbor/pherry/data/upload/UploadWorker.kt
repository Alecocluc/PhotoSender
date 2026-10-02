package com.appharbor.pherry.data.upload

import android.content.Context
import android.content.pm.ServiceInfo
import android.app.NotificationManager
import android.net.wifi.WifiManager
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.*

/** WorkManager remains the deferrable/background path and Android 11-13 fallback. */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val uploadManager: UploadManager,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        var wifiLock: WifiManager.WifiLock? = null
        return try {
            setForeground(getForegroundInfo())
            @Suppress("DEPRECATION")
            wifiLock = (appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Pherry:transfer")?.apply { acquire() }
            val outcome = coroutineScope {
                val notifier = launch {
                    while (isActive) {
                        delay(1000)
                        runCatching { appContext.getSystemService(NotificationManager::class.java).notify(TransferNotifications.ID,
                            TransferNotifications.build(appContext, uploadManager.transferState.value)) }
                    }
                }
                try { uploadManager.runQueue() } finally { notifier.cancel() }
            }
            if (outcome == QueueOutcome.COMPLETE || outcome == QueueOutcome.FAILED) {
                runCatching { appContext.getSystemService(NotificationManager::class.java).notify(1002,
                    TransferNotifications.build(appContext, uploadManager.transferState.value, false)) }
            }
            when (outcome) { QueueOutcome.RETRY -> Result.retry(); else -> Result.success() }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
        finally { if (wifiLock?.isHeld == true) wifiLock.release() }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(TransferNotifications.ID,
        TransferNotifications.build(appContext, uploadManager.transferState.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    companion object { const val WORK_NAME = "photo_upload_transfer" }
}
