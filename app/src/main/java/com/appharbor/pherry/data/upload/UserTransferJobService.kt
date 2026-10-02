package com.appharbor.pherry.data.upload

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.net.wifi.WifiManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

/** Manual long transfers use Android's user-initiated transfer quota on API 34+. */
@AndroidEntryPoint
class UserTransferJobService : JobService() {
    @Inject lateinit var uploadManager: UploadManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        setNotification(params, TransferNotifications.ID, TransferNotifications.build(this, uploadManager.transferState.value), JOB_END_NOTIFICATION_POLICY_REMOVE)
        running = scope.launch {
            @Suppress("DEPRECATION")
            val wifiLock = runCatching {
                (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                    ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Pherry:user-transfer")?.apply { acquire() }
            }.getOrNull()
            val notifier = launch {
                while (isActive) {
                    delay(1000)
                    runCatching { getSystemService(NotificationManager::class.java).notify(TransferNotifications.ID,
                        TransferNotifications.build(this@UserTransferJobService, uploadManager.transferState.value)) }
                }
            }
            try {
                val outcome = uploadManager.runQueue()
                if (outcome == QueueOutcome.COMPLETE || outcome == QueueOutcome.FAILED) runCatching {
                    getSystemService(NotificationManager::class.java).notify(1002,
                        TransferNotifications.build(this@UserTransferJobService, uploadManager.transferState.value, false))
                }
                jobFinished(params, outcome == QueueOutcome.RETRY)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { jobFinished(params, true) }
            finally { notifier.cancel(); if (wifiLock?.isHeld == true) wifiLock.release() }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        if (Build.VERSION.SDK_INT >= 31 && params.stopReason == JobParameters.STOP_REASON_USER) {
            uploadManager.cancelTransfer()
            return false
        }
        return true
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        private const val JOB_ID = 73210
        fun schedule(context: Context, wifiOnly: Boolean, estimatedBytes: Long = 0): Boolean {
            if (Build.VERSION.SDK_INT < 34) return false
            return runCatching {
                val builder = JobInfo.Builder(JOB_ID, ComponentName(context, UserTransferJobService::class.java))
                    .setUserInitiated(true)
                    .setRequiredNetworkType(if (wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
                    .setBackoffCriteria(15_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                if (estimatedBytes > 0) builder.setEstimatedNetworkBytes(0, estimatedBytes)
                    .setMinimumNetworkChunkBytes(minOf(4L * 1024 * 1024, estimatedBytes))
                context.getSystemService(JobScheduler::class.java).schedule(builder.build()) == JobScheduler.RESULT_SUCCESS
            }.getOrDefault(false)
        }
        fun cancel(context: Context) { context.getSystemService(JobScheduler::class.java).cancel(JOB_ID) }
    }
}

