package com.appharbor.pherry.data.upload

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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val contentResolver: ContentResolver = context.contentResolver
    // Two enqueues racing (a double tap, auto-backup, a sync) could both pass the already-queued
    // check and insert every file twice; the table has no unique index on mediaStoreId.
    private val enqueueMutex = Mutex()

    private val _transferState = MutableStateFlow(TransferState())
    val transferState: StateFlow<TransferState> = _transferState.asStateFlow()

    private val _verifyState = MutableStateFlow(VerifyState())
    val verifyState: StateFlow<VerifyState> = _verifyState.asStateFlow()

    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

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

        scope.launch { enqueueAndSchedule(items) }
    }

    /**
     * Persist [items] as PENDING records and enqueue the worker, suspending until both are durable.
     * Background callers (auto-backup) await this so they only advance their checkpoint once the
     * work can survive process death — unlike [start], which fires-and-forgets on an internal scope.
     */
    suspend fun enqueueAndSchedule(items: List<MediaItem>) {
        if (items.isEmpty()) return
        enqueueRecords(items)
        scheduleWork(ExistingWorkPolicy.APPEND_OR_REPLACE)
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
            if (baseUrl.isBlank()) {
                _verifyState.value = VerifyState(
                    unreachable = true,
                    summary = "Pair a computer first to check your backups.",
                )
                return@launch
            }
            val computer = connectionManager.serverName.value.ifBlank { "your computer" }

            val completed = uploadRecordDao.getCompletedSnapshot()
            _verifyState.value = VerifyState(isVerifying = true, total = completed.size)

            val checked = java.util.concurrent.atomic.AtomicInteger(0)
            val unknown = java.util.concurrent.atomic.AtomicInteger(0)
            val unreadable = java.util.concurrent.atomic.AtomicInteger(0)
            val unknownInARow = java.util.concurrent.atomic.AtomicInteger(0)
            val interrupted = java.util.concurrent.atomic.AtomicBoolean(false)
            val missingRecords = java.util.concurrent.ConcurrentHashMap.newKeySet<UploadRecord>()
            val semaphore = Semaphore(parallelSlots())
            coroutineScope {
                completed.map { record ->
                    launch {
                        semaphore.withPermit {
                            // Once the computer stops answering, skip the rest instead of guessing about them.
                            if (!interrupted.get()) {
                                // No stored hash (e.g. legacy record) → recompute so we can still check.
                                val md5 = record.md5Hash.ifBlank {
                                    runCatching { computeMd5(Uri.parse(record.contentUri)) }.getOrDefault("")
                                }
                                if (md5.isBlank()) {
                                    unreadable.incrementAndGet()
                                } else {
                                    when (serverPresence(baseUrl, md5)) {
                                        Presence.PRESENT -> unknownInARow.set(0)
                                        Presence.ABSENT -> {
                                            unknownInARow.set(0)
                                            missingRecords.add(record)
                                        }
                                        Presence.UNKNOWN -> {
                                            unknown.incrementAndGet()
                                            if (unknownInARow.incrementAndGet() >= VERIFY_MAX_UNKNOWN_IN_A_ROW) {
                                                interrupted.set(true)
                                            }
                                        }
                                    }
                                }
                                val done = checked.incrementAndGet()
                                _verifyState.update {
                                    it.copy(
                                        checked = done,
                                        missing = missingRecords.size,
                                        unknown = unknown.get(),
                                        unreadable = unreadable.get(),
                                    )
                                }
                            }
                        }
                    }
                }.forEach { it.join() }
            }

            // Re-queue only what the server confirmed it doesn't have, then re-arm the worker.
            for (record in missingRecords) {
                uploadRecordDao.update(record.copy(status = UploadStatus.PENDING, progress = 0))
            }
            val missingCount = missingRecords.size
            if (missingCount > 0) {
                scheduleWork(ExistingWorkPolicy.APPEND_OR_REPLACE)
            }
            val unknownCount = unknown.get()
            val unreadableCount = unreadable.get()
            val wasInterrupted = interrupted.get()
            val checkedCount = checked.get()
            _verifyState.value = VerifyState(
                isVerifying = false,
                checked = checkedCount,
                total = completed.size,
                missing = missingCount,
                unknown = unknownCount,
                unreadable = unreadableCount,
                interrupted = wasInterrupted,
                summary = verifySummary(
                    computer = computer,
                    total = completed.size,
                    checked = checkedCount,
                    missing = missingCount,
                    unconfirmed = unknownCount + unreadableCount,
                    interrupted = wasInterrupted,
                ),
            )
        }
    }

    /** The one-line result of a verify pass, naming the computer that was asked. */
    private fun verifySummary(
        computer: String,
        total: Int,
        checked: Int,
        missing: Int,
        unconfirmed: Int,
        interrupted: Boolean,
    ): String = when {
        interrupted -> "Lost $computer after checking ${Fmt.count(checked)} of ${Fmt.count(total)}."
        missing == 1 ->
            "1 of ${Fmt.count(total)} was missing from $computer. It's queued to send again."
        missing > 0 ->
            "${Fmt.count(missing)} of ${Fmt.count(total)} were missing from $computer. They're queued to send again."
        unconfirmed == 0 -> when (total) {
            0 -> "Nothing to check yet. No files have been sent to $computer."
            1 -> "The 1 file is on $computer."
            else -> "All ${Fmt.count(total)} files are on $computer."
        }
        else -> "${Fmt.count(total - unconfirmed)} of ${Fmt.count(total)} confirmed on $computer."
    }

    /**
     * Sync mode plan: diff the current phone library against what we've uploaded. Uses only cheap
     * metadata (the passed-in scan + the local DB) so it's instant even for a 22k library.
     *
     * The [aliveMd5s] guard makes deletion conservative against duplicates: if the same content
     * still exists somewhere on the phone, its desktop copy is kept even when one phone copy was
     * removed. Content with a blank hash (legacy records) is never proposed for deletion.
     */
    suspend fun computeSyncPlan(liveItems: List<MediaItem>): SyncPlan {
        val liveIds = liveItems.mapTo(HashSet()) { it.id }
        val completed = uploadRecordDao.getCompletedSnapshot()

        val aliveMd5s = completed.asSequence()
            .filter { it.mediaStoreId in liveIds && it.md5Hash.isNotBlank() }
            .mapTo(HashSet()) { it.md5Hash }

        // Only phone-library records can be "deleted from this phone". Media shared in from other
        // apps carries negative synthetic ids and never appears in a MediaStore scan, so it is
        // never proposed for deletion.
        val phoneRecords = completed.filter { it.mediaStoreId >= 0 }
        val missing = phoneRecords.filter { it.mediaStoreId !in liveIds }
        // A scan that can't see the whole library would read as mass deletion. Hold deletes back
        // when access is partial, the scan is empty, or most backed-up photos vanished at once.
        val withhold = missing.isNotEmpty() && (
            !hasFullMediaAccess() ||
                liveItems.isEmpty() ||
                (missing.size >= SUSPICIOUS_MISSING_MIN && missing.size * 2 > phoneRecords.size)
            )
        val deletedRecords = if (withhold) emptyList() else missing
        val deleteEntries = deletedRecords.asSequence()
            .filter { it.md5Hash.isNotBlank() && it.md5Hash !in aliveMd5s }
            .distinctBy { it.md5Hash }
            .map { SyncDeleteEntry(md5 = it.md5Hash, bucketName = it.bucketName, fileName = it.fileName) }
            .toList()

        // New uploads: same set-based filter enqueueRecords uses.
        val completedIds = completed.mapTo(HashSet()) { it.mediaStoreId }
        val alreadyQueuedIds = uploadRecordDao.getPendingAndUploading().mapTo(HashSet()) { it.mediaStoreId }
        val uploadItems = liveItems.filter { it.id !in completedIds && it.id !in alreadyQueuedIds }

        return SyncPlan(
            uploadItems = uploadItems,
            uploadBytes = uploadItems.sumOf { it.size },
            deleteEntries = deleteEntries,
            deletedRecordIds = deletedRecords.map { it.id },
            deletesWithheld = withhold,
        )
    }

    private fun hasFullMediaAccess(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    /**
     * Run a confirmed [SyncPlan]: queue the new files through the normal upload path, then ask the
     * desktop to delete the removed ones. Local records for gone-from-phone media are dropped only
     * after the desktop confirms, so a failed/offline delete stays retryable on the next sync.
     */
    fun executeSync(confirmed: SyncPlan) {
        // Without the pairing token the desktop is certain to refuse the delete (401): run only
        // the upload half and keep the records, so a later sync with the token can delete them.
        val plan = if (!connectionManager.canDelete.value && confirmed.deleteEntries.isNotEmpty()) {
            confirmed.copy(deleteEntries = emptyList(), deletedRecordIds = emptyList())
        } else {
            confirmed
        }
        if (plan.uploadItems.isNotEmpty()) {
            start(plan.uploadItems)
        }
        if (plan.deletedRecordIds.isEmpty()) return

        scope.launch {
            _syncState.value = SyncState(isSyncing = true)
            val computer = connectionManager.serverName.value.ifBlank { "your computer" }
            val result = deleteServerFiles(plan.deleteEntries)
            if (result.ok) {
                uploadRecordDao.deleteByIds(plan.deletedRecordIds)
                _syncState.value = SyncState(
                    isSyncing = false,
                    deleted = result.deleted,
                    summary = if (result.deleted > 0) {
                        "Deleted ${Fmt.plural(result.deleted, "file")} from $computer."
                    } else {
                        "Nothing to delete. $computer already matches this phone."
                    },
                )
            } else {
                _syncState.value = SyncState(
                    isSyncing = false,
                    summary = if (result.unauthorized) {
                        "To delete files on $computer, scan its pairing ticket again."
                    } else {
                        "Couldn't reach $computer. Nothing was deleted. Try again when it's connected."
                    },
                )
            }
        }
    }

    // POST the delete list to the desktop. Network failures return ok=false so we don't drop the
    // local records (the deletion stays retryable). An empty list is a no-op success.
    private fun deleteServerFiles(entries: List<SyncDeleteEntry>): SyncDeleteResult {
        if (entries.isEmpty()) return SyncDeleteResult(ok = true)
        val baseUrl = connectionManager.getBaseUrl()
        if (baseUrl.isBlank()) return SyncDeleteResult(ok = false)
        return runCatching {
            val payload = JSONObject().apply {
                put("files", JSONArray().apply {
                    entries.forEach { entry ->
                        put(JSONObject().apply {
                            put("md5", entry.md5)
                            put("bucketName", entry.bucketName)
                            put("fileName", entry.fileName)
                        })
                    }
                })
            }.toString()
            val request = Request.Builder()
                .url("$baseUrl/sync/delete")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@runCatching SyncDeleteResult(ok = false, unauthorized = response.code == 401)
                }
                val json = JSONObject(response.body?.string().orEmpty())
                SyncDeleteResult(
                    ok = json.optBoolean("success", false),
                    deleted = json.optInt("deleted", 0),
                    bytesFreed = json.optLong("bytesFreed", 0L),
                    notFound = json.optJSONArray("notFound")?.length() ?: 0,
                )
            }
        }.getOrDefault(SyncDeleteResult(ok = false))
    }

    /** Clear a finished sync summary once the UI has shown it. */
    fun clearSyncSummary() {
        _syncState.update { it.copy(summary = null) }
    }

    /** MediaStore ids already backed up — used by the "New since last backup" quick selection. */
    suspend fun completedMediaStoreIds(): Set<Long> =
        uploadRecordDao.getCompletedMediaStoreIds().toHashSet()

    /**
     * The subset of [liveItems] that isn't on the desktop yet — not already completed and not already
     * queued. This is exactly what "Back up new" sends; it mirrors the filter [enqueueRecords] applies,
     * so the count the Home screen shows matches what actually gets queued.
     */
    suspend fun filterUnsent(liveItems: List<MediaItem>): List<MediaItem> {
        val completedIds = uploadRecordDao.getCompletedMediaStoreIds().toHashSet()
        val queuedIds = uploadRecordDao.getPendingAndUploading().mapTo(HashSet()) { it.mediaStoreId }
        return liveItems.filter { it.id !in completedIds && it.id !in queuedIds }
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
    private suspend fun enqueueRecords(items: List<MediaItem>) = enqueueMutex.withLock {
        uploadRecordDao.resetUploadingToPending()
        // Re-arm prior hard failures so any new transfer also retries them. This both gives
        // failed files another chance and prevents orphaned FAILED rows when a failed item is
        // re-selected (it's revived in place rather than inserted as a duplicate row).
        uploadRecordDao.resetFailedToPending()

        val completedIds = uploadRecordDao.getCompletedMediaStoreIds().toHashSet()
        val alreadyQueuedIds = uploadRecordDao.getPendingAndUploading()
            .mapTo(HashSet()) { it.mediaStoreId }

        val serverIp = connectionManager.getConnectedEndpoint()
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

    private suspend fun scheduleWork(policy: ExistingWorkPolicy) {
        // LAN transfer to a local server defaults to Wi-Fi; users can relax this in Settings.
        val networkType = if (appPreferences.wifiOnlyTransfer.first()) {
            NetworkType.UNMETERED
        } else {
            NetworkType.CONNECTED
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
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
            // Nothing was queued (e.g. everything picked was already on the computer): close the
            // job at what actually ran, so it never reads as a paused "0 / 12" with nothing to resume.
            _transferState.update {
                it.copy(
                    isTransferring = false,
                    activeTransfers = emptyList(),
                    totalFiles = it.completedFiles + it.failedFiles,
                )
            }
            return
        }

        speedTracker = SpeedTracker()
        // Seed the full batch as PENDING so the list is stable from the start.
        // Items are updated in-place as they progress; none are removed mid-batch.
        val initialTransfers = batch.map { record ->
            FileTransferProgress(
                recordId = record.id,
                fileName = record.fileName,
                contentUri = record.contentUri,
                fileSize = record.fileSize,
                bytesTransferred = 0,
                status = UploadStatus.PENDING,
            )
        }
        _transferState.value = TransferState(
            isTransferring = true,
            totalFiles = batch.size,
            totalBytes = batch.sumOf { it.fileSize },
            activeTransfers = initialTransfers,
        )

        try {
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
        } catch (e: CancellationException) {
            // The worker was stopped from outside the app (notification Stop, lost constraint,
            // system stop): leave the same state as cancelTransfer(). uploadFile already put the
            // in-flight records back to PENDING.
            _transferState.update { it.copy(isTransferring = false, activeTransfers = emptyList()) }
            throw e
        } finally {
            // Keep activeTransfers visible after completion so the user can see the final state.
            // The list is cleared when the next batch starts (see start() / runQueue init above).
            _transferState.update { it.copy(isTransferring = false) }
        }
    }

    private suspend fun uploadFile(record: UploadRecord) {
        // Resolve the destination first. Prefer the IP captured when the record was queued so a
        // transfer can resume after process death; fall back to the live connection and persist
        // that IP. If nothing is reachable, keep the file PENDING (retryable) rather than burning
        // a hard failure — this is what previously stranded files queued while disconnected.
        val serverIp = record.serverIp.takeIf { it.isNotBlank() } ?: connectionManager.getConnectedEndpoint()
        if (serverIp.isBlank()) {
            uploadRecordDao.update(record.copy(status = UploadStatus.PENDING))
            return
        }
        val baseUrl = connectionManager.baseUrlForTarget(serverIp) ?: run {
            uploadRecordDao.update(record.copy(status = UploadStatus.PENDING))
            return
        }

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
            val exactCompleted = uploadRecordDao.getCompletedByMediaStoreId(working.mediaStoreId)
                ?.takeIf { it.md5Hash == md5 }
            val localMatch = exactCompleted ?: uploadRecordDao.getOriginalByMd5(md5, working.mediaStoreId)
            val serverMatch = serverFileMatch(baseUrl, md5)
            if (serverMatch.exists) {
                completeWithoutUpload(working, md5, localMatch, serverMatch)
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
                        // Mark item COMPLETED in the stable batch list (in-place, no removal).
                        updateActiveTransfer(working.id, working.fileName, working.contentUri, working.fileSize, working.fileSize, UploadStatus.COMPLETED)
                        _transferState.update { st ->
                            st.copy(completedFiles = st.completedFiles + 1, sentBytes = st.sentBytes + working.fileSize)
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
            updateActiveTransfer(working.id, working.fileName, working.contentUri, working.fileSize, 0, UploadStatus.PENDING)
            throw e
        } catch (e: FileNotFoundException) {
            // The source is gone (deleted from the phone, or a pruned share). Retrying can't help,
            // so surface it as a hard failure instead of leaving it PENDING to loop forever. It
            // only comes back on an explicit retry or a new batch (resetFailedToPending).
            markFailed(working)
        } catch (e: IOException) {
            // Network/read timeouts can happen after the server already persisted the file.
            // Keep this retryable instead of marking it as a hard failure.
            uploadRecordDao.update(working.copy(status = UploadStatus.PENDING))
            updateActiveTransfer(working.id, working.fileName, working.contentUri, working.fileSize, 0, UploadStatus.PENDING)
        } catch (e: Exception) {
            markFailed(working)
        }
        // No removeActiveTransfer — items stay in the stable list with their final status.
    }

    private suspend fun markFailed(working: UploadRecord) {
        uploadRecordDao.update(working.copy(status = UploadStatus.FAILED))
        updateActiveTransfer(working.id, working.fileName, working.contentUri, working.fileSize, 0, UploadStatus.FAILED)
        _transferState.update { st ->
            st.copy(failedFiles = st.failedFiles + 1)
        }
    }

    // Ask the server whether it already stores this content. Failures default to false so we
    // never skip an upload on a flaky check.
    private fun serverHasFile(baseUrl: String, md5: String): Boolean =
        serverFileMatch(baseUrl, md5).exists

    private fun serverFileMatch(baseUrl: String, md5: String): ServerFileMatch = runCatching {
        val request = Request.Builder().url("$baseUrl/exists?md5=$md5").build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@runCatching ServerFileMatch()
            val json = JSONObject(response.body?.string().orEmpty())
            val match = json.optJSONObject("match")
            ServerFileMatch(
                exists = json.optBoolean("exists", false),
                fileName = match?.optString("fileName")?.takeIf { it.isNotBlank() },
                bucketName = match?.optString("bucketName")?.takeIf { it.isNotBlank() },
                size = match?.optLong("size", 0L) ?: 0L,
            )
        }
    }.getOrDefault(ServerFileMatch())

    // The verify pass needs to tell "not there" apart from "couldn't ask": any failure is UNKNOWN,
    // never ABSENT, so a dropped connection is not reported (or re-queued) as missing files.
    private fun serverPresence(baseUrl: String, md5: String): Presence = runCatching {
        val request = Request.Builder().url("$baseUrl/exists?md5=$md5").build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@runCatching Presence.UNKNOWN
            val json = JSONObject(response.body?.string().orEmpty())
            when {
                !json.has("exists") -> Presence.UNKNOWN
                json.optBoolean("exists", false) -> Presence.PRESENT
                else -> Presence.ABSENT
            }
        }
    }.getOrDefault(Presence.UNKNOWN)

    // Mark a record done without transferring bytes (used by both local and server dedup).
    private suspend fun completeWithoutUpload(
        record: UploadRecord,
        md5: String,
        localMatch: UploadRecord?,
        serverMatch: ServerFileMatch,
    ) {
        uploadRecordDao.update(
            record.copy(
                status = UploadStatus.COMPLETED,
                md5Hash = md5,
                progress = 100,
                uploadedAt = System.currentTimeMillis(),
            )
        )
        val skip = DedupSkip(
            skippedRecordId = record.id,
            skippedFileName = record.fileName,
            skippedBucketName = record.bucketName,
            matchedFileName = localMatch?.fileName ?: serverMatch.fileName,
            matchedBucketName = localMatch?.bucketName ?: serverMatch.bucketName,
            fileSize = record.fileSize,
            md5Hash = md5,
            matchedOnPhone = localMatch != null,
        )
        // updateActiveTransfer already adds record.fileSize to transferredBytes (delta from 0),
        // so do NOT add it again here — doing so double-counts every dedup-skipped file and makes
        // the byte-based progress bar race to 100% long before the file count finishes.
        updateActiveTransfer(record.id, record.fileName, record.contentUri, record.fileSize, record.fileSize, UploadStatus.COMPLETED)
        _transferState.update { st ->
            st.copy(
                completedFiles = st.completedFiles + 1,
                skippedFiles = st.skippedFiles + 1,
                skippedDuplicates = (listOf(skip) + st.skippedDuplicates).take(MAX_SKIPPED_BATCH),
            )
        }
    }

    private fun resolveContentLength(uri: Uri, fallback: Long): Long {
        val length = runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()
        return if (length != null && length != AssetFileDescriptor.UNKNOWN_LENGTH) length else fallback
    }

    private fun computeMd5(uri: Uri): String {
        val digest = MessageDigest.getInstance("MD5")
        // A null stream means the provider has nothing behind this URI any more: treat it as gone,
        // never as an empty file (that would hash to d41d8cd9... and dedup against nothing).
        val stream = contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException("Cannot open $uri")
        stream.use { input ->
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
