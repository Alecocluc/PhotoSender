package com.appharbor.pherry.data.share

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges an incoming Android "Share to Pherry" intent (read in [com.appharbor.pherry.MainActivity])
 * to the Compose UI, which lives behind a [androidx.lifecycle.ViewModel] and can't read the activity
 * intent directly. The activity pushes the shared media here; [com.appharbor.pherry.ui.share.ShareImportViewModel]
 * collects it and shows the review sheet.
 *
 * The grant Android attaches to share URIs only lives for the receiving activity instance, so the
 * actual bytes must be copied out (see [SharedMediaImporter]) while the app is still foregrounded —
 * which is exactly when the review sheet is on screen.
 */
@Singleton
class ShareIntakeBus @Inject constructor() {

    private val _requests = MutableStateFlow<List<Uri>>(emptyList())
    val requests: StateFlow<List<Uri>> = _requests.asStateFlow()

    fun submit(uris: List<Uri>) {
        val cleaned = uris.distinct()
        if (cleaned.isNotEmpty()) _requests.value = cleaned
    }

    fun clear() {
        _requests.value = emptyList()
    }
}
