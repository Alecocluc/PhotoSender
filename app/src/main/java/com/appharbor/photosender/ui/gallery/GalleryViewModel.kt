package com.appharbor.photosender.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appharbor.photosender.data.media.MediaRepository
import com.appharbor.photosender.data.model.MediaFilter
import com.appharbor.photosender.data.model.MediaFolder
import com.appharbor.photosender.data.model.MediaItem
import com.appharbor.photosender.data.preferences.AppPreferences
import com.appharbor.photosender.data.upload.SyncPlan
import com.appharbor.photosender.data.upload.UploadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val uploadManager: UploadManager,
    appPreferences: AppPreferences,
) : ViewModel() {

    private val _folders = MutableStateFlow<List<MediaFolder>>(emptyList())
    val folders: StateFlow<List<MediaFolder>> = _folders.asStateFlow()

    private val _currentFolderItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val currentFolderItems: StateFlow<List<MediaItem>> = _currentFolderItems.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    private val _filter = MutableStateFlow(MediaFilter.ALL)
    val filter: StateFlow<MediaFilter> = _filter.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _totalAssetCount = MutableStateFlow(0)
    val totalAssetCount: StateFlow<Int> = _totalAssetCount.asStateFlow()

    private val _allMediaIds = MutableStateFlow<Set<Long>>(emptySet())

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

    init {
        viewModelScope.launch {
            appPreferences.defaultUploadMode.collect { mode ->
                _uploadMode.value = UploadMode.entries.firstOrNull { it.name == mode } ?: UploadMode.ADD
            }
        }
    }

    fun loadFolders() {
        viewModelScope.launch {
            _isLoading.value = true
            val allMedia = mediaRepository.loadAllMedia(_filter.value)
            _totalAssetCount.value = allMedia.size
            _allMediaIds.value = allMedia.map { it.id }.toSet()
            _folders.value = mediaRepository.loadFolders(_filter.value)
            _isLoading.value = false
        }
    }

    fun loadFolderItems(bucketName: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _currentFolderItems.value = mediaRepository.loadMediaInFolder(bucketName, _filter.value)
            _isLoading.value = false
        }
    }

    fun setFilter(filter: MediaFilter) {
        _filter.value = filter
        loadFolders()
    }

    fun toggleSelection(id: Long) {
        _selectedIds.update { current ->
            if (id in current) current - id else current + id
        }
    }

    fun selectAll(items: List<MediaItem>) {
        _selectedIds.update { current ->
            current + items.map { it.id }.toSet()
        }
    }

    fun deselectAll() {
        _selectedIds.value = emptySet()
    }

    fun isAllSelected(items: List<MediaItem>): Boolean {
        return items.isNotEmpty() && items.all { it.id in _selectedIds.value }
    }

    fun selectAllMedia() {
        viewModelScope.launch {
            val allMedia = mediaRepository.loadAllMedia(_filter.value)
            _selectedIds.update { current ->
                current + allMedia.map { it.id }.toSet()
            }
        }
    }

    fun isAllMediaSelected(): Boolean {
        val allIds = _allMediaIds.value
        return allIds.isNotEmpty() && allIds.all { it in _selectedIds.value }
    }

    fun startTransfer() {
        viewModelScope.launch {
            val selectedItems = mediaRepository.getMediaItemsByIds(_selectedIds.value)
            if (selectedItems.isNotEmpty()) {
                uploadManager.start(selectedItems)
            }
        }
    }

    fun setMode(mode: UploadMode) {
        _uploadMode.value = mode
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
}

enum class UploadMode { ADD, SYNC }
