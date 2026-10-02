package com.appharbor.pherry.ui.gallery

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appharbor.pherry.data.media.MediaRepository
import com.appharbor.pherry.data.model.MediaFilter
import com.appharbor.pherry.data.model.MediaFolder
import com.appharbor.pherry.data.model.MediaItem
import com.appharbor.pherry.data.network.ConnectionManager
import com.appharbor.pherry.data.preferences.AppPreferences
import com.appharbor.pherry.data.upload.SyncPlan
import com.appharbor.pherry.data.upload.UploadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** One frame of an album's preview strip. */
data class AlbumFrame(
    val id: Long,
    val uri: Uri,
    val isVideo: Boolean,
    val backedUp: Boolean,
)

/** An album as the Library shows it: one film strip of its newest frames and its backup state. */
data class AlbumSummary(
    val name: String,
    val itemCount: Int,
    val unsentCount: Int,
    val preview: List<AlbumFrame>,
    /** Every item in the album, so its strip can say how many of them are in the job. */
    val ids: Set<Long> = emptySet(),
)

/** What a send did: frames put in the job, and frames left out because the computer already has them. */
data class SendOutcome(val queued: Int, val alreadySent: Int)

/** The id sets behind the Library's quick picks, so a chip can show whether its pick is already selected. */
data class QuickPicks(
    val newIds: Set<Long> = emptySet(),
    val recentIds: Set<Long> = emptySet(),
    val allIds: Set<Long> = emptySet(),
)

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val uploadManager: UploadManager,
    connectionManager: ConnectionManager,
    appPreferences: AppPreferences,
) : ViewModel() {

    private val _folders = MutableStateFlow<List<MediaFolder>>(emptyList())
    val folders: StateFlow<List<MediaFolder>> = _folders.asStateFlow()

    private val _albums = MutableStateFlow<List<AlbumSummary>>(emptyList())
    val albums: StateFlow<List<AlbumSummary>> = _albums.asStateFlow()

    private val _currentFolderItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val currentFolderItems: StateFlow<List<MediaItem>> = _currentFolderItems.asStateFlow()

    /** The album [currentFolderItems] belongs to; null while an album is loading. */
    private val _currentFolderName = MutableStateFlow<String?>(null)
    val currentFolderName: StateFlow<String?> = _currentFolderName.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    private val _filter = MutableStateFlow(MediaFilter.ALL)
    val filter: StateFlow<MediaFilter> = _filter.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /** True once the library has been read at least once for the current filter. */
    private val _libraryLoaded = MutableStateFlow(false)
    val libraryLoaded: StateFlow<Boolean> = _libraryLoaded.asStateFlow()

    private val _totalAssetCount = MutableStateFlow(0)
    val totalAssetCount: StateFlow<Int> = _totalAssetCount.asStateFlow()

    private val _allMediaIds = MutableStateFlow<Set<Long>>(emptySet())

    /** MediaStore ids this phone has already backed up (completed upload records). */
    private val _completedIds = MutableStateFlow<Set<Long>>(emptySet())
    val completedIds: StateFlow<Set<Long>> = _completedIds.asStateFlow()

    private val _quickPicks = MutableStateFlow(QuickPicks())
    val quickPicks: StateFlow<QuickPicks> = _quickPicks.asStateFlow()

    /** Items in the current filter not yet backed up. */
    val unsentCount: StateFlow<Int> = _quickPicks
        .map { it.newIds.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** File sizes of every item seen so far, so a selection spanning albums and filters can be weighed. */
    private val _knownSizes = MutableStateFlow<Map<Long, Long>>(emptyMap())

    /** Total bytes the current selection would send (frames already on the computer weigh nothing). */
    val selectedBytes: StateFlow<Long> = combine(_selectedIds, _knownSizes, _completedIds) { ids, sizes, completed ->
        ids.sumOf { if (it in completed) 0L else sizes[it] ?: 0L }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    val serverName: StateFlow<String> = connectionManager.serverName

    private val _uploadMode = MutableStateFlow(UploadMode.ADD)
    val uploadMode: StateFlow<UploadMode> = _uploadMode.asStateFlow()

    /** Non-null while the Sync confirmation dialog is showing the computed plan. */
    private val _pendingSyncPlan = MutableStateFlow<SyncPlan?>(null)
    val pendingSyncPlan: StateFlow<SyncPlan?> = _pendingSyncPlan.asStateFlow()

    private val _isPreparingSync = MutableStateFlow(false)
    val isPreparingSync: StateFlow<Boolean> = _isPreparingSync.asStateFlow()

    /** Result/summary of the most recent sync's desktop-deletion step (for a snackbar). */
    val syncState = uploadManager.syncState

    val confirmDestructiveSync: StateFlow<Boolean> = appPreferences.confirmDestructiveSync
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    /** The last library read, kept so backup marks can be refreshed without re-querying MediaStore. */
    private var lastMedia: List<MediaItem>? = null
    private var libraryJob: Job? = null
    private var folderJob: Job? = null

    init {
        viewModelScope.launch {
            appPreferences.defaultUploadMode.collect { mode ->
                _uploadMode.value = UploadMode.entries.firstOrNull { it.name == mode } ?: UploadMode.ADD
            }
        }
        // When a transfer ends, re-read which items are on the computer so the marks stay truthful.
        viewModelScope.launch {
            uploadManager.transferState
                .map { it.isTransferring }
                .distinctUntilChanged()
                .drop(1)
                .collect { transferring -> if (!transferring) refreshBackupState() }
        }
    }

    /** Read the library for the current filter. Needs only media permission, never a connection. */
    fun loadFolders() {
        libraryJob?.cancel()
        libraryJob = viewModelScope.launch {
            _isLoading.value = true
            val media = readSafely { mediaRepository.loadAllMedia(_filter.value) }
            val completed = uploadManager.completedMediaStoreIds()
            rememberSizes(media)
            val (ids, folders) = withContext(Dispatchers.Default) {
                val folders = media.groupBy { it.bucketName }
                    .map { (bucket, items) ->
                        MediaFolder(
                            id = bucket.hashCode().toLong(),
                            bucketName = bucket,
                            coverUri = items.first().uri,
                            itemCount = items.size,
                        )
                    }
                    .sortedByDescending { it.itemCount }
                media.mapTo(LinkedHashSet()) { it.id } to folders
            }
            lastMedia = media
            _totalAssetCount.value = media.size
            _allMediaIds.value = ids
            _folders.value = folders
            applyCompleted(completed)
            _libraryLoaded.value = true
            _isLoading.value = false
        }
    }

    fun loadFolderItems(bucketName: String) {
        folderJob?.cancel()
        folderJob = viewModelScope.launch {
            if (_currentFolderName.value != bucketName) {
                _currentFolderName.value = null
                _currentFolderItems.value = emptyList()
            }
            val items = readSafely { mediaRepository.loadMediaInFolder(bucketName, _filter.value) }
            val completed = uploadManager.completedMediaStoreIds()
            rememberSizes(items)
            applyCompleted(completed)
            _currentFolderItems.value = items
            _currentFolderName.value = bucketName
        }
    }

    fun setFilter(filter: MediaFilter) {
        if (_filter.value == filter && _libraryLoaded.value) return
        _filter.value = filter
        // Show unexposed film rather than the previous filter's albums under the new label.
        _libraryLoaded.value = false
        _albums.value = emptyList()
        loadFolders()
    }

    fun toggleSelection(id: Long) {
        _selectedIds.update { current ->
            if (id in current) current - id else current + id
        }
    }

    fun selectAll(items: List<MediaItem>) {
        viewModelScope.launch { rememberSizes(items) }
        _selectedIds.update { current ->
            current + items.map { it.id }.toSet()
        }
    }

    /** Add every item in [items] that isn't on the computer yet ("Select new" in an album). */
    fun selectUnsent(items: List<MediaItem>) {
        val completed = _completedIds.value
        selectAll(items.filter { it.id !in completed })
    }

    /** Remove [ids] from the selection (un-ticking a quick pick). */
    fun deselect(ids: Set<Long>) {
        _selectedIds.update { current -> current - ids }
    }

    fun deselectAll() {
        _selectedIds.value = emptySet()
    }

    fun isAllSelected(items: List<MediaItem>): Boolean {
        return items.isNotEmpty() && items.all { it.id in _selectedIds.value }
    }

    fun selectAllMedia() {
        viewModelScope.launch {
            val allMedia = readSafely { mediaRepository.loadAllMedia(_filter.value) }
            rememberSizes(allMedia)
            _selectedIds.update { current ->
                current + allMedia.map { it.id }.toSet()
            }
        }
    }

    /** Quick-select everything modified within the last [days] days (Add mode). */
    fun selectRecent(days: Int) {
        viewModelScope.launch {
            val recent = readSafely { mediaRepository.loadAllMedia(_filter.value) }
                .filter { it.dateModified >= recentCutoffSeconds(days) }
            rememberSizes(recent)
            _selectedIds.update { current -> current + recent.map { it.id }.toSet() }
        }
    }

    /** Quick-select every item not yet backed up to the desktop (Add mode). */
    fun selectNewSinceBackup() {
        viewModelScope.launch {
            val completed = uploadManager.completedMediaStoreIds()
            val unsent = readSafely { mediaRepository.loadAllMedia(_filter.value) }
                .filter { it.id !in completed }
            rememberSizes(unsent)
            _selectedIds.update { current -> current + unsent.map { it.id }.toSet() }
        }
    }

    fun isAllMediaSelected(): Boolean {
        val allIds = _allMediaIds.value
        return allIds.isNotEmpty() && allIds.all { it in _selectedIds.value }
    }

    /**
     * Queue the selection, leaving out what is already on the computer (the queue would skip it
     * anyway, and counting it would leave the job short). When nothing is left to send, nothing is
     * queued and the selection is cleared: the caller says so instead of opening Transfers.
     */
    fun startTransfer(): SendOutcome {
        val selected = _selectedIds.value
        val completed = _completedIds.value
        val toSend = selected.filterTo(LinkedHashSet()) { it !in completed }
        val outcome = SendOutcome(queued = toSend.size, alreadySent = selected.size - toSend.size)
        if (toSend.isEmpty()) {
            _selectedIds.value = emptySet()
            return outcome
        }
        viewModelScope.launch {
            val selectedItems = mediaRepository.getMediaItemsByIds(toSend)
            if (selectedItems.isNotEmpty()) {
                uploadManager.start(selectedItems)
            }
            // Clear the selection once it's queued so it doesn't linger across folders/filters and
            // get accidentally re-sent on the next transfer.
            _selectedIds.value = emptySet()
        }
        return outcome
    }

    fun setMode(mode: UploadMode) {
        if (_uploadMode.value == mode) return
        _uploadMode.value = mode
        // The two modes don't share a selection model (Sync diffs the whole library), so drop any
        // pending Add-mode selection when switching to avoid a stale action bar.
        _selectedIds.value = emptySet()
    }

    /** Compute the whole-library sync diff and surface it for confirmation. */
    fun prepareSync() {
        if (_isPreparingSync.value) return
        viewModelScope.launch {
            _isPreparingSync.value = true
            val liveItems = mediaRepository.loadAllMedia(MediaFilter.ALL)
            _pendingSyncPlan.value = uploadManager.computeSyncPlan(liveItems)
            _isPreparingSync.value = false
        }
    }

    /** Run the previously-computed plan: returns true when there's an upload to navigate to. */
    fun confirmSync(): Boolean {
        val plan = _pendingSyncPlan.value ?: return false
        _pendingSyncPlan.value = null
        uploadManager.executeSync(plan)
        return plan.uploadCount > 0
    }

    fun cancelSync() {
        _pendingSyncPlan.value = null
    }

    fun clearSyncSummary() = uploadManager.clearSyncSummary()

    private fun refreshBackupState() {
        viewModelScope.launch { applyCompleted(uploadManager.completedMediaStoreIds()) }
    }

    /** Publish [completed] and rebuild everything derived from it: album strips and quick picks. */
    private suspend fun applyCompleted(completed: Set<Long>) {
        _completedIds.value = completed
        val media = lastMedia ?: return
        val allIds = _allMediaIds.value
        val (albums, picks) = withContext(Dispatchers.Default) {
            val cutoff = recentCutoffSeconds(RECENT_DAYS)
            val albums = media.groupBy { it.bucketName }
                .map { (name, items) ->
                    // Items arrive newest first, so each group's head is the album's newest frames.
                    AlbumSummary(
                        name = name,
                        itemCount = items.size,
                        unsentCount = items.count { it.id !in completed },
                        preview = items.take(PREVIEW_FRAMES).map {
                            AlbumFrame(id = it.id, uri = it.uri, isVideo = it.isVideo, backedUp = it.id in completed)
                        },
                        ids = items.mapTo(HashSet(items.size)) { it.id },
                    )
                }
                .sortedByDescending { it.itemCount }
            val picks = QuickPicks(
                newIds = media.filter { it.id !in completed }.mapTo(LinkedHashSet()) { it.id },
                recentIds = media.filter { it.dateModified >= cutoff }.mapTo(LinkedHashSet()) { it.id },
                allIds = allIds,
            )
            albums to picks
        }
        _albums.value = albums
        _quickPicks.value = picks
    }

    private suspend fun rememberSizes(items: List<MediaItem>) {
        if (items.isEmpty()) return
        withContext(Dispatchers.Default) {
            _knownSizes.update { known -> known + items.associate { it.id to it.size } }
        }
    }

    /** MediaStore throws if access is revoked mid-session; treat that as an empty read. */
    private suspend fun readSafely(block: suspend () -> List<MediaItem>): List<MediaItem> =
        try {
            block()
        } catch (e: SecurityException) {
            emptyList()
        }

    companion object {
        /** Frames in an album's preview strip (the widest strip layout shows six). */
        const val PREVIEW_FRAMES = 6
        const val RECENT_DAYS = 30

        // MediaStore DATE_MODIFIED is in seconds.
        private fun recentCutoffSeconds(days: Int): Long =
            (System.currentTimeMillis() - days * 86_400_000L) / 1000L
    }
}

internal val MediaItem.isVideo: Boolean get() = mimeType.startsWith("video/")

enum class UploadMode { ADD, SYNC }
