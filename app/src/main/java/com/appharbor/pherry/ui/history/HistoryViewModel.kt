package com.appharbor.pherry.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appharbor.pherry.data.db.UploadRecord
import com.appharbor.pherry.data.db.UploadRecordDao
import com.appharbor.pherry.data.model.ConnectionState
import com.appharbor.pherry.data.network.ConnectionManager
import com.appharbor.pherry.data.upload.UploadManager
import com.appharbor.pherry.data.upload.VerifyState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val uploadRecordDao: UploadRecordDao,
    private val uploadManager: UploadManager,
    connectionManager: ConnectionManager,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState

    val completedCount: StateFlow<Int> = uploadRecordDao.getCompletedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val failedCount: StateFlow<Int> = uploadRecordDao.getFailedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val verifyState: StateFlow<VerifyState> = uploadManager.verifyState

    val totalTransferredBytes: StateFlow<Long> = uploadRecordDao.getTotalTransferredBytes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val lastSyncTimestamp: StateFlow<Long> = uploadRecordDao.getLastSyncTimestamp()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** True while the History tab is on screen; set by the tab itself. */
    private val historyShown = MutableStateFlow(false)

    /**
     * The newest [HISTORY_LIMIT] completed transfers; null until the database has answered.
     * Queried only while the History tab is shown: Room re-runs this 500-row query on every record
     * change, which during a transfer means every file. While hidden it keeps the last list it had.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val recentHistory: StateFlow<List<UploadRecord>?> = historyShown
        .flatMapLatest { shown -> if (shown) uploadRecordDao.getRecentCompleted(HISTORY_LIMIT) else emptyFlow() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setHistoryShown(shown: Boolean) {
        historyShown.value = shown
    }

    /**
     * Forget the sent files only (what the ledger lists). Queued and failed records stay, so a paused
     * queue and the Retry notice survive a clear.
     */
    fun clearHistory() {
        viewModelScope.launch {
            uploadRecordDao.clearCompleted()
        }
    }

    /** Re-queue every hard-failed record and kick the worker. */
    fun retryFailed() = uploadManager.retryFailed()

    /** Reconcile completed records against the server; re-queue anything actually missing. */
    fun verifyBackup() = uploadManager.verifyAgainstServer()

    companion object {
        const val HISTORY_LIMIT = 500
    }
}
