package com.appharbor.photosender.data.model

import android.net.Uri

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val size: Long,
    val dateModified: Long,
    val mimeType: String,
    val bucketName: String,
)
