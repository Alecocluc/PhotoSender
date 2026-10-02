package com.appharbor.pherry.data.upload

import com.appharbor.pherry.data.db.UploadJob
import com.appharbor.pherry.data.media.MediaRepository
import com.appharbor.pherry.data.model.sourceVersion
import com.appharbor.pherry.data.network.ReceiverIdentity
import com.appharbor.pherry.data.network.TransferApi
import com.appharbor.pherry.data.network.TransferHttpException
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.appharbor.pherry.data.db.UploadRecord
import com.appharbor.pherry.data.db.UploadRecordDao
import com.appharbor.pherry.data.db.UploadStatus
import com.appharbor.pherry.data.model.MediaItem
import com.appharbor.pherry.data.network.ConnectionManager
import com.appharbor.pherry.data.preferences.AppPreferences
import com.appharbor.pherry.ui.components.Fmt
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

// One resend allowed on an MD5 mismatch (422) before the file is treated as a hard failure.
private const val MAX_UPLOAD_ATTEMPTS = 2
private const val MAX_SKIPPED_BATCH = 100
// Sync holds deletes back when at least this many backed-up phone files vanish at once and they are
// more than half of them: that reads as a scan that can't see the library (SD card out), not a cleanup.
private const val SUSPICIOUS_MISSING_MIN = 20

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

data class DedupSkip(
    val skippedRecordId: Long,
    val skippedFileName: String,
    val skippedBucketName: String,
    val matchedFileName: String?,
    val matchedBucketName: String?,
    val fileSize: Long,
    val md5Hash: String,
    val matchedOnPhone: Boolean,
)

data class TransferState(
    val sessionId: String = "",
    val phase: TransferPhase = TransferPhase.IDLE,
    val message: String? = null,
    val pendingFiles: Int = 0,
    val resumedBytes: Long = 0,
    val destinationName: String = "",
    val enoughSpace: Boolean? = null,
    val isTransferring: Boolean = false,
    val totalFiles: Int = 0,
    val completedFiles: Int = 0,
    val failedFiles: Int = 0,
    // Subset of completedFiles whose bytes were already on the PC, so nothing was sent (content
    // dedup). Surfaced so a count lower than the selection never looks like data loss.
    val skippedFiles: Int = 0,
    val totalBytes: Long = 0,
    val transferredBytes: Long = 0,
    // Bytes actually sent over the wire this batch: real uploads only, no dedup skips.
    val sentBytes: Long = 0,
    val savedBytes: Long = 0,
    val currentSpeedBytesPerSec: Long = 0,
    val activeTransfers: List<FileTransferProgress> = emptyList(),
    val skippedDuplicates: List<DedupSkip> = emptyList(),
) {
    val progressPercent: Float
        get() = if (totalBytes > 0) transferredBytes.toFloat() / totalBytes else 0f

    val estimatedSecondsRemaining: Long
        get() = if (currentSpeedBytesPerSec > 0) {
            (totalBytes - transferredBytes) / currentSpeedBytesPerSec
        } else 0
}

private data class ServerFileMatch(
    val exists: Boolean = false,
    val fileName: String? = null,
    val bucketName: String? = null,
    val size: Long = 0,
)

/** Progress of a reconcile pass that checks every completed record is really on the server. */
data class VerifyState(
    val isVerifying: Boolean = false,
    val checked: Int = 0,
    val total: Int = 0,
    /** Records the server answered "not here" for; only these are re-queued. */
    val missing: Int = 0,
    /** Records the server gave no usable answer for (network/HTTP error); neither present nor missing. */
    val unknown: Int = 0,
    /** Records with no stored hash whose file is gone from the phone, so they couldn't be asked about. */
    val unreadable: Int = 0,
    /** The pass stopped early because the server stopped answering. */
    val interrupted: Boolean = false,
    /** The pass couldn't start because there was no computer to ask. */
    val unreachable: Boolean = false,
    /** Human-readable summary once a pass finishes; null while idle or running. */
    val summary: String? = null,
)

/** What the server said about one hash during a verify pass. */
private enum class Presence { PRESENT, ABSENT, UNKNOWN }

/** A verify pass gives up after this many unanswered checks in a row (the computer went away). */
private const val VERIFY_MAX_UNKNOWN_IN_A_ROW = 3

/** A file the server should delete in Sync mode, identified by its content hash. */
data class SyncDeleteEntry(
    val md5: String,
    val bucketName: String,
    val fileName: String,
)

/**
 * The diff between the phone library and what we've uploaded, for Sync mode. Computed from cheap
 * metadata only (a MediaStore scan + the local DB) — no hashing.
 */
data class SyncPlan(
    val uploadItems: List<MediaItem>,
    val uploadBytes: Long,
    val deleteEntries: List<SyncDeleteEntry>,
    /** Every completed record whose media is gone from the phone — removed from the DB after sync. */
    val deletedRecordIds: List<Long>,
    /**
     * True when deletions were held back because the scan can't be trusted to show what's gone:
     * partial media access, an empty scan, or most backed-up photos suddenly missing (an SD card
     * out). Nothing is proposed for deletion then.
     */
    val deletesWithheld: Boolean = false,
    val receiverId: String = "",
    val libraryId: String = "",
) {
    val uploadCount: Int get() = uploadItems.size
    val deleteCount: Int get() = deleteEntries.size
    val isNoOp: Boolean get() = uploadItems.isEmpty() && deletedRecordIds.isEmpty()
}

/** Result of the (one-shot) desktop-deletion half of a sync; surfaced to the UI via a snackbar. */
data class SyncState(
    val isSyncing: Boolean = false,
    val deleted: Int = 0,
    /** Human-readable summary once a sync finishes; null while idle or running. */
    val summary: String? = null,
)

private data class SyncDeleteResult(
    val ok: Boolean,
    val deleted: Int = 0,
    val bytesFreed: Long = 0,
    val notFound: Int = 0,
    /** Server rejected the delete because we lack the pairing token (scan the pairing ticket again). */
    val unauthorized: Boolean = false,
)

@Singleton
class UploadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val uploadRecordDao: UploadRecordDao,
    private val connectionManager: ConnectionManager,
    private val appPreferences: AppPreferences,
    private val mediaRepository: MediaRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val contentResolver = context.contentResolver
    private val enqueueMutex = Mutex()
    private val runMutex = Mutex()
    private val _transferState = MutableStateFlow(TransferState())
    val transferState = _transferState.asStateFlow()
    private val _verifyState = MutableStateFlow(VerifyState())
    val verifyState = _verifyState.asStateFlow()
    private val _syncState = MutableStateFlow(SyncState())
    val syncState = _syncState.asStateFlow()
    private val pauseRequested = AtomicBoolean(false)
    @Volatile private var activeRun: Job? = null

    fun start(items: List<MediaItem>) {
        if (items.isEmpty()) return
        if (!_transferState.value.isTransferring) _transferState.value = TransferState(
            phase = TransferPhase.PREPARING, isTransferring = true, totalFiles = items.size, pendingFiles = items.size)
        scope.launch {
            try { enqueueAndSchedule(items, userInitiated = true) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { setPhase(TransferPhase.FAILED, e.message ?: "Could not prepare the backup") }
        }
    }

    /** Persists the whole job atomically before returning to a share intent or background caller. */
    suspend fun enqueueAndSchedule(items: List<MediaItem>, userInitiated: Boolean = false) {
        if (items.isEmpty()) return
        val identity = connectionManager.selectedIdentity() ?: throw IllegalStateException("Pair a computer before sending files")
        val enqueuedJobId = enqueueMutex.withLock {
            val existing = uploadRecordDao.recordsForDestination(identity.deviceId, identity.libraryId)
            val known = existing.filter { it.status != UploadStatus.FAILED }.mapNotNullTo(HashSet()) { it.dedupKey }
            val jobId = UUID.randomUUID().toString()
            val records = items.distinctBy { it.uri.toString() to it.sourceVersion }.mapNotNull { item ->
                val key = ReceiptPolicy.key(identity.deviceId, identity.libraryId, item.uri.toString(), item.sourceVersion)
                if (key in known) null else UploadRecord(mediaStoreId = item.id, contentUri = item.uri.toString(),
                    fileName = item.displayName, bucketName = item.bucketName, fileSize = item.size,
                    serverIp = connectionManager.getConnectedEndpoint(), receiverId = identity.deviceId,
                    libraryId = identity.libraryId, sourceVersion = item.sourceVersion, dedupKey = key,
                    jobId = jobId, uploadId = UUID.randomUUID().toString(), hashAlgorithm = "sha256")
            }
            val selectedKeys = items.mapTo(HashSet()) { ReceiptPolicy.key(identity.deviceId, identity.libraryId, it.uri.toString(), it.sourceVersion) }
            val retries = existing.filter { it.status == UploadStatus.FAILED && it.dedupKey in selectedKeys }
            val retryKeys = retries.mapTo(HashSet()) { it.dedupKey }
            val newRecords = records.filter { it.dedupKey !in retryKeys }
            if (newRecords.isNotEmpty() || retries.isNotEmpty()) {
                if (newRecords.isNotEmpty()) uploadRecordDao.enqueueJob(UploadJob(jobId, identity.deviceId, identity.libraryId, System.currentTimeMillis()), newRecords)
                retries.forEach {
                    uploadRecordDao.update(it.copy(status = UploadStatus.PENDING, error = ""))
                    uploadRecordDao.job(it.jobId)?.let { job -> uploadRecordDao.putJob(job.copy(state = "waiting")) }
                }
                if (newRecords.isNotEmpty()) jobId else retries.first().jobId
            } else null
        }
        if (userInitiated) {
            pauseRequested.set(false)
            appPreferences.setUserPaused(false)
            uploadRecordDao.pauseOpenJobs(false)
        }
        if (enqueuedJobId == null) {
            val queued = uploadRecordDao.recordsForDestination(identity.deviceId, identity.libraryId)
                .any { it.status == UploadStatus.PENDING || it.status == UploadStatus.UPLOADING }
            if (!runMutex.isLocked) {
                if (queued) restoreLatestState()
                else _transferState.value = TransferState(phase = TransferPhase.COMPLETE, totalFiles = items.size,
                    completedFiles = items.size, skippedFiles = items.size, totalBytes = items.sumOf { it.size },
                    transferredBytes = items.sumOf { it.size }, message = "These versions are already saved on this computer")
            }
            if (queued) scheduleWork(userInitiated, ExistingWorkPolicy.APPEND_OR_REPLACE)
            return
        }
        // Publish durable new intent before Android evaluates constraints. A queued job must never
        // continue showing the previous job's Finished receipt while waiting for Wi-Fi.
        if (!runMutex.isLocked) {
            val queued = uploadRecordDao.recordsForJob(enqueuedJobId)
            _transferState.value = ProgressLedger(queued).snapshot(TransferState(sessionId = enqueuedJobId,
                phase = TransferPhase.WAITING_FOR_NETWORK, message = "Queued. Waiting for the permitted network",
                destinationName = connectionManager.serverName.value.ifBlank { connectionManager.rememberedComputer.value?.name.orEmpty() }))
        }
        scheduleWork(userInitiated, ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    fun resumeIfPending() {
        scope.launch {
            restoreLatestState()
            val pending = uploadRecordDao.getPendingAndUploading().isNotEmpty()
            val receiptsToConfirm = uploadRecordDao.openJobs().any { !it.userPaused && it.state != "failed" }
            if (!appPreferences.userPaused.first() && (pending || receiptsToConfirm)) scheduleWork(false, ExistingWorkPolicy.KEEP)
        }
    }

    fun resumeTransfer() {
        scope.launch {
            pauseRequested.set(false)
            appPreferences.setUserPaused(false)
            uploadRecordDao.pauseOpenJobs(false)
            setPhase(TransferPhase.PREPARING, "Resuming your backup")
            scheduleWork(true, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
    }

    fun cancelTransfer() {
        pauseRequested.set(true)
        setPhase(TransferPhase.PAUSED, "Paused. Your place is saved")
        scope.launch { pauseTransferAndWait() }
    }

    suspend fun pauseTransferAndWait() {
        pauseRequested.set(true)
        setPhase(TransferPhase.PAUSED, "Paused. Your place is saved")
        appPreferences.setUserPaused(true)
        uploadRecordDao.pauseOpenJobs(true)
        activeRun?.cancel()
        WorkManager.getInstance(context).cancelUniqueWork(UploadWorker.WORK_NAME)
        UserTransferJobService.cancel(context)
    }

    fun retryFailed() {
        scope.launch {
            val identity = connectionManager.selectedIdentity() ?: return@launch
            uploadRecordDao.recordsForDestination(identity.deviceId, identity.libraryId).filter { it.status == UploadStatus.FAILED }.forEach {
                uploadRecordDao.update(it.copy(status = UploadStatus.PENDING, error = ""))
            }
            resumeTransfer()
        }
    }

    private suspend fun scheduleWork(userInitiated: Boolean, policy: ExistingWorkPolicy) {
        val wifiOnly = appPreferences.wifiOnlyTransfer.first()
        if (appPreferences.userPaused.first()) return
        if (userInitiated && UserTransferJobService.schedule(context, wifiOnly,
                (_transferState.value.totalBytes - _transferState.value.transferredBytes).coerceAtLeast(0))) return
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(UploadWorker.WORK_NAME, policy, request)
        if (!runMutex.isLocked) setPhase(TransferPhase.WAITING_FOR_NETWORK, "Queued. Waiting for the permitted network")
    }

    suspend fun runQueue(): QueueOutcome = runMutex.withLock {
        activeRun = currentCoroutineContext()[Job]
        try {
            if (pauseRequested.get() || appPreferences.userPaused.first()) { restoreLatestState(); return@withLock QueueOutcome.PAUSED }
            val selected = connectionManager.selectedIdentity() ?: run {
                setPhase(TransferPhase.WAITING_FOR_COMPUTER, "Pair your computer to continue")
                return@withLock QueueOutcome.RETRY
            }
            setPhase(TransferPhase.WAITING_FOR_COMPUTER, "Checking your paired computer")
            val connection = connectionManager.connectionFor(selected) ?: run {
                setPhase(TransferPhase.WAITING_FOR_COMPUTER, "Waiting for your paired computer. Your queue is saved")
                return@withLock QueueOutcome.RETRY
            }
            adoptLegacyQueue(selected)
            uploadRecordDao.resetUploadingToPending()
            val jobs = uploadRecordDao.openJobs().filter { it.receiverId == selected.deviceId && it.libraryId == selected.libraryId && !it.userPaused }
            var outcome = QueueOutcome.COMPLETE
            for (job in jobs) {
                currentCoroutineContext().ensureActive()
                if (pauseRequested.get()) return@withLock QueueOutcome.PAUSED
                val records = uploadRecordDao.recordsForJob(job.id)
                if (records.none { it.status == UploadStatus.PENDING }) {
                    // The bytes may be durable while the final job response was lost. Reconcile the
                    // same receipt instead of declaring success locally and forgetting to notify the PC.
                    val state = if (records.any { it.status == UploadStatus.FAILED }) "failed" else "completed"
                    _transferState.value = ProgressLedger(records).snapshot(TransferState(sessionId = job.id))
                    val acknowledged = reportJob(TransferApi(okHttpClient, connection), job.id, state)
                    if (!acknowledged) {
                        setPhase(TransferPhase.WAITING_FOR_COMPUTER, "Files saved. Waiting for the computer to confirm the job receipt")
                        return@withLock QueueOutcome.RETRY
                    }
                    uploadRecordDao.putJob(job.copy(state = state))
                    setPhase(if (state == "completed") TransferPhase.COMPLETE else TransferPhase.FAILED, null)
                    continue
                }
                val next = runJob(job, records, TransferApi(okHttpClient, connection))
                if (next == QueueOutcome.RETRY || next == QueueOutcome.PAUSED) return@withLock next
                if (next == QueueOutcome.FAILED) outcome = next
            }
            outcome
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                uploadRecordDao.resetUploadingToPending()
                val paused = pauseRequested.get() || appPreferences.userPaused.first()
                setPhase(if (paused) TransferPhase.PAUSED else TransferPhase.WAITING_FOR_NETWORK,
                    if (paused) "Paused. Your place is saved" else "Interrupted by Android. Your place is saved")
            }
            throw e
        } finally { activeRun = null }
    }
    private suspend fun runJob(job: UploadJob, records: List<UploadRecord>, api: TransferApi): QueueOutcome {
        val ledger = ProgressLedger(records)
        _transferState.value = ledger.snapshot(TransferState(sessionId = job.id, phase = TransferPhase.PREPARING,
            isTransferring = true, destinationName = connectionManager.serverName.value, message = "Checking space and preparing files"))
        var outcome = QueueOutcome.COMPLETE
        val stop = AtomicBoolean(false)
        try {
            val pending = records.filter { it.status == UploadStatus.PENDING }
            uploadRecordDao.putJob(job.copy(state = "running"))
            if (!reportJob(api, job.id, "running")) throw IOException("The computer has not acknowledged this backup job")
            coroutineScope {
                val ticker = launch { while (isActive) { delay(200); _transferState.update { ledger.snapshot(it) } } }
                try {
                    for (page in pending.chunked(100)) {
                        if (stop.get() || pauseRequested.get()) break
                        setPhase(TransferPhase.PREPARING, "Checking original files")
                        val prepared = java.util.Collections.synchronizedList(mutableListOf<UploadRecord>())
                        forEachBounded(page, parallelSlots(), stop) { record ->
                            ledger.start(record)
                            try {
                                val ready = prepare(record, api)
                                ledger.sourcePrepared(record, ready)
                                prepared.add(ready)
                            }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) {
                                if (e.shouldRetryTransfer()) {
                                    stop.set(true); outcome = QueueOutcome.RETRY
                                    _transferState.update { it.copy(message = e.message ?: "Preparation interrupted. Your place is saved") }
                                } else failRecord(record, e, ledger)
                            }
                        }
                        if (stop.get()) break
                        if (prepared.isEmpty()) continue
                        val matches = presence(api, prepared.map { it.md5Hash })
                        // Existing content consumes no new disk space. Check only known-missing
                        // bytes, so a nearly full receiver can still confirm an already-saved library.
                        val missingFiles = prepared.filter { !matches.getValue(it.md5Hash).optBoolean("exists") }.distinctBy { it.md5Hash }
                        val requiredBytes = missingFiles.sumOf { (it.fileSize - it.acknowledgedBytes).coerceAtLeast(0) }
                        val preflight = api.json("/v2/preflight", "POST", JSONObject().put("totalBytes", requiredBytes).put("totalFiles", missingFiles.size))
                        if (requiredBytes > 0 && !preflight.optBoolean("enoughSpace", true)) {
                            _transferState.update { it.copy(enoughSpace = false) }
                            throw IOException("Not enough space for the remaining originals. Free space in the computer's destination folder, then resume")
                        }
                        _transferState.update { it.copy(enoughSpace = true) }
                        setPhase(TransferPhase.UPLOADING, "Sending originals to ${connectionManager.serverName.value.ifBlank { "your computer" }}")
                        forEachBounded(prepared.toList(), parallelSlots(), stop) { record ->
                            if (pauseRequested.get()) { stop.set(true); return@forEachBounded }
                            try {
                                val match = matches.getValue(record.md5Hash)
                                if (match.optBoolean("exists")) completeRecord(record, match, match.optString("uploadId") != record.uploadId, ledger)
                                else uploadFile(record, api, ledger)
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) {
                                if (e.shouldRetryTransfer()) {
                                    stop.set(true); outcome = QueueOutcome.RETRY
                                    _transferState.update { it.copy(message = e.message ?: "Connection interrupted. Your place is saved") }
                                } else failRecord(record, e, ledger)
                            }
                        }
                        _transferState.update { ledger.snapshot(it) }
                        reportJob(api, job.id, if (stop.get()) "waiting" else "running")
                    }
                } finally { ticker.cancel() }
            }
            if (pauseRequested.get()) outcome = QueueOutcome.PAUSED
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            outcome = if (e is IOException) QueueOutcome.RETRY else QueueOutcome.FAILED
            _transferState.update { it.copy(message = e.message ?: "The backup could not continue") }
        } finally {
            withContext(NonCancellable) {
                val persisted = uploadRecordDao.recordsForJob(job.id)
                val pending = persisted.count { it.status == UploadStatus.PENDING || it.status == UploadStatus.UPLOADING }
                val failed = persisted.count { it.status == UploadStatus.FAILED }
                val paused = pauseRequested.get() || appPreferences.userPaused.first()
                val state = when { paused -> "paused"; pending > 0 -> "waiting"; failed > 0 -> "failed"; else -> "completed" }
                uploadRecordDao.putJob(job.copy(state = if (state == "completed") "waiting" else state, userPaused = paused,
                    error = _transferState.value.message.orEmpty().takeIf { state != "completed" }.orEmpty()))
                _transferState.update { ledger.snapshot(it).copy(isTransferring = false, currentSpeedBytesPerSec = 0,
                    phase = when (state) { "paused" -> TransferPhase.PAUSED; "waiting" -> TransferPhase.WAITING_FOR_COMPUTER; "failed" -> TransferPhase.FAILED; else -> TransferPhase.COMPLETE },
                    message = if (state == "completed") "All files in this job are saved on ${connectionManager.serverName.value.ifBlank { "your computer" }}" else it.message) }
                val confirmed = withTimeoutOrNull(2500) { reportJob(api, job.id, state) } == true
                if (confirmed) uploadRecordDao.putJob(job.copy(state = state, userPaused = paused))
                else if (!paused) {
                    outcome = QueueOutcome.RETRY
                    if (pending == 0) setPhase(TransferPhase.WAITING_FOR_COMPUTER, "Files saved. Waiting for the computer to confirm the job receipt")
                }
                if (pending > 0 && !paused) outcome = QueueOutcome.RETRY else if (failed > 0) outcome = QueueOutcome.FAILED
            }
        }
        return outcome
    }

    private suspend fun prepare(record: UploadRecord, api: TransferApi): UploadRecord = withContext(Dispatchers.IO) {
        val (size, version) = readSourceVersion(record)
        val unchanged = version == record.sourceVersion && size == record.fileSize && record.hashAlgorithm == "sha256"
        if (!unchanged && record.uploadId.isNotBlank()) {
            try { api.json("/v2/uploads/${record.uploadId}", "DELETE") }
            catch (e: TransferHttpException) { if (e.code != 404) throw e }
        }
        val hash = if (unchanged && record.hashAlgorithm == "sha256" && record.md5Hash.length == 64) record.md5Hash else hashSource(Uri.parse(record.contentUri))
        val after = readSourceVersion(record)
        if (after != (size to version)) throw IOException("The source changed while it was being read. Retry when editing is finished")
        val prepared = record.copy(fileSize = size, sourceVersion = version, md5Hash = hash, hashAlgorithm = "sha256",
            uploadId = if (unchanged && record.uploadId.isNotBlank()) record.uploadId else UUID.randomUUID().toString(),
            acknowledgedBytes = if (unchanged) record.acknowledgedBytes else 0,
            dedupKey = ReceiptPolicy.key(record.receiverId, record.libraryId, record.contentUri, version), error = "")
        uploadRecordDao.update(prepared)
        prepared
    }

    private fun readSourceVersion(record: UploadRecord): Pair<Long, String> {
        val uri = Uri.parse(record.contentUri)
        if (uri.scheme == "file") {
            val file = File(requireNotNull(uri.path))
            if (!file.isFile) throw FileNotFoundException("The shared original is no longer available")
            return file.length() to ":0:${file.lastModified() / 1000}:${file.length()}"
        }
        val projection = arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.GENERATION_MODIFIED)
        return contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) throw FileNotFoundException("The original was removed from your phone")
            val size = cursor.getLong(0); val date = cursor.getLong(1); val generation = cursor.getLong(2)
            size to "${MediaStore.getVersion(context).orEmpty()}:$generation:$date:$size"
        } ?: throw FileNotFoundException("The original cannot be opened")
    }

    private suspend fun hashSource(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        (contentResolver.openInputStream(uri) ?: throw FileNotFoundException("The original cannot be opened")).use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun presence(api: TransferApi, hashes: List<String>, verify: Boolean = false): Map<String, JSONObject> {
        val unique = hashes.distinct()
        val response = api.json("/v2/files/exists", "POST", JSONObject().put("hashes", JSONArray(unique)).put("hashAlgorithm", "sha256").put("verify", verify))
        val files = response.optJSONArray("files") ?: throw IOException("The computer did not return a usable presence check")
        val found = (0 until files.length()).associate { val file = files.getJSONObject(it); file.getString("hash") to file }
        if (unique.any { found[it]?.has("exists") != true }) throw IOException("The computer returned an incomplete presence check")
        return found
    }
    private suspend fun uploadFile(initial: UploadRecord, api: TransferApi, ledger: ProgressLedger) = withContext(Dispatchers.IO) {
        var record = initial.copy(status = UploadStatus.UPLOADING)
        val wire = AtomicLong(record.sentBytes)
        uploadRecordDao.update(record)
        try {
            val metadata = JSONObject().put("uploadId", record.uploadId).put("jobId", record.jobId)
                .put("hash", record.md5Hash).put("hashAlgorithm", "sha256").put("size", record.fileSize)
                .put("fileName", record.fileName).put("bucketName", record.bucketName)
                .put("sourceTimestampMs", sourceTimestamp(record))
            val created = api.json("/v2/uploads", "POST", metadata)
            if (created.optBoolean("complete")) {
                completeRecord(record, created, created.optBoolean("deduplicated", created.optString("savedUploadId", created.optString("uploadId")) != record.uploadId), ledger)
                return@withContext
            }
            val offset = created.optLong("offset", 0)
            if (offset !in 0..record.fileSize) throw IOException("The receiver returned an invalid resume position")
            record = record.copy(acknowledgedBytes = offset)
            uploadRecordDao.update(record)
            ledger.acknowledge(record, offset)
            val uri = Uri.parse(record.contentUri)
            (contentResolver.openInputStream(uri) ?: throw FileNotFoundException("The original cannot be opened")).use { input ->
                skipFully(input, offset)
                while (record.acknowledgedBytes < record.fileSize) {
                    currentCoroutineContext().ensureActive()
                    if (pauseRequested.get()) throw CancellationException("Paused by you")
                    val count = minOf(CHUNK_BYTES.toLong(), record.fileSize - record.acknowledgedBytes).toInt()
                    val bytes = ByteArray(count)
                    var read = 0
                    while (read < count) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(bytes, read, minOf(128 * 1024, count - read))
                        if (n < 0) throw FileNotFoundException("The original changed or ended before its recorded size")
                        read += n
                    }
                    val owner = currentCoroutineContext()[Job]
                    val body = object : RequestBody() {
                        override fun contentType() = "application/octet-stream".toMediaType()
                        override fun contentLength() = bytes.size.toLong()
                        override fun writeTo(sink: BufferedSink) {
                            var start = 0
                            while (start < bytes.size) {
                                if (owner?.isActive == false || pauseRequested.get()) throw InterruptedIOException("Transfer paused")
                                val amount = minOf(64 * 1024, bytes.size - start)
                                sink.write(bytes, start, amount)
                                wire.addAndGet(amount.toLong()); ledger.wire(amount.toLong())
                                start += amount
                            }
                        }
                    }
                    val acknowledged = api.request("/v2/uploads/${record.uploadId}", "PATCH", body, record.acknowledgedBytes)
                    val next = acknowledged.optLong("offset", -1)
                    if (next != record.acknowledgedBytes + count) throw IOException("The receiver did not acknowledge the full chunk")
                    record = record.copy(acknowledgedBytes = next, sentBytes = wire.get())
                    uploadRecordDao.update(record)
                    ledger.acknowledge(record, next)
                }
            }
            val after = readSourceVersion(record)
            if (after != (record.fileSize to record.sourceVersion)) throw IOException("The original changed during upload; its new version needs another pass")
            val receipt = api.json("/v2/uploads/${record.uploadId}/complete", "POST")
            if (!receipt.optBoolean("complete")) throw IOException("The receiver has not verified this file")
            completeRecord(record.copy(sentBytes = wire.get()), receipt, receipt.optBoolean("deduplicated", false), ledger)
        } catch (e: Exception) {
            withContext(NonCancellable) {
                if (e is TransferHttpException && e.code == 422) {
                    // A checksum rejection invalidates both the hash cache and resume proof.
                    // An explicit retry re-reads the source and abandons this failed upload ID.
                    record = record.copy(md5Hash = "", hashAlgorithm = "sha256-invalid", acknowledgedBytes = 0)
                }
                uploadRecordDao.update(record.copy(status = UploadStatus.PENDING, sentBytes = wire.get(), error = e.message.orEmpty()))
            }
            throw e
        }
    }

    private suspend fun skipFully(input: InputStream, offset: Long) {
        var remaining = offset
        while (remaining > 0) {
            currentCoroutineContext().ensureActive()
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped else {
                if (input.read() < 0) throw FileNotFoundException("The original is shorter than its saved resume position")
                remaining--
            }
        }
    }

    private suspend fun completeRecord(record: UploadRecord, receipt: JSONObject, skipped: Boolean, ledger: ProgressLedger) {
        val saved = record.copy(status = UploadStatus.COMPLETED, progress = 100, acknowledgedBytes = record.fileSize,
            uploadedAt = System.currentTimeMillis(), skipped = skipped, receiptId = receipt.optString("receiptId"), error = "", historyHidden = false)
        uploadRecordDao.update(saved)
        ledger.finish(saved, UploadStatus.COMPLETED, skipped)
        if (skipped) _transferState.update { state -> state.copy(skippedDuplicates = (listOf(DedupSkip(record.id, record.fileName,
            record.bucketName, receipt.optString("fileName"), receipt.optString("bucketName"), record.fileSize, record.md5Hash, false)) + state.skippedDuplicates).take(100)) }
    }

    private suspend fun failRecord(record: UploadRecord, error: Exception, ledger: ProgressLedger) {
        val latest = uploadRecordDao.record(record.id) ?: record
        val failed = latest.copy(status = UploadStatus.FAILED, error = error.message ?: "The original could not be sent")
        uploadRecordDao.update(failed)
        ledger.finish(failed, UploadStatus.FAILED)
    }

    private suspend fun <T> forEachBounded(items: List<T>, slots: Int, stop: AtomicBoolean, action: suspend (T) -> Unit) = coroutineScope {
        val next = AtomicInteger(0)
        repeat(minOf(slots, items.size)) {
            launch {
                while (isActive && !stop.get() && !pauseRequested.get()) {
                    val index = next.getAndIncrement()
                    if (index >= items.size) break
                    action(items[index])
                }
            }
        }
    }

    private suspend fun reportJob(api: TransferApi, id: String, state: String): Boolean {
        return try {
            val current = _transferState.value
            api.json("/v2/jobs/$id", "PUT", JSONObject().put("state", state).put("totalFiles", current.totalFiles)
                .put("totalBytes", current.totalBytes).put("completedFiles", (current.completedFiles - current.skippedFiles).coerceAtLeast(0))
                .put("skippedFiles", current.skippedFiles).put("failedFiles", current.failedFiles)
                .put("completedBytes", current.savedBytes).put("error", if (state == "waiting" || state == "failed") current.message.orEmpty() else ""))
            true
        } catch (e: CancellationException) { throw e } catch (_: IOException) { false }
    }

    private fun sourceTimestamp(record: UploadRecord): Long = record.sourceVersion.split(':').let {
        if (it.size >= 2) it[it.size - 2].toLongOrNull()?.times(1000) ?: 0 else 0
    }

    private suspend fun adoptLegacyQueue(identity: ReceiverIdentity) {
        val endpoint = connectionManager.getConnectedEndpoint()
        val legacy = uploadRecordDao.legacyPending().filter { it.serverIp.isBlank() || it.serverIp == endpoint }
        if (legacy.isEmpty()) return
        val id = UUID.randomUUID().toString()
        uploadRecordDao.putJob(UploadJob(id, identity.deviceId, identity.libraryId, System.currentTimeMillis()))
        for (record in legacy) uploadRecordDao.update(record.copy(receiverId = identity.deviceId, libraryId = identity.libraryId,
            jobId = id, uploadId = UUID.randomUUID().toString(), hashAlgorithm = "sha256", md5Hash = "", status = UploadStatus.PENDING))
    }

    private suspend fun restoreLatestState() {
        val identity = connectionManager.selectedIdentity() ?: return
        val job = uploadRecordDao.openJobs().lastOrNull { it.receiverId == identity.deviceId && it.libraryId == identity.libraryId }
            ?: uploadRecordDao.latestJob()?.takeIf { it.receiverId == identity.deviceId && it.libraryId == identity.libraryId } ?: return
        val records = uploadRecordDao.recordsForJob(job.id)
        val paused = appPreferences.userPaused.first() || job.userPaused
        pauseRequested.set(paused)
        val phase = when { paused -> TransferPhase.PAUSED; job.state == "completed" -> TransferPhase.COMPLETE; job.state == "failed" -> TransferPhase.FAILED; else -> TransferPhase.WAITING_FOR_COMPUTER }
        _transferState.value = ProgressLedger(records).snapshot(TransferState(sessionId = job.id, phase = phase,
            message = if (paused) "Paused. Your place is saved" else job.error.takeIf(String::isNotBlank)))
    }

    private fun setPhase(phase: TransferPhase, message: String?) {
        _transferState.update { it.copy(phase = phase, message = message, isTransferring = phase in setOf(TransferPhase.PREPARING, TransferPhase.UPLOADING, TransferPhase.VERIFYING)) }
    }

    suspend fun completedMediaStoreIds(liveItems: List<MediaItem>): Set<Long> {
        val identity = connectionManager.selectedIdentity() ?: return emptySet()
        val receipts = uploadRecordDao.recordsForDestination(identity.deviceId, identity.libraryId)
            .filter { it.status == UploadStatus.COMPLETED }.mapNotNullTo(HashSet()) { it.dedupKey }
        return liveItems.filter { ReceiptPolicy.key(identity.deviceId, identity.libraryId, it.uri.toString(), it.sourceVersion) in receipts }.mapTo(HashSet()) { it.id }
    }
    suspend fun completedMediaStoreIds(): Set<Long> = completedMediaStoreIds(mediaRepository.loadAllMedia())

    suspend fun filterUnsent(liveItems: List<MediaItem>): List<MediaItem> {
        val identity = connectionManager.selectedIdentity() ?: return liveItems
        val recorded = uploadRecordDao.recordsForDestination(identity.deviceId, identity.libraryId)
            .filter { it.status != UploadStatus.FAILED }.mapNotNullTo(HashSet()) { it.dedupKey }
        return liveItems.filter { ReceiptPolicy.key(identity.deviceId, identity.libraryId, it.uri.toString(), it.sourceVersion) !in recorded }
    }
    fun verifyAgainstServer(verifyIntegrity: Boolean = false) {
        if (_verifyState.value.isVerifying) return
        _verifyState.value = VerifyState(isVerifying = true)
        scope.launch {
            var checked = 0; var missing = 0
            val missingRecords = mutableListOf<UploadRecord>()
            var repairTarget: ReceiverIdentity? = null
            try {
                val identity = connectionManager.selectedIdentity() ?: throw IOException("Pair a computer first")
                repairTarget = identity
                val connection = connectionManager.connectionFor(identity) ?: throw IOException("Your computer is unavailable")
                val completed = uploadRecordDao.recordsForDestination(identity.deviceId, identity.libraryId)
                    .filter { it.status == UploadStatus.COMPLETED && it.hashAlgorithm == "sha256" && it.dedupKey != null }
                _verifyState.value = VerifyState(isVerifying = true, total = completed.size)
                val api = TransferApi(okHttpClient, connection)
                for (page in completed.chunked(if (verifyIntegrity) 10 else 100)) {
                    val result = presence(api, page.map { it.md5Hash }, verifyIntegrity)
                    for (record in page) {
                        if (!result.getValue(record.md5Hash).optBoolean("exists")) {
                            missing++
                            missingRecords.add(record)
                        }
                        checked++
                    }
                    _verifyState.update { it.copy(checked = checked, missing = missing) }
                }
                _verifyState.update { it.copy(isVerifying = false, summary = if (missing > 0) "$missing missing files are queued to send again" else
                    if (verifyIntegrity) "$checked originals passed the checksum check" else "$checked originals are present on this computer") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _verifyState.update { it.copy(isVerifying = false, checked = checked, missing = missing,
                unknown = (it.total - checked).coerceAtLeast(0), interrupted = checked > 0, unreachable = checked == 0,
                summary = "Check interrupted. ${e.message.orEmpty()}") } }
            finally {
                val target = repairTarget
                if (target != null && missingRecords.isNotEmpty()) {
                    withContext(NonCancellable) {
                        val job = UploadJob(UUID.randomUUID().toString(), target.deviceId, target.libraryId, System.currentTimeMillis())
                        enqueueMutex.withLock { uploadRecordDao.enqueueRepair(job, missingRecords) }
                        if (!appPreferences.userPaused.first()) scheduleWork(false, ExistingWorkPolicy.APPEND_OR_REPLACE)
                    }
                }
            }
        }
    }

    suspend fun computeSyncPlan(liveItems: List<MediaItem>): SyncPlan {
        val identity = connectionManager.selectedIdentity()
        val completed = if (identity == null) emptyList() else uploadRecordDao.recordsForDestination(identity.deviceId, identity.libraryId)
            .filter { it.status == UploadStatus.COMPLETED && it.dedupKey != null }
        val liveUris = liveItems.mapTo(HashSet()) { it.uri.toString() }
        val phoneRecords = completed.filter { it.mediaStoreId >= 0 }
        val missing = phoneRecords.filter { it.contentUri !in liveUris }
        val withhold = ReceiptPolicy.shouldWithholdDeletes(hasFullMediaAccess(), mediaRepository.isAuthoritativeSnapshot(liveItems), liveItems.size, missing.size, phoneRecords.size)
        val aliveHashes = completed.filter { it.contentUri in liveUris }.mapTo(HashSet()) { it.md5Hash }
        val deletedRecords = if (withhold) emptyList() else missing
        val entries = deletedRecords.filter { it.md5Hash.isNotBlank() && it.md5Hash !in aliveHashes }.distinctBy { it.md5Hash }
            .map { SyncDeleteEntry(it.md5Hash, it.bucketName, it.fileName) }
        val uploadItems = filterUnsent(liveItems)
        return SyncPlan(uploadItems, uploadItems.sumOf { it.size }, entries, deletedRecords.map { it.id },
            deletesWithheld = missing.isNotEmpty() && withhold, receiverId = identity?.deviceId.orEmpty(), libraryId = identity?.libraryId.orEmpty())
    }

    private fun hasFullMediaAccess(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        return perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    fun executeSync(confirmed: SyncPlan) {
        scope.launch {
            _syncState.value = SyncState(isSyncing = true)
            try {
                val identity = connectionManager.selectedIdentity() ?: throw IOException("Connect to the computer first")
                if (identity.deviceId != confirmed.receiverId || identity.libraryId != confirmed.libraryId) throw IOException("The destination changed. Review Sync again")
                if (confirmed.uploadItems.isNotEmpty()) enqueueAndSchedule(confirmed.uploadItems, true)
                if (confirmed.deleteEntries.isEmpty()) { _syncState.value = SyncState(summary = "New files are queued. No computer files were removed"); return@launch }
                if (!hasFullMediaAccess()) throw IOException("Full photo and video access is needed for Sync deletions")
                val fresh = computeSyncPlan(mediaRepository.loadAllMedia())
                if (fresh.deletesWithheld) throw IOException("The library scan is incomplete. Deletions were held back")
                val allowed = fresh.deleteEntries.mapTo(HashSet()) { it.md5 }
                val entries = confirmed.deleteEntries.filter { it.md5 in allowed }
                val connection = connectionManager.connectionFor(identity) ?: throw IOException("The computer is unavailable")
                val api = TransferApi(okHttpClient, connection)
                val removedHashes = HashSet<String>()
                var failures = 0; var deleted = 0
                for (page in entries.chunked(100)) {
                    val payload = JSONObject().put("entries", JSONArray(page.map { JSONObject().put("hash", it.md5) }))
                    val response = api.json("/v2/sync/delete", "POST", payload)
                    val results = response.optJSONArray("results") ?: throw IOException("The computer did not confirm which files were removed")
                    for (index in 0 until results.length()) {
                        val result = results.getJSONObject(index)
                        val hash = result.optString("hash")
                        if (result.optBoolean("deleted")) { deleted++; removedHashes.add(hash) }
                        else if (result.optBoolean("missing")) removedHashes.add(hash)
                        else failures++
                    }
                }
                val completed = uploadRecordDao.recordsForDestination(identity.deviceId, identity.libraryId)
                val ids = confirmed.deletedRecordIds.toHashSet()
                uploadRecordDao.deleteByIds(completed.filter { it.id in ids && it.md5Hash in removedHashes }.map { it.id })
                _syncState.value = SyncState(deleted = deleted, summary = "$deleted computer files removed" + if (failures > 0) "; $failures could not be removed and can be retried" else "")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _syncState.value = SyncState(summary = "Sync stopped: ${e.message.orEmpty()}. Unconfirmed files remain recorded for retry") }
        }
    }

    fun clearSyncSummary() { _syncState.update { it.copy(summary = null) } }
    private suspend fun parallelSlots(): Int = if (appPreferences.highSpeedTransferEnabled.first()) 4 else 2
    companion object { private const val CHUNK_BYTES = 4 * 1024 * 1024 }
}
