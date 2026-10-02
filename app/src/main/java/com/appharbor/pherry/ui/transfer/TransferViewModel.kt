package com.appharbor.pherry.ui.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appharbor.pherry.data.db.UploadRecordDao
import com.appharbor.pherry.data.network.ConnectionManager
import com.appharbor.pherry.data.upload.TransferState
import com.appharbor.pherry.data.upload.UploadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class TransferViewModel @Inject constructor(
    private val uploadManager: UploadManager,
    uploadRecordDao: UploadRecordDao,
    connectionManager: ConnectionManager,
) : ViewModel() {

    // Throttle to 4 updates/sec so per-byte progress storms don't thrash the list. Seeded with the
    // live value so opening the screen mid-transfer never flashes the empty state.
    val transferState: StateFlow<TransferState> = uploadManager.transferState
        .sample(250L)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), uploadManager.transferState.value)

    val serverName: StateFlow<String> = connectionManager.serverName

    /** Files queued in the database (pending or mid-upload), including ones left by a stopped run. */
    val queuedCount: StateFlow<Int> = uploadRecordDao.getQueuedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Stop the running transfer. Unsent files stay queued. */
    fun cancelTransfer() = uploadManager.cancelTransfer()

    /** Restart a queue that was stopped or interrupted. */
    fun resumeQueued() = uploadManager.resumeIfPending()
}
