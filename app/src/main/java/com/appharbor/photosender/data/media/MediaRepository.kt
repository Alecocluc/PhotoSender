package com.appharbor.photosender.data.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.appharbor.photosender.data.model.MediaFilter
import com.appharbor.photosender.data.model.MediaFolder
import com.appharbor.photosender.data.model.MediaItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val contentResolver: ContentResolver = context.contentResolver

    suspend fun loadFolders(filter: MediaFilter = MediaFilter.ALL): List<MediaFolder> =
        withContext(Dispatchers.IO) {
            val folders = mutableMapOf<String, MutableList<MediaItem>>()
            queryMedia(filter).forEach { item ->
                folders.getOrPut(item.bucketName) { mutableListOf() }.add(item)
            }
            folders.map { (bucket, items) ->
                MediaFolder(
                    id = bucket.hashCode().toLong(),
                    bucketName = bucket,
                    coverUri = items.first().uri,
                    itemCount = items.size,
                )
            }.sortedByDescending { it.itemCount }
        }

    suspend fun loadMediaInFolder(
        bucketName: String,
        filter: MediaFilter = MediaFilter.ALL,
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        queryMedia(filter).filter { it.bucketName == bucketName }
            .sortedByDescending { it.dateModified }
    }

    suspend fun loadAllMedia(filter: MediaFilter = MediaFilter.ALL): List<MediaItem> =
        withContext(Dispatchers.IO) {
            queryMedia(filter).sortedByDescending { it.dateModified }
        }

    suspend fun getMediaItemsByIds(ids: Set<Long>): List<MediaItem> =
        withContext(Dispatchers.IO) {
            queryMedia(MediaFilter.ALL).filter { it.id in ids }
        }

    private fun queryMedia(filter: MediaFilter): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        if (filter == MediaFilter.ALL || filter == MediaFilter.PHOTOS) {
            items.addAll(queryImages())
        }
        if (filter == MediaFilter.ALL || filter == MediaFilter.VIDEOS) {
            items.addAll(queryVideos())
        }
        return items
    }

    private fun queryImages(): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null, null, sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                items.add(
                    MediaItem(
                        id = id,
                        uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                        displayName = cursor.getString(nameCol) ?: "unknown",
                        size = cursor.getLong(sizeCol),
                        dateModified = cursor.getLong(dateCol),
                        mimeType = cursor.getString(mimeCol) ?: "image/*",
                        bucketName = cursor.getString(bucketCol) ?: "Other",
                    )
                )
            }
        }
        return items
    }

    private fun queryVideos(): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        )
        val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null, null, sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                items.add(
                    MediaItem(
                        id = id,
                        uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                        displayName = cursor.getString(nameCol) ?: "unknown",
                        size = cursor.getLong(sizeCol),
                        dateModified = cursor.getLong(dateCol),
                        mimeType = cursor.getString(mimeCol) ?: "video/*",
                        bucketName = cursor.getString(bucketCol) ?: "Other",
                    )
                )
            }
        }
        return items
    }
}
