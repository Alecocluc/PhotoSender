package com.appharbor.pherry.data.upload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.appharbor.pherry.MainActivity
import com.appharbor.pherry.R

object TransferNotifications {
    const val ID = 1001
    private const val CHANNEL = "photo_transfer"
    fun build(context: Context, state: TransferState, ongoing: Boolean = true, stopIntent: PendingIntent? = null): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Photo transfers", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = when (state.phase) {
            TransferPhase.PAUSED -> "Backup paused"
            TransferPhase.WAITING_FOR_COMPUTER -> "Waiting for your computer"
            TransferPhase.WAITING_FOR_NETWORK -> "Waiting for the network"
            TransferPhase.COMPLETE -> "Backup complete"
            TransferPhase.FAILED -> "Backup needs attention"
            TransferPhase.PREPARING -> "Preparing your backup"
            else -> "Sending originals to your computer"
        }
        val text = "${state.completedFiles}/${state.totalFiles} saved" +
            (if (state.skippedFiles > 0) " · ${state.skippedFiles} already there" else "") +
            (if (state.failedFiles > 0) " · ${state.failedFiles} failed" else "") +
            (if (state.pendingFiles > 0 && !ongoing) " · ${state.pendingFiles} still queued" else "")
        return NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_pherry)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(ongoing).setAutoCancel(!ongoing)
            .apply {
                if (ongoing) setProgress(100, (state.progressPercent * 100).toInt().coerceIn(0, 100), state.phase == TransferPhase.PREPARING)
                if (ongoing) addAction(android.R.drawable.ic_media_pause, "Pause", stopIntent ?: PendingIntent.getBroadcast(context, 0,
                    Intent(context, PauseTransferReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            }.build()
    }
}

