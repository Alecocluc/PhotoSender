package com.appharbor.pherry.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appharbor.pherry.data.db.UploadRecordDao
import com.appharbor.pherry.data.media.MediaRepository
import com.appharbor.pherry.data.model.ConnectionState
import com.appharbor.pherry.data.model.MediaFilter
import com.appharbor.pherry.data.network.ConnectionManager
import com.appharbor.pherry.data.preferences.AppPreferences
import com.appharbor.pherry.data.upload.TransferState
import com.appharbor.pherry.data.upload.UploadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** How many library items aren't on the desktop yet (the "Back up new" target). */
data class UnsentState(
    val count: Int = 0,
    val bytes: Long = 0,
    val isLoading: Boolean = false,
    /** False until the first real scan completes, so the UI can tell "unknown" from "zero". */
    val computed: Boolean = false,
)

/**
 * Backs the Home dashboard. It owns no new persistence — everything but the "unsent" count is a
 * cheap reactive read of flows that already exist (connection, transfer progress, DB counts,
 * preferences). The unsent count needs a MediaStore scan, so it's computed lazily and cached: the
 * staleness guard keeps rapid tab-switching from re-scanning a large library, and a new completion
 * invalidates the cache so the next open recomputes.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val uploadManager: UploadManager,
    private val mediaRepository: MediaRepository,
    uploadRecordDao: UploadRecordDao,
    connectionManager: ConnectionManager,
    appPreferences: AppPreferences,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState
    val serverName: StateFlow<String> = connectionManager.serverName

    val transferState: StateFlow<TransferState> = uploadManager.transferState
        .sample(250L)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TransferState())

    val completedCount: StateFlow<Int> = uploadRecordDao.getCompletedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val failedCount: StateFlow<Int> = uploadRecordDao.getFailedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val queuedCount: StateFlow<Int> = uploadRecordDao.getQueuedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val totalTransferredBytes: StateFlow<Long> = uploadRecordDao.getTotalTransferredBytes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val lastBackupAt: StateFlow<Long> = uploadRecordDao.getLastSyncTimestamp()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val autoBackupEnabled: StateFlow<Boolean> = appPreferences.autoBackupEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val autoBackupRequiresCharging: StateFlow<Boolean> = appPreferences.autoBackupRequiresCharging
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val wifiOnly: StateFlow<Boolean> = appPreferences.wifiOnlyTransfer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val _unsent = MutableStateFlow(UnsentState())
    val unsent: StateFlow<UnsentState> = _unsent.asStateFlow()

    private var unsentJob: Job? = null
    private var lastComputedAt = 0L

    init {
        // A new completion (or a record going away) means the unsent set changed — invalidate so the
        // next refresh recomputes. drop(1) skips the flow's initial replay value.
        viewModelScope.launch {
            uploadRecordDao.getCompletedCount().drop(1).collect { lastComputedAt = 0L }
        }
    }

    /**
     * Recompute the unsent count if it's stale (or [force]d). Caller gates this on a live connection
     * and media permission — without permission the MediaStore scan returns nothing, which would
     * masquerade as "0 new". Concurrent calls coalesce.
     */
    fun refreshUnsent(force: Boolean = false) {
        val fresh = System.currentTimeMillis() - lastComputedAt < STALE_MS
        if (!force && fresh && _unsent.value.computed) return
        if (unsentJob?.isActive == true) return
        unsentJob = viewModelScope.launch {
            _unsent.update { it.copy(isLoading = true) }
            val items = uploadManager.filterUnsent(mediaRepository.loadAllMedia(MediaFilter.ALL))
            lastComputedAt = System.currentTimeMillis()
            _unsent.value = UnsentState(
                count = items.size,
                bytes = items.sumOf { it.size },
                isLoading = false,
                computed = true,
            )
        }
    }

    /**
     * Queue everything not yet on the desktop. Recomputes from a fresh scan so just-taken photos are
     * included, hands the set to the normal upload pipeline, then optimistically zeroes the count.
     */
    fun backUpNew(onQueued: () -> Unit) {
        viewModelScope.launch {
            val items = uploadManager.filterUnsent(mediaRepository.loadAllMedia(MediaFilter.ALL))
            if (items.isNotEmpty()) uploadManager.start(items)
            lastComputedAt = System.currentTimeMillis()
            _unsent.value = UnsentState(count = 0, bytes = 0, isLoading = false, computed = true)
            onQueued()
        }
    }

    fun retryFailed() = uploadManager.retryFailed()

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    fun formatSpeed(bytesPerSec: Long): String = when {
        bytesPerSec >= 1_048_576 -> "%.1f MB/s".format(bytesPerSec / 1_048_576.0)
        bytesPerSec >= 1024 -> "%.1f KB/s".format(bytesPerSec / 1024.0)
        else -> "$bytesPerSec B/s"
    }

    fun formatTime(seconds: Long): String = when {
        seconds >= 3600 -> "%dh %dm".format(seconds / 3600, (seconds % 3600) / 60)
        seconds >= 60 -> "%dm %ds".format(seconds / 60, seconds % 60)
        seconds > 0 -> "${seconds}s left"
        else -> ""
    }

    private companion object {
        const val STALE_MS = 15_000L
    }
}
