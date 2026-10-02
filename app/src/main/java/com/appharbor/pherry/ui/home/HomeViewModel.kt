package com.appharbor.pherry.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appharbor.pherry.data.db.UploadRecord
import com.appharbor.pherry.data.db.UploadRecordDao
import com.appharbor.pherry.data.media.MediaRepository
import com.appharbor.pherry.data.model.ConnectionState
import com.appharbor.pherry.data.model.MediaFilter
import com.appharbor.pherry.data.network.ConnectionManager
import com.appharbor.pherry.data.network.RememberedComputer
import com.appharbor.pherry.data.preferences.AppPreferences
import com.appharbor.pherry.data.upload.SyncPlan
import com.appharbor.pherry.data.upload.TransferState
import com.appharbor.pherry.data.upload.UploadManager
import com.appharbor.pherry.ui.gallery.UploadMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** How many library items aren't on the desktop yet (the "Back up new" target). */
data class UnsentState(
    val count: Int = 0,
    val bytes: Long = 0,
    /**
     * Files on the desktop that you've since removed from this phone — what a Sync would delete.
     * Always 0 in Add mode (Add never deletes); only computed when the default mode is Sync, so the
     * Home tile can offer a sync instead of falsely claiming "all caught up".
     */
    val deleteCount: Int = 0,
    /** The mode this snapshot was computed under, so the tile picks "Sync" vs "Back up" consistently. */
    val syncMode: Boolean = false,
    /** Photos and videos on this phone at the time of the scan (the envelope's ON PHONE field). */
    val libraryCount: Int = 0,
    /** Completed versions in the visible library, scoped to the selected destination. */
    val backedUpCount: Int = 0,
    val error: String? = null,
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
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val uploadManager: UploadManager,
    private val mediaRepository: MediaRepository,
    uploadRecordDao: UploadRecordDao,
    private val connectionManager: ConnectionManager,
    private val appPreferences: AppPreferences,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState
    val serverName: StateFlow<String> = connectionManager.serverName
    val connectionReason = connectionManager.connectionReason

    /** The saved computer, or null when none is saved; tells "not answering" apart from "not paired". */
    val rememberedComputer: StateFlow<RememberedComputer?> = connectionManager.rememberedComputer

    // Seeded with the live value so the first frame shows the running job, not an empty one.
    val transferState: StateFlow<TransferState> = uploadManager.transferState
        .sample(250L)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), uploadManager.transferState.value)

    val completedCount: StateFlow<Int> = connectionManager.receiverIdentity.flatMapLatest { target ->
        if (target == null) flowOf(0) else uploadRecordDao.getCompletedCount(target.deviceId, target.libraryId)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val failedCount: StateFlow<Int> = connectionManager.receiverIdentity.flatMapLatest { target ->
        if (target == null) flowOf(0) else uploadRecordDao.getFailedCount(target.deviceId, target.libraryId)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val queuedCount: StateFlow<Int> = connectionManager.receiverIdentity.flatMapLatest { target ->
        if (target == null) flowOf(0) else uploadRecordDao.getQueuedCount(target.deviceId, target.libraryId)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val totalTransferredBytes: StateFlow<Long> = connectionManager.receiverIdentity.flatMapLatest { target ->
        if (target == null) flowOf(0L) else uploadRecordDao.getTotalTransferredBytes(target.deviceId, target.libraryId)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** The last frames that reached the computer, newest first (Home's film strip). */
    val recentSent: StateFlow<List<UploadRecord>> = connectionManager.receiverIdentity.flatMapLatest { target ->
        if (target == null) flowOf(emptyList()) else uploadRecordDao.getRecentCompleted(target.deviceId, target.libraryId, 12)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val lastBackupAt: StateFlow<Long> = connectionManager.receiverIdentity.flatMapLatest { target ->
        if (target == null) flowOf(0L) else uploadRecordDao.getLastSyncTimestamp(target.deviceId, target.libraryId)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val autoBackupEnabled: StateFlow<Boolean> = appPreferences.autoBackupEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val autoBackupRequiresCharging: StateFlow<Boolean> = appPreferences.autoBackupRequiresCharging
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val wifiOnly: StateFlow<Boolean> = appPreferences.wifiOnlyTransfer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val confirmDestructiveSync: StateFlow<Boolean> = appPreferences.confirmDestructiveSync
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    /** Result/summary of the most recent sync's desktop-deletion step (surfaced as a toast). */
    val syncState = uploadManager.syncState

    private val _unsent = MutableStateFlow(UnsentState())
    val unsent: StateFlow<UnsentState> = _unsent.asStateFlow()

    /** Non-null while the Sync confirmation dialog is showing the computed plan. */
    private val _pendingSyncPlan = MutableStateFlow<SyncPlan?>(null)
    val pendingSyncPlan: StateFlow<SyncPlan?> = _pendingSyncPlan.asStateFlow()

    private val _isPreparingSync = MutableStateFlow(false)
    val isPreparingSync: StateFlow<Boolean> = _isPreparingSync.asStateFlow()

    /** True while "Back up N now" scans the library and queues it, so a second tap can't double-queue. */
    private val _isQueueing = MutableStateFlow(false)
    val isQueueing: StateFlow<Boolean> = _isQueueing.asStateFlow()

    // One-shot "queued, open Transfers" events. The screen collects them, so the NavController
    // never leaks into viewModelScope across a configuration change; with Home off screen the event
    // is simply dropped rather than navigating later.
    private val _queued = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val queued: SharedFlow<Unit> = _queued.asSharedFlow()
    private var backUpJob: Job? = null

    private var unsentJob: Job? = null
    private var lastComputedAt = 0L
    // Snapshot used for the Home summary; confirmation always takes a fresh scan.
    private var latestSyncPlan: SyncPlan? = null

    init {
        viewModelScope.launch {
            connectionManager.receiverIdentity.drop(1).collect {
                unsentJob?.cancel()
                latestSyncPlan = null
                _pendingSyncPlan.value = null
                lastComputedAt = 0L
                _unsent.value = UnsentState()
                refreshUnsent(force = true)
            }
        }
        // A new completion (or a record going away) means the unsent set changed — invalidate so the
        // next refresh recomputes. drop(1) skips the flow's initial replay value.
        viewModelScope.launch {
            connectionManager.receiverIdentity.flatMapLatest { target ->
                if (target == null) flowOf(0) else uploadRecordDao.getCompletedCount(target.deviceId, target.libraryId)
            }.drop(1).collect { lastComputedAt = 0L }
        }
        // Switching Add⇄Sync changes what the tile should show (deletions only matter in Sync), so
        // invalidate too — otherwise a just-changed mode would keep showing the old snapshot.
        viewModelScope.launch {
            appPreferences.defaultUploadMode.drop(1).collect { lastComputedAt = 0L }
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
            try {
                val liveItems = mediaRepository.loadAllMedia(MediaFilter.ALL)
                val completedIds = uploadManager.completedMediaStoreIds(liveItems)
                val backedUpCount = liveItems.count { it.id in completedIds }
                lastComputedAt = System.currentTimeMillis()
                _unsent.value = if (appPreferences.defaultUploadMode.first() == UploadMode.SYNC.name) {
                    // Sync mode: the full plan also tells us what's been removed from the phone, so the
                    // tile can surface pending desktop deletions instead of "all caught up".
                    val plan = uploadManager.computeSyncPlan(liveItems)
                    latestSyncPlan = plan
                    UnsentState(
                        count = plan.uploadCount,
                        bytes = plan.uploadBytes,
                        deleteCount = plan.deleteCount,
                        syncMode = true,
                        libraryCount = liveItems.size,
                        backedUpCount = backedUpCount,
                        isLoading = false,
                        computed = true,
                    )
                } else {
                    latestSyncPlan = null
                    val items = uploadManager.filterUnsent(liveItems)
                    UnsentState(
                        count = items.size,
                        bytes = items.sumOf { it.size },
                        syncMode = false,
                        libraryCount = liveItems.size,
                        backedUpCount = backedUpCount,
                        isLoading = false,
                        computed = true,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                _unsent.update { it.copy(isLoading = false, computed = false, error = "Could not read the photo library. Check photo access and try again.") }
            }
        }
    }

    /**
     * Take a fresh snapshot before displaying a destructive mirror plan.
     */
    fun prepareSync() {
        if (_isPreparingSync.value) return
        viewModelScope.launch {
            _isPreparingSync.value = true
            try {
                val plan = uploadManager.computeSyncPlan(mediaRepository.loadAllMedia(MediaFilter.ALL))
                latestSyncPlan = plan
                _pendingSyncPlan.value = plan
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                _unsent.update { it.copy(error = "Could not prepare the mirror. Check photo access and try again.") }
            } finally {
                _isPreparingSync.value = false
            }
        }
    }

    /** Execute the confirmed plan. Returns true if it queued uploads, so the caller can navigate. */
    fun confirmSync(): Boolean {
        val plan = _pendingSyncPlan.value ?: return false
        _pendingSyncPlan.value = null
        latestSyncPlan = null
        lastComputedAt = 0L
        uploadManager.executeSync(plan)
        return plan.uploadCount > 0
    }

    fun cancelSync() {
        _pendingSyncPlan.value = null
    }

    fun clearSyncSummary() = uploadManager.clearSyncSummary()

    /**
     * Queue everything not yet on the desktop. Recomputes from a fresh scan so just-taken photos are
     * included, hands the set to the normal upload pipeline, then optimistically zeroes the count.
     */
    fun backUpNew() {
        if (backUpJob?.isActive == true) return
        backUpJob = viewModelScope.launch {
            _isQueueing.value = true
            try {
                val items = uploadManager.filterUnsent(mediaRepository.loadAllMedia(MediaFilter.ALL))
                if (items.isNotEmpty()) uploadManager.enqueueAndSchedule(items, userInitiated = true)
                lastComputedAt = System.currentTimeMillis()
                _unsent.update { it.copy(count = 0, bytes = 0, isLoading = false, computed = true) }
                _queued.tryEmit(Unit)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                _unsent.update { it.copy(error = "Could not queue your photos. Check photo access and try again.") }
            } finally {
                _isQueueing.value = false
            }
        }
    }

    fun retryFailed() = uploadManager.retryFailed()

    /** Pause the running transfer durably. Only an explicit Resume starts it again. */
    fun stopTransfer() = uploadManager.cancelTransfer()

    /** Try the last computer again, e.g. right after Android grants local network access. */
    fun reconnect() = connectionManager.autoReconnect()

    /** Restart a queue that was stopped (or interrupted) without waiting for the next app start. */
    fun resumeQueued() = uploadManager.resumeTransfer()

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
