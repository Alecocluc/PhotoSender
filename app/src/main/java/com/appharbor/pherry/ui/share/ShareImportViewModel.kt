package com.appharbor.pherry.ui.share

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appharbor.pherry.data.model.ConnectionState
import com.appharbor.pherry.data.model.MediaItem
import com.appharbor.pherry.data.network.ConnectionManager
import com.appharbor.pherry.data.share.ShareIntakeBus
import com.appharbor.pherry.data.share.SharedItem
import com.appharbor.pherry.data.share.SharedMediaImporter
import com.appharbor.pherry.data.share.SharedImportFailure
import com.appharbor.pherry.data.upload.UploadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    /** The paired desktop's name; blank until a health check has answered. */
    val serverName: StateFlow<String> = connectionManager.serverName

    private val _preview = MutableStateFlow(SharePreview())
    val preview: StateFlow<SharePreview> = _preview.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()
    private val _failures = MutableStateFlow<List<SharedImportFailure>>(emptyList())
    val failures: StateFlow<List<SharedImportFailure>> = _failures.asStateFlow()
    private val _queuedCount = MutableStateFlow(0)
    val queuedCount: StateFlow<Int> = _queuedCount.asStateFlow()
    private val preparedItems = LinkedHashMap<Uri, MediaItem>()
    private val _sent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sent = _sent.asSharedFlow()

    init {
        // Resolve metadata for the sheet whenever a new set of shared URIs arrives. The bus is a
        // StateFlow, so collection is already conflated to distinct values.
        shareIntakeBus.requests
            .onEach { uris ->
                if (!_isSending.value && uris != _failures.value.map { it.uri }) {
                    _failures.value = emptyList()
                    _queuedCount.value = 0
                }
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
     * Copy shares into durable app storage and wait until queue records exist before dismissing.
     * Successful items continue; unreadable sources remain in the sheet for a visible retry.
     */
    fun confirmSend() {
        if (_isSending.value) return
        val uris = shareIntakeBus.requests.value
        if (uris.isEmpty()) return
        _isSending.value = true
        viewModelScope.launch {
            try {
                val needCopy = uris.filter { it !in preparedItems }
                val result = sharedMediaImporter.importToCache(needCopy)
                val failedUris = result.failures.mapTo(HashSet()) { it.uri }
                needCopy.filter { it !in failedUris }.zip(result.items).forEach { (uri, item) -> preparedItems[uri] = item }
                val ready = uris.mapNotNull { preparedItems[it] }
                if (ready.isNotEmpty()) uploadManager.enqueueAndSchedule(ready, userInitiated = true)
                _failures.value = result.failures
                _queuedCount.value += ready.size
                uris.filter { it !in failedUris }.forEach { preparedItems.remove(it) }
                shareIntakeBus.replaceIfCurrent(uris, result.failures.map { it.uri })
                if (result.failures.isEmpty() && ready.isNotEmpty()) _sent.emit(Unit)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: com.appharbor.pherry.data.upload.UpgradeReviewRequiredException) {
                _failures.value = uris.map { SharedImportFailure(it, "Shared file", "Review your existing backup, then retry. This shared copy is saved on the phone.") }
            } catch (_: Exception) {
                _failures.value = uris.map { SharedImportFailure(it, "Shared file", "Could not prepare the transfer. Check storage and retry.") }
            } finally {
                _isSending.value = false
            }
        }
    }

    fun cancel() {
        if (_isSending.value) return
        _failures.value = emptyList()
        _queuedCount.value = 0
        preparedItems.clear()
        shareIntakeBus.clear()
    }
}
