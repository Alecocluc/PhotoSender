package com.appharbor.photosender.data.model

import android.net.Uri

data class MediaFolder(
    val id: Long,
    val bucketName: String,
    val coverUri: Uri,
    val itemCount: Int,
)
