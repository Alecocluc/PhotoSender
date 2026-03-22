package com.appharbor.photosender.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appharbor.photosender.data.media.MediaRepository
import com.appharbor.photosender.data.model.MediaFilter
import com.appharbor.photosender.data.model.MediaFolder
import com.appharbor.photosender.data.model.MediaItem
import com.appharbor.photosender.data.upload.UploadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val uploadManager: UploadManager,
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
                uploadManager.startTransfer(selectedItems)
            }
        }
    }
}
