package com.appharbor.pherry.ui.share

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appharbor.pherry.data.model.ConnectionState
import com.appharbor.pherry.data.network.ConnectionManager
import com.appharbor.pherry.data.share.ShareIntakeBus
import com.appharbor.pherry.data.share.SharedItem
import com.appharbor.pherry.data.share.SharedMediaImporter
import com.appharbor.pherry.data.upload.UploadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SharePreview(
    val items: List<SharedItem> = emptyList(),
    val isLoading: Boolean = false,
) {
    val photoCount: Int get() = items.count { !it.isVideo }
    val videoCount: Int get() = items.count { it.isVideo }
    val totalBytes: Long get() = items.sumOf { it.size }
    val count: Int get() = items.size
}

@HiltViewModel
class ShareImportViewModel @Inject constructor(
    private val shareIntakeBus: ShareIntakeBus,
    private val sharedMediaImporter: SharedMediaImporter,
    private val uploadManager: UploadManager,
    connectionManager: ConnectionManager,
) : ViewModel() {

    /** URIs handed over from the share intent; non-empty means the review sheet should be shown. */
    val pendingUris: StateFlow<List<Uri>> = shareIntakeBus.requests

    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState

    private val _preview = MutableStateFlow(SharePreview())
    val preview: StateFlow<SharePreview> = _preview.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    init {
        // Resolve metadata for the sheet whenever a new set of shared URIs arrives. The bus is a
        // StateFlow, so collection is already conflated to distinct values.
        shareIntakeBus.requests
            .onEach { uris ->
                if (uris.isEmpty()) {
                    _preview.value = SharePreview()
                    return@onEach
                }
                _preview.value = SharePreview(isLoading = true)
                _preview.value = SharePreview(items = sharedMediaImporter.describe(uris), isLoading = false)
            }
            .launchIn(viewModelScope)
    }

    /**
     * Copy the shared media into the cache and queue it through the normal upload pipeline, then clear
     * the request. [onQueued] fires on the main dispatcher once work is enqueued so the UI can navigate.
     */
    fun confirmSend(onQueued: () -> Unit) {
        if (_isSending.value) return
        val uris = shareIntakeBus.requests.value
        if (uris.isEmpty()) return
        _isSending.value = true
        viewModelScope.launch {
            val items = sharedMediaImporter.importToCache(uris)
            if (items.isNotEmpty()) uploadManager.start(items)
            shareIntakeBus.clear()
            _isSending.value = false
            onQueued()
        }
    }

    fun cancel() {
        shareIntakeBus.clear()
    }
}
