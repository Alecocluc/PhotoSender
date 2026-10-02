package com.appharbor.pherry.data.share

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.appharbor.pherry.data.db.UploadRecordDao
import com.appharbor.pherry.data.model.MediaItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Lightweight metadata for one shared item, read without copying its bytes. */
data class SharedItem(
    val uri: Uri,
    val displayName: String,
    val size: Long,
    val isVideo: Boolean,
)

data class SharedImportFailure(val uri: Uri, val name: String, val reason: String)
data class SharedImportResult(val items: List<MediaItem>, val failures: List<SharedImportFailure>)

/**
 * Turns media shared into Pherry from another app (Google Photos, the gallery, …) into something the
 * normal upload pipeline can send. Foreign content URIs aren't MediaStore rows and their read grant
 * dies with the receiving activity, so each item is copied into durable app storage up front; the queued
 * [MediaItem] then points at that stable local file.
 */
@Singleton
class SharedMediaImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uploadRecordDao: UploadRecordDao,
) {
    private val resolver: ContentResolver = context.contentResolver

    // Pending uploads must survive Android cache eviction under storage pressure.
    private fun cacheDir(): File = File(context.filesDir, SHARED_DIR).apply { mkdirs() }

    /** Cheap metadata read for the review sheet — queries the provider, copies nothing. */
    suspend fun describe(uris: List<Uri>): List<SharedItem> = withContext(Dispatchers.IO) {
        uris.mapIndexedNotNull { index, uri ->
            val mime = runCatching { resolver.getType(uri) }.getOrNull().orEmpty()
            // Only ferry images/videos; ignore anything else another app might smuggle in.
            if (mime.isNotEmpty() && !mime.startsWith("image/") && !mime.startsWith("video/")) {
                return@mapIndexedNotNull null
            }
            val (name, size) = queryNameAndSize(uri)
            SharedItem(
                uri = uri,
                displayName = name ?: fallbackName(index, mime, uri),
                size = size,
                isVideo = mime.startsWith("video/"),
            )
        }
    }

    /**
     * Copy shared items into app storage and report every unreadable source alongside successful
     * [MediaItem]s. Synthetic negative IDs keep shares separate from MediaStore IDs.
     */
    suspend fun importToCache(uris: List<Uri>): SharedImportResult = withContext(Dispatchers.IO) {
        val dir = cacheDir()
        val items = mutableListOf<MediaItem>()
        val failures = mutableListOf<SharedImportFailure>()
        uris.forEachIndexed { index, uri ->
            coroutineContext.ensureActive()
            var name = "Shared file ${index + 1}"
            var dest: File? = null
            try {
                val mime = resolver.getType(uri).orEmpty()
                if (mime.isNotEmpty() && !mime.startsWith("image/") && !mime.startsWith("video/")) {
                    failures += SharedImportFailure(uri, name, "This file is not a photo or video.")
                    return@forEachIndexed
                }
                name = sanitizeName(queryNameAndSize(uri).first ?: fallbackName(index, mime, uri))
                val file = uniqueFile(dir, name)
                dest = file
                val input = resolver.openInputStream(uri) ?: throw java.io.IOException("Cannot open this file")
                input.use { source ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                if (file.length() == 0L) throw java.io.IOException("The file is empty")
                items += MediaItem(
                    id = idSeq.decrementAndGet(), uri = Uri.fromFile(file), displayName = file.name,
                    size = file.length(), dateModified = System.currentTimeMillis() / 1000L,
                    mimeType = mime.ifEmpty { "application/octet-stream" }, bucketName = SHARED_BUCKET,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                dest?.delete()
                throw e
            } catch (e: Exception) {
                dest?.delete()
                val reason = when (e) {
                    is SecurityException -> "Access expired. Share this file again from its app."
                    is java.io.FileNotFoundException -> "The source file is unavailable. Download it in its app, then retry."
                    else -> "Could not copy this file. Check free space on this phone, then retry."
                }
                failures += SharedImportFailure(uri, name, reason)
            }
        }
        SharedImportResult(items, failures)
    }

    /**
     * Drop cached shares older than [maxAgeMs]. Files survive long enough to be uploaded (and to be
     * re-tried after a process restart); anything older has already been sent or abandoned. Called on
     * launch so the cache can't grow without bound. A file still queued (a pending, uploading or failed
     * record points at it) is kept whatever its age, so a long-paused queue never loses its source.
     */
    suspend fun pruneCache(maxAgeMs: Long = DEFAULT_MAX_AGE_MS) {
        withContext(Dispatchers.IO) {
            // If the queue can't be read, keep everything: a stale cache is cheaper than a lost share.
            val queued = runCatching { queuedCachePaths() }.getOrNull() ?: return@withContext
            runCatching {
                val cutoff = System.currentTimeMillis() - maxAgeMs
                cacheDir().listFiles()?.forEach { file ->
                    if (file.isFile && file.lastModified() < cutoff && file.absolutePath !in queued) file.delete()
                }
            }
        }
    }

    /** Absolute paths of shared files still needed for a transfer or a failed-item retry. */
    private suspend fun queuedCachePaths(): Set<String> =
        (uploadRecordDao.getPendingAndUploading() + uploadRecordDao.getFailed()).mapNotNullTo(HashSet()) { record ->
            val uri = Uri.parse(record.contentUri)
            if (uri.scheme == ContentResolver.SCHEME_FILE) uri.path?.let { File(it).absolutePath } else null
        }

    private fun queryNameAndSize(uri: Uri): Pair<String?, Long> {
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null to 0L
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (nameIdx >= 0 && !cursor.isNull(nameIdx)) cursor.getString(nameIdx) else null
                    val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L
                    name to size
                } ?: (null to 0L)
        }.getOrDefault(null to 0L)
    }

    private fun fallbackName(index: Int, mime: String, uri: Uri): String {
        val fromUri = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.contains('.') }
        if (fromUri != null) return fromUri
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            ?: if (mime.startsWith("video/")) "mp4" else "jpg"
        return "shared-${System.currentTimeMillis()}-$index.$ext"
    }

    private fun sanitizeName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim()
            .ifEmpty { "shared-${System.currentTimeMillis()}" }
            .take(120)

    private fun uniqueFile(dir: File, desiredName: String): File {
        var candidate = File(dir, desiredName)
        if (!candidate.exists()) return candidate
        val dot = desiredName.lastIndexOf('.')
        val base = if (dot > 0) desiredName.substring(0, dot) else desiredName
        val ext = if (dot > 0) desiredName.substring(dot) else ""
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($counter)$ext")
            counter++
        }
        return candidate
    }

    private companion object {
        const val SHARED_DIR = "shared"
        const val SHARED_BUCKET = "Shared"
        const val DEFAULT_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000 // 7 days

        // Seeded far in the negative so ids never collide with positive MediaStore ids, and each
        // process run starts below the previous one's range.
        val idSeq = AtomicLong(-System.currentTimeMillis() * 1000L)
    }
}
