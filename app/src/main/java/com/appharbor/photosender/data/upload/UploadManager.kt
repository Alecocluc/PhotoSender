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
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val SERVER_PORT = 3210

// One resend allowed on an MD5 mismatch (422) before the file is treated as a hard failure.
private const val MAX_UPLOAD_ATTEMPTS = 2
private const val MAX_SKIPPED_BATCH = 100

/** Outcome of a single POST /upload attempt. */
private sealed interface UploadAttempt {
    object Success : UploadAttempt
    object RetryHashMismatch : UploadAttempt
    data class Failed(val message: String) : UploadAttempt
}

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
    // Subset of completedFiles whose bytes were already on the PC, so nothing was sent (content
    // dedup). Surfaced so a count lower than the selection never looks like data loss.
    val skippedFiles: Int = 0,
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

/** Progress of a reconcile pass that checks every completed record is really on the server. */
data class VerifyState(
    val isVerifying: Boolean = false,
    val checked: Int = 0,
    val total: Int = 0,
    val missing: Int = 0,
    /** Human-readable summary once a pass finishes; null while idle or running. */
    val summary: String? = null,
)

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

    private val _verifyState = MutableStateFlow(VerifyState())
    val verifyState: StateFlow<VerifyState> = _verifyState.asStateFlow()

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

    /** Explicitly re-queue every hard-failed record and kick the worker. */
    fun retryFailed() {
        scope.launch {
            val revived = uploadRecordDao.resetFailedToPending()
            if (revived > 0 && uploadRecordDao.getPendingAndUploading().isNotEmpty()) {
                if (!_transferState.value.isTransferring) {
                    _transferState.value = TransferState(isTransferring = true)
                }
                scheduleWork(ExistingWorkPolicy.APPEND_OR_REPLACE)
            }
        }
    }

    /**
     * Reconcile: ask the server (via the dedup [/exists] check) whether the content of every
     * record we believe is COMPLETED is actually present. Anything missing is reset to PENDING and
     * re-queued, so the pass doubles as a recovery tool. Requires an active connection.
     */
    fun verifyAgainstServer() {
        if (_verifyState.value.isVerifying) return
        scope.launch {
            val baseUrl = connectionManager.getBaseUrl()
            if (connectionManager.connectedIp.value.isBlank()) {
                _verifyState.value = VerifyState(summary = "Connect to the desktop first to verify.")
                return@launch
            }

            val completed = uploadRecordDao.getCompletedSnapshot()
            _verifyState.value = VerifyState(isVerifying = true, total = completed.size)

            val checked = java.util.concurrent.atomic.AtomicInteger(0)
            val missingRecords = java.util.concurrent.ConcurrentHashMap.newKeySet<UploadRecord>()
            val semaphore = Semaphore(parallelSlots())
            coroutineScope {
                completed.map { record ->
                    launch {
                        semaphore.withPermit {
                            // No stored hash (e.g. legacy record) → recompute so we can still check.
                            val md5 = record.md5Hash.ifBlank {
                                runCatching { computeMd5(Uri.parse(record.contentUri)) }.getOrDefault("")
                            }
                            if (md5.isNotBlank() && !serverHasFile(baseUrl, md5)) {
                                missingRecords.add(record)
                            }
                            val done = checked.incrementAndGet()
                            _verifyState.update {
                                it.copy(checked = done, missing = missingRecords.size)
                            }
                        }
                    }
                }.forEach { it.join() }
            }

            // Re-queue anything the server turned out not to have, then re-arm the worker.
            for (record in missingRecords) {
                uploadRecordDao.update(record.copy(status = UploadStatus.PENDING, progress = 0))
            }
            val missingCount = missingRecords.size
            if (missingCount > 0) {
                scheduleWork(ExistingWorkPolicy.APPEND_OR_REPLACE)
            }
            _verifyState.value = VerifyState(
                isVerifying = false,
                checked = completed.size,
                total = completed.size,
                missing = missingCount,
                summary = if (missingCount == 0) {
                    "All ${completed.size} files confirmed on the desktop."
                } else {
                    "$missingCount of ${completed.size} were missing — re-queued for upload."
                },
            )
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
        // Re-arm prior hard failures so any new transfer also retries them. This both gives
        // failed files another chance and prevents orphaned FAILED rows when a failed item is
        // re-selected (it's revived in place rather than inserted as a duplicate row).
        uploadRecordDao.resetFailedToPending()

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
        // Resolve the destination first. Prefer the IP captured when the record was queued so a
        // transfer can resume after process death; fall back to the live connection and persist
        // that IP. If nothing is reachable, keep the file PENDING (retryable) rather than burning
        // a hard failure — this is what previously stranded files queued while disconnected.
        val serverIp = record.serverIp.takeIf { it.isNotBlank() } ?: connectionManager.connectedIp.value
        if (serverIp.isBlank()) {
            uploadRecordDao.update(record.copy(status = UploadStatus.PENDING))
            return
        }
        val baseUrl = "http://$serverIp:$SERVER_PORT"

        // Mark UPLOADING and persist the resolved IP so a later resume reaches the same server.
        val working = record.copy(status = UploadStatus.UPLOADING, serverIp = serverIp)
        uploadRecordDao.update(working)
        updateActiveTransfer(working.id, working.fileName, working.contentUri, working.fileSize, 0, UploadStatus.UPLOADING)

        try {
            val uri = Uri.parse(working.contentUri)

            // Compute MD5
            var md5 = computeMd5(uri)

            // Dedup: skip the upload entirely if the content already exists — locally (same media
            // re-selected) or on the server (covers a client reinstall / cleared local DB).
            val locallyDone = uploadRecordDao.getCompletedByMediaStoreId(working.mediaStoreId)
                ?.md5Hash == md5
            if (locallyDone || serverHasFile(baseUrl, md5)) {
                completeWithoutUpload(working, md5)
                return
            }

            val sourceTimestampMs = resolveSourceTimestampMillis(uri)

            var attempt = 0
            while (true) {
                attempt++

                // Build multipart request. Stream the file straight from the content URI
                // instead of reading it fully into memory — a 48 GB selection with several
                // parallel slots would otherwise hold multiple whole videos in RAM and OOM.
                val progressBody = ContentUriRequestBody(
                    contentResolver = contentResolver,
                    uri = uri,
                    contentType = (working.fileName.toMediaTypeOrNull() ?: "application/octet-stream").toMediaType(),
                    declaredLength = resolveContentLength(uri, working.fileSize),
                    onProgress = { bytesWritten ->
                        val delta = updateActiveTransfer(
                            id = working.id,
                            name = working.fileName,
                            contentUri = working.contentUri,
                            size = working.fileSize,
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
                    .addFormDataPart("bucketName", working.bucketName)
                    .addFormDataPart("fileName", working.fileName)
                    .addFormDataPart("fileSize", working.fileSize.toString())
                    .addFormDataPart("md5Hash", md5)
                    .addFormDataPart("mimeType", guessMimeType(working.fileName))
                    .addFormDataPart("sourceTimestampMs", sourceTimestampMs.toString())
                    .addFormDataPart("file", working.fileName, progressBody)
                    .build()

                val request = Request.Builder()
                    .url("$baseUrl/upload")
                    .post(requestBody)
                    .build()

                val attemptResult = okHttpClient.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> UploadAttempt.Success
                        // A 422 is an MD5 mismatch — usually the file changed under us mid-read.
                        // Recompute the hash and resend once before giving up.
                        response.code == 422 && attempt < MAX_UPLOAD_ATTEMPTS -> UploadAttempt.RetryHashMismatch
                        else -> {
                            val body = response.body?.string()?.take(180) ?: ""
                            UploadAttempt.Failed(
                                "Server returned ${response.code}${if (body.isNotBlank()) ": $body" else ""}"
                            )
                        }
                    }
                }

                when (attemptResult) {
                    UploadAttempt.Success -> {
                        uploadRecordDao.update(
                            working.copy(
                                status = UploadStatus.COMPLETED,
                                md5Hash = md5,
                                progress = 100,
                                uploadedAt = System.currentTimeMillis()
                            )
                        )
                        _transferState.update { st ->
                            val activeTransferred = st.activeTransfers.find { it.recordId == working.id }?.bytesTransferred ?: 0L
                            val finalDelta = (working.fileSize - activeTransferred).coerceAtLeast(0)
                            st.copy(
                                completedFiles = st.completedFiles + 1,
                                transferredBytes = (st.transferredBytes + finalDelta).coerceAtMost(st.totalBytes),
                            )
                        }
                        return
                    }
                    UploadAttempt.RetryHashMismatch -> {
                        md5 = computeMd5(uri)
                        // loop and resend with the fresh hash
                    }
                    is UploadAttempt.Failed -> throw Exception(attemptResult.message)
                }
            }
        } catch (e: CancellationException) {
            uploadRecordDao.update(working.copy(status = UploadStatus.PENDING))
            throw e
        } catch (e: IOException) {
            // Network/read timeouts can happen after the server already persisted the file.
            // Keep this retryable instead of marking it as a hard failure.
            uploadRecordDao.update(working.copy(status = UploadStatus.PENDING))
        } catch (e: Exception) {
            uploadRecordDao.update(working.copy(status = UploadStatus.FAILED))
            _transferState.update { st ->
                st.copy(failedFiles = st.failedFiles + 1)
            }
        } finally {
            removeActiveTransfer(working.id)
        }
    }

    // Ask the server whether it already stores this content. Failures default to false so we
    // never skip an upload on a flaky check.
    private fun serverHasFile(baseUrl: String, md5: String): Boolean = runCatching {
        val request = Request.Builder().url("$baseUrl/exists?md5=$md5").build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@runCatching false
            JSONObject(response.body?.string().orEmpty()).optBoolean("exists", false)
        }
    }.getOrDefault(false)

    // Mark a record done without transferring bytes (used by both local and server dedup).
    private suspend fun completeWithoutUpload(record: UploadRecord, md5: String) {
        uploadRecordDao.update(
            record.copy(
                status = UploadStatus.COMPLETED,
                md5Hash = md5,
                progress = 100,
                uploadedAt = System.currentTimeMillis(),
            )
        )
        _transferState.update { st ->
            st.copy(
                completedFiles = st.completedFiles + 1,
                // Bytes weren't sent (content already on the PC) — count it as skipped so the UI
                // can explain why the transferred count is below the selection.
                skippedFiles = st.skippedFiles + 1,
                transferredBytes = (st.transferredBytes + record.fileSize).coerceAtMost(st.totalBytes),
            )
        }
        removeActiveTransfer(record.id)
    }

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
