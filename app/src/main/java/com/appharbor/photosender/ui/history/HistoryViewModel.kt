package com.appharbor.photosender.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appharbor.photosender.data.db.UploadRecord
import com.appharbor.photosender.data.db.UploadRecordDao
import com.appharbor.photosender.data.upload.UploadManager
import com.appharbor.photosender.data.upload.VerifyState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val uploadRecordDao: UploadRecordDao,
    private val uploadManager: UploadManager,
) : ViewModel() {

    val completedCount: StateFlow<Int> = uploadRecordDao.getCompletedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val failedCount: StateFlow<Int> = uploadRecordDao.getFailedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val verifyState: StateFlow<VerifyState> = uploadManager.verifyState

    val totalTransferredBytes: StateFlow<Long> = uploadRecordDao.getTotalTransferredBytes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val lastSyncTimestamp: StateFlow<Long> = uploadRecordDao.getLastSyncTimestamp()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val recentHistory: StateFlow<List<UploadRecord>> = uploadRecordDao.getRecentCompleted(20)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun clearHistory() {
        viewModelScope.launch {
            uploadRecordDao.clearAll()
        }
    }

    /** Re-queue every hard-failed record and kick the worker. */
    fun retryFailed() = uploadManager.retryFailed()

    /** Reconcile completed records against the server; re-queue anything actually missing. */
    fun verifyBackup() = uploadManager.verifyAgainstServer()

    fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
