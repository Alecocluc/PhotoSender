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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val uploadRecordDao: UploadRecordDao,
    private val uploadManager: UploadManager,
    private val connectionManager: ConnectionManager,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState

    val completedCount: StateFlow<Int> = connectionManager.receiverIdentity.flatMapLatest { target ->
        if (target == null) flowOf(0) else uploadRecordDao.getCompletedCount(target.deviceId, target.libraryId)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val historyCount: StateFlow<Int> = connectionManager.receiverIdentity.flatMapLatest { target ->
        if (target == null) flowOf(0) else uploadRecordDao.getHistoryCount(target.deviceId, target.libraryId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val failedCount: StateFlow<Int> = connectionManager.receiverIdentity.flatMapLatest { target ->
        if (target == null) flowOf(0) else uploadRecordDao.getFailedCount(target.deviceId, target.libraryId)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val verifyState: StateFlow<VerifyState> = uploadManager.verifyState

    val totalTransferredBytes: StateFlow<Long> = connectionManager.receiverIdentity.flatMapLatest { target ->
        if (target == null) flowOf(0L) else uploadRecordDao.getTotalTransferredBytes(target.deviceId, target.libraryId)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val lastSyncTimestamp: StateFlow<Long> = connectionManager.receiverIdentity.flatMapLatest { target ->
        if (target == null) flowOf(0L) else uploadRecordDao.getLastSyncTimestamp(target.deviceId, target.libraryId)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** True while the History tab is on screen; set by the tab itself. */
    private val historyShown = MutableStateFlow(false)
    private val historyLimit = MutableStateFlow(HISTORY_LIMIT)

    /**
     * The newest [HISTORY_LIMIT] completed transfers; null until the database has answered.
     * Queried only while the History tab is shown: Room re-runs this 500-row query on every record
     * change, which during a transfer means every file. While hidden it keeps the last list it had.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val recentHistory: StateFlow<List<UploadRecord>?> = combine(historyShown, historyLimit, connectionManager.receiverIdentity) { shown, limit, target -> Triple(shown, limit, target) }
        .flatMapLatest { (shown, limit, target) ->
            if (!shown) emptyFlow() else if (target == null) flowOf(emptyList())
            else uploadRecordDao.getRecentCompleted(target.deviceId, target.libraryId, limit)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setHistoryShown(shown: Boolean) {
        historyShown.value = shown
    }

    fun loadMoreHistory() { historyLimit.update { it + HISTORY_LIMIT } }

    /**
     * Hide completed events while keeping their backup receipts, queued work and failed-file retries.
     */
    fun clearHistory() {
        viewModelScope.launch {
            val target = connectionManager.receiverIdentity.value ?: return@launch
            uploadRecordDao.clearCompleted(target.deviceId, target.libraryId)
        }
    }

    /** Re-queue every hard-failed record and kick the worker. */
    fun retryFailed() = uploadManager.retryFailed()

    /** Reconcile completed records against the server; re-queue anything actually missing. */
    fun verifyBackup() = uploadManager.verifyAgainstServer()
    fun auditBackup() = uploadManager.verifyAgainstServer(verifyIntegrity = true)

    companion object {
        const val HISTORY_LIMIT = 500
    }
}
