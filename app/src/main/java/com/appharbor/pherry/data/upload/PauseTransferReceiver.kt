package com.appharbor.pherry.data.upload

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Notification pause saves intent before its receiver lifecycle ends. */
@AndroidEntryPoint
class PauseTransferReceiver : BroadcastReceiver() {
    @Inject lateinit var uploadManager: UploadManager
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { uploadManager.pauseTransferAndWait() } finally { pending.finish() }
        }
    }
}
