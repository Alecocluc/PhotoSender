package com.appharbor.pherry.data.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.provider.MediaStore
import com.appharbor.pherry.data.model.MediaFilter
import com.appharbor.pherry.data.model.MediaFolder
import com.appharbor.pherry.data.model.MediaItem
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
    @Volatile private var lastFullScanKeys: Set<String>? = null
    @Volatile private var lastFullScanAt: Long = 0

    /** Deletes may use only a recent, complete query of both media collections. */
    fun isAuthoritativeSnapshot(items: List<MediaItem>): Boolean =
        System.currentTimeMillis() - lastFullScanAt < 60_000 &&
            lastFullScanKeys == items.mapTo(HashSet()) { it.uri.toString() }

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
            if (filter == MediaFilter.ALL) lastFullScanKeys = null
            val fullAccessAtStart = canReadAll()
            val items = queryMedia(filter).sortedByDescending { it.dateModified }
            if (filter == MediaFilter.ALL && fullAccessAtStart && canReadAll()) {
                lastFullScanKeys = items.mapTo(HashSet()) { it.uri.toString() }
                lastFullScanAt = System.currentTimeMillis()
            }
            items
        }

    suspend fun getMediaItemsByIds(ids: Set<Long>): List<MediaItem> =
        withContext(Dispatchers.IO) {
            queryMedia(MediaFilter.ALL).filter { it.id in ids }
        }

    private fun queryMedia(filter: MediaFilter): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        if ((filter == MediaFilter.ALL || filter == MediaFilter.PHOTOS) && canRead(photos = true)) {
            items.addAll(queryImages())
        }
        if ((filter == MediaFilter.ALL || filter == MediaFilter.VIDEOS) && canRead(photos = false)) {
            items.addAll(queryVideos())
        }
        return items
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun canRead(photos: Boolean): Boolean = if (Build.VERSION.SDK_INT >= 33) {
        granted(if (photos) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_MEDIA_VIDEO) ||
            (Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
    } else granted(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun canReadAll(): Boolean = if (Build.VERSION.SDK_INT >= 33) {
        granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO)
    } else granted(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun queryImages(): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.GENERATION_MODIFIED,
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
            val generationCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.GENERATION_MODIFIED)
            val storeVersion = MediaStore.getVersion(context).orEmpty()
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
                        generationModified = cursor.getLong(generationCol),
                        mediaStoreVersion = storeVersion,
                    )
                )
            }
        } ?: throw IllegalStateException("The photo library could not be read")
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
            MediaStore.MediaColumns.GENERATION_MODIFIED,
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
            val generationCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.GENERATION_MODIFIED)
            val storeVersion = MediaStore.getVersion(context).orEmpty()
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
                        generationModified = cursor.getLong(generationCol),
                        mediaStoreVersion = storeVersion,
                    )
                )
            }
        } ?: throw IllegalStateException("The video library could not be read")
        return items
    }
}
