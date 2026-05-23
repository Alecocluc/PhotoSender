package com.appharbor.photosender.data.upload

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.provider.MediaStore
import com.appharbor.photosender.data.db.UploadRecord
import com.appharbor.photosender.data.db.UploadRecordDao
import com.appharbor.photosender.data.db.UploadStatus
import com.appharbor.photosender.data.model.MediaItem
import com.appharbor.photosender.data.network.ConnectionManager
import com.appharbor.photosender.data.preferences.AppPreferences
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val SERVER_PORT = 3210

data class FileTransferProgress(
    val recordId: Long,
    val fileName: String,
    val contentUri: String,
    val fileSize: Long,
    val bytesTransferred: Long,
    val status: UploadStatus,
)

data class TransferState(
    val isTransferring: Boolean = false,
    val totalFiles: Int = 0,
    val completedFiles: Int = 0,
    val failedFiles: Int = 0,
    val totalBytes: Long = 0,
    val transferredBytes: Long = 0,
    val currentSpeedBytesPerSec: Long = 0,
    val activeTransfers: List<FileTransferProgress> = emptyList(),
) {
    val progressPercent: Float
        get() = if (totalBytes > 0) transferredBytes.toFloat() / totalBytes else 0f

    val estimatedSecondsRemaining: Long
        get() = if (currentSpeedBytesPerSec > 0) {
            (totalBytes - transferredBytes) / currentSpeedBytesPerSec
        } else 0
}

@Singleton
class UploadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val uploadRecordDao: UploadRecordDao,
    private val connectionManager: ConnectionManager,
    private val appPreferences: AppPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val contentResolver: ContentResolver = context.contentResolver

    private val _transferState = MutableStateFlow(TransferState())
    val transferState: StateFlow<TransferState> = _transferState.asStateFlow()

    private var speedTracker = SpeedTracker()

    /**
     * Entry point from the UI. Queues the selected items and hands the actual uploading off to
     * [UploadWorker], a foreground service that survives the screen turning off / the app being
     * backgrounded and shows a progress notification.
     */
    fun start(items: List<MediaItem>) {
        if (items.isEmpty()) return

        // Flip state immediately so the UI reflects "preparing" the moment the user taps, before
        // the (now batched) DB work and worker hand-off complete. If a transfer is already running,
        // leave its live progress alone — the worker owns the state — and just append the new work.
        if (!_transferState.value.isTransferring) {
            _transferState.value = TransferState(isTransferring = true, totalFiles = items.size)
        }

        scope.launch {
            enqueueRecords(items)
            scheduleWork(ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
    }

    /** Re-arm the worker on app launch if a transfer was interrupted (process death, reboot). */
    fun resumeIfPending() {
        scope.launch {
            if (uploadRecordDao.getPendingAndUploading().isNotEmpty()) {
                scheduleWork(ExistingWorkPolicy.KEEP)
            }
        }
    }

    fun cancelTransfer() {
        WorkManager.getInstance(context).cancelUniqueWork(UploadWorker.WORK_NAME)
        _transferState.update {
            it.copy(isTransferring = false, activeTransfers = emptyList())
        }
        scope.launch {
            uploadRecordDao.resetUploadingToPending()
        }
    }

    /**
     * Persist the selection as PENDING upload records. Uses set-based dedup + a single batched
     * insert so even a 20k+ selection is one transaction rather than tens of thousands.
     */
    private suspend fun enqueueRecords(items: List<MediaItem>) {
        uploadRecordDao.resetUploadingToPending()

        val completedIds = uploadRecordDao.getCompletedMediaStoreIds().toHashSet()
        val alreadyQueuedIds = uploadRecordDao.getPendingAndUploading()
            .mapTo(HashSet()) { it.mediaStoreId }

        val serverIp = connectionManager.connectedIp.value
        val newRecords = items.asSequence()
            .filter { it.id !in completedIds && it.id !in alreadyQueuedIds }
            .map { item ->
                UploadRecord(
                    mediaStoreId = item.id,
                    contentUri = item.uri.toString(),
                    fileName = item.displayName,
                    bucketName = item.bucketName,
                    fileSize = item.size,
                    serverIp = serverIp,
                )
            }
            .toList()

        uploadRecordDao.insertAll(newRecords)
    }

    private fun scheduleWork(policy: ExistingWorkPolicy) {
        // LAN transfer to a local server, so require WiFi (unmetered); WorkManager pauses the
        // job when it drops and resumes when it returns.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UploadWorker.WORK_NAME, policy, request)
    }

    /**
     * Drains the pending queue. Called by [UploadWorker] from within its foreground coroutine, so
     * cancelling the work cancels these uploads (each in-flight file resets to PENDING).
     */
    suspend fun runQueue() {
        uploadRecordDao.resetUploadingToPending()
        val batch = uploadRecordDao.getPendingAndUploading()

        if (batch.isEmpty()) {
            _transferState.update { it.copy(isTransferring = false, activeTransfers = emptyList()) }
            return
        }

        speedTracker = SpeedTracker()
        _transferState.value = TransferState(
            isTransferring = true,
            totalFiles = batch.size,
            totalBytes = batch.sumOf { it.fileSize },
        )

        val semaphore = Semaphore(parallelSlots())
        coroutineScope {
            batch.map { record ->
                launch {
                    semaphore.withPermit {
                        uploadFile(record)
                    }
                }
            }.forEach { it.join() }
        }

        _transferState.update { it.copy(isTransferring = false, activeTransfers = emptyList()) }
    }

    private suspend fun uploadFile(record: UploadRecord) {
        // Update status to UPLOADING
        uploadRecordDao.update(record.copy(status = UploadStatus.UPLOADING))
        updateActiveTransfer(record.id, record.fileName, record.contentUri, record.fileSize, 0, UploadStatus.UPLOADING)

        try {
            val uri = Uri.parse(record.contentUri)

            // Compute MD5
            val md5 = computeMd5(uri)

            // Check for dedup with hash
            val existingWithHash = uploadRecordDao.getCompletedByMediaStoreId(record.mediaStoreId)
            if (existingWithHash != null && existingWithHash.md5Hash == md5) {
                uploadRecordDao.update(record.copy(status = UploadStatus.COMPLETED, md5Hash = md5, uploadedAt = System.currentTimeMillis()))
                _transferState.update { st ->
                    st.copy(
                        completedFiles = st.completedFiles + 1,
                        transferredBytes = (st.transferredBytes + record.fileSize).coerceAtMost(st.totalBytes),
                    )
                }
                removeActiveTransfer(record.id)
                return
            }

            // Build multipart request. Stream the file straight from the content URI
            // instead of reading it fully into memory — a 48 GB selection with several
            // parallel slots would otherwise hold multiple whole videos in RAM and OOM.
            val baseUrl = baseUrlFor(record)
            val sourceTimestampMs = resolveSourceTimestampMillis(uri)
            val progressBody = ContentUriRequestBody(
                contentResolver = contentResolver,
                uri = uri,
                contentType = (record.fileName.toMediaTypeOrNull() ?: "application/octet-stream").toMediaType(),
                declaredLength = resolveContentLength(uri, record.fileSize),
                onProgress = { bytesWritten ->
                    val delta = updateActiveTransfer(
                        id = record.id,
                        name = record.fileName,
                        contentUri = record.contentUri,
                        size = record.fileSize,
                        transferred = bytesWritten,
                        status = UploadStatus.UPLOADING,
                    )
                    speedTracker.recordBytes(delta)
                    _transferState.update { st -> st.copy(currentSpeedBytesPerSec = speedTracker.getSpeed()) }
                }
            )

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                // Put metadata before the file part so multer has bucketName during destination resolution.
                .addFormDataPart("bucketName", record.bucketName)
                .addFormDataPart("fileName", record.fileName)
                .addFormDataPart("fileSize", record.fileSize.toString())
                .addFormDataPart("md5Hash", md5)
                .addFormDataPart("mimeType", guessMimeType(record.fileName))
                .addFormDataPart("sourceTimestampMs", sourceTimestampMs.toString())
                .addFormDataPart("file", record.fileName, progressBody)
                .build()

            val request = Request.Builder()
                .url("$baseUrl/upload")
                .post(requestBody)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    uploadRecordDao.update(
                        record.copy(
                            status = UploadStatus.COMPLETED,
                            md5Hash = md5,
                            progress = 100,
                            uploadedAt = System.currentTimeMillis()
                        )
                    )
                    _transferState.update { st ->
                        val activeTransferred = st.activeTransfers.find { it.recordId == record.id }?.bytesTransferred ?: 0L
                        val finalDelta = (record.fileSize - activeTransferred).coerceAtLeast(0)
                        st.copy(
                            completedFiles = st.completedFiles + 1,
                            transferredBytes = (st.transferredBytes + finalDelta).coerceAtMost(st.totalBytes),
                        )
                    }
                } else {
                    val body = response.body?.string()?.take(180) ?: ""
                    throw Exception("Server returned ${response.code}${if (body.isNotBlank()) ": $body" else ""}")
                }
            }
        } catch (e: CancellationException) {
            uploadRecordDao.update(record.copy(status = UploadStatus.PENDING))
            throw e
        } catch (e: IOException) {
            // Network/read timeouts can happen after the server already persisted the file.
            // Keep this retryable instead of marking it as a hard failure.
            uploadRecordDao.update(record.copy(status = UploadStatus.PENDING))
        } catch (e: Exception) {
            uploadRecordDao.update(record.copy(status = UploadStatus.FAILED))
            _transferState.update { st ->
                st.copy(failedFiles = st.failedFiles + 1)
            }
        } finally {
            removeActiveTransfer(record.id)
        }
    }

    // Prefer the IP captured when the record was queued so a transfer can resume after process
    // death (when the live connection state is gone); fall back to the current connection.
    private fun baseUrlFor(record: UploadRecord): String =
        record.serverIp.takeIf { it.isNotBlank() }?.let { "http://$it:$SERVER_PORT" }
            ?: connectionManager.getBaseUrl()

    private fun resolveContentLength(uri: Uri, fallback: Long): Long {
        val length = runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()
        return if (length != null && length != AssetFileDescriptor.UNKNOWN_LENGTH) length else fallback
    }

    private fun computeMd5(uri: Uri): String {
        val digest = MessageDigest.getInstance("MD5")
        contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun updateActiveTransfer(
        id: Long,
        name: String,
        contentUri: String,
        size: Long,
        transferred: Long,
        status: UploadStatus,
    ): Long {
        var delta = 0L
        _transferState.update { st ->
            val active = st.activeTransfers.toMutableList()
            val idx = active.indexOfFirst { it.recordId == id }
            val previousBytes = if (idx >= 0) active[idx].bytesTransferred else 0L
            delta = (transferred - previousBytes).coerceAtLeast(0)
            val item = FileTransferProgress(id, name, contentUri, size, transferred, status)
            if (idx >= 0) active[idx] = item else active.add(item)
            st.copy(
                activeTransfers = active,
                transferredBytes = (st.transferredBytes + delta).coerceAtMost(st.totalBytes),
            )
        }
        return delta
    }

    private fun removeActiveTransfer(id: Long) {
        _transferState.update { st ->
            st.copy(activeTransfers = st.activeTransfers.filter { it.recordId != id })
        }
    }

    private fun guessMimeType(fileName: String): String {
        return when {
            fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) -> "image/jpeg"
            fileName.endsWith(".png", true) -> "image/png"
            fileName.endsWith(".gif", true) -> "image/gif"
            fileName.endsWith(".webp", true) -> "image/webp"
            fileName.endsWith(".heic", true) || fileName.endsWith(".heif", true) -> "image/heic"
            fileName.endsWith(".mp4", true) -> "video/mp4"
            fileName.endsWith(".mov", true) -> "video/quicktime"
            fileName.endsWith(".avi", true) -> "video/x-msvideo"
            fileName.endsWith(".mkv", true) -> "video/x-matroska"
            fileName.endsWith(".raw", true) || fileName.endsWith(".dng", true) -> "image/x-raw"
            else -> "application/octet-stream"
        }
    }

    private fun String.toMediaTypeOrNull(): String? {
        return guessMimeType(this).takeIf { it != "application/octet-stream" }
    }

    private fun resolveSourceTimestampMillis(uri: Uri): Long {
        return runCatching {
            contentResolver.query(
                uri,
                arrayOf("datetaken", MediaStore.MediaColumns.DATE_MODIFIED),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val takenCol = cursor.getColumnIndex("datetaken")
                    if (takenCol >= 0) {
                        val taken = cursor.getLong(takenCol)
                        if (taken > 0L) return@runCatching taken
                    }

                    val modifiedCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    if (modifiedCol >= 0) {
                        val modified = cursor.getLong(modifiedCol)
                        if (modified > 0L) {
                            return@runCatching if (modified < 100_000_000_000L) modified * 1000L else modified
                        }
                    }
                }
                0L
            } ?: 0L
        }.getOrDefault(0L)
    }

    private suspend fun parallelSlots(): Int {
        return if (appPreferences.highSpeedTransferEnabled.first()) 6 else 3
    }
}

private class ContentUriRequestBody(
    private val contentResolver: ContentResolver,
    private val uri: Uri,
    private val contentType: okhttp3.MediaType,
    private val declaredLength: Long,
    private val onProgress: (Long) -> Unit,
) : RequestBody() {
    override fun contentType() = contentType
    override fun contentLength() = declaredLength

    override fun writeTo(sink: BufferedSink) {
        // Re-open per writeTo so OkHttp can retry the body if needed. Streams in
        // fixed-size chunks, keeping memory flat regardless of file size.
        val input = contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open $uri")
        input.source().use { source ->
            var totalWritten = 0L
            var read: Long
            while (source.read(sink.buffer, 8192).also { read = it } != -1L) {
                sink.flush()
                totalWritten += read
                onProgress(totalWritten)
            }
        }
    }
}

private class SpeedTracker {
    private val windowMs = 1200L
    private val samples = ArrayDeque<Sample>()
    private var bytesInWindow = 0L

    private data class Sample(
        val timeMs: Long,
        val bytes: Long,
    )

    fun recordBytes(bytes: Long) {
        if (bytes <= 0L) return
        synchronized(this) {
            val now = System.currentTimeMillis()
            evictOldLocked(now)
            samples.addLast(Sample(timeMs = now, bytes = bytes))
            bytesInWindow += bytes
        }
    }

    fun getSpeed(): Long {
        synchronized(this) {
            val now = System.currentTimeMillis()
            evictOldLocked(now)
            if (samples.isEmpty()) return 0L

            val elapsed = (now - samples.first().timeMs).coerceAtLeast(1L)
            return bytesInWindow * 1000 / elapsed
        }
    }

    private fun evictOldLocked(now: Long) {
        val cutoff = now - windowMs
        while (samples.isNotEmpty() && samples.first().timeMs < cutoff) {
            bytesInWindow -= samples.removeFirst().bytes
        }
        if (bytesInWindow < 0L) {
            bytesInWindow = 0L
        }
    }
}
