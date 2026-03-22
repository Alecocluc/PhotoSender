package com.appharbor.photosender.ui.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appharbor.photosender.data.db.UploadRecord
import com.appharbor.photosender.data.db.UploadRecordDao
import com.appharbor.photosender.data.upload.TransferState
import com.appharbor.photosender.data.upload.UploadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class TransferViewModel @Inject constructor(
    private val uploadManager: UploadManager,
    private val uploadRecordDao: UploadRecordDao,
) : ViewModel() {

    val transferState: StateFlow<TransferState> = uploadManager.transferState

    val recentBatch: StateFlow<List<UploadRecord>> = uploadRecordDao.getRecentCompleted(10)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun cancelTransfer() {
        uploadManager.cancelTransfer()
    }

    fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    fun formatSpeed(bytesPerSec: Long): String {
        return when {
            bytesPerSec >= 1_048_576 -> "%.1f MB/s".format(bytesPerSec / 1_048_576.0)
            bytesPerSec >= 1024 -> "%.1f KB/s".format(bytesPerSec / 1024.0)
            else -> "$bytesPerSec B/s"
        }
    }

    fun formatTime(seconds: Long): String {
        return when {
            seconds >= 3600 -> "%dh %dm".format(seconds / 3600, (seconds % 3600) / 60)
            seconds >= 60 -> "%dm %ds".format(seconds / 60, seconds % 60)
            seconds > 0 -> "${seconds}s left"
            else -> ""
        }
    }
}
