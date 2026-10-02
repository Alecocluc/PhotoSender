package com.appharbor.pherry.data.upload

import com.appharbor.pherry.data.db.UploadRecord
import com.appharbor.pherry.data.db.UploadStatus
import com.appharbor.pherry.data.network.TransferHttpException
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest

/** Pure receipt identity policy shared by selection, enqueue and regression tests. */
object ReceiptPolicy {
    fun key(receiverId: String, libraryId: String, uri: String, version: String): String =
        MessageDigest.getInstance("SHA-256").digest(listOf(receiverId, libraryId, uri, version).joinToString("\u0000").toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun matches(record: UploadRecord, receiverId: String, libraryId: String, uri: String, version: String): Boolean =
        record.receiverId == receiverId && record.libraryId == libraryId && record.contentUri == uri &&
            record.sourceVersion.isNotBlank() && record.sourceVersion == version && record.status == UploadStatus.COMPLETED

    fun shouldWithholdDeletes(fullAccess: Boolean, authoritative: Boolean, liveCount: Int, missing: Int, backedUp: Int): Boolean =
        !fullAccess || !authoritative || liveCount == 0 || (missing >= 20 && missing * 2 > backedUp)
}

enum class QueueOutcome { COMPLETE, RETRY, PAUSED, FAILED }
enum class TransferPhase { IDLE, PREPARING, WAITING_FOR_COMPUTER, WAITING_FOR_NETWORK, UPLOADING, VERIFYING, PAUSED, COMPLETE, FAILED }
enum class TransferReason {
    NONE, NETWORK_REQUIRED, UNMETERED_REQUIRED, COMPUTER_UNAVAILABLE, OUT_OF_SPACE,
    PAIRING_REQUIRED, PAIRING_CODE_INVALID, ACCESS_REVOKED, DESKTOP_UPDATE_REQUIRED, DESTINATION_CHANGED,
    RECEIVER_BUSY, SOURCE_UNAVAILABLE, CHECKSUM_MISMATCH,
    RECEIPT_PENDING, USER_PAUSED, ANDROID_INTERRUPTED, FILE_FAILURES, UNKNOWN,
}

internal class SourceReadException(cause: Exception) : IOException("The original could not be read", cause)

internal fun Exception.transferReason(): TransferReason = when (this) {
    is SourceReadException, is FileNotFoundException, is SecurityException -> TransferReason.SOURCE_UNAVAILABLE
    is TransferHttpException -> when {
        protocolCode == "DESTINATION_CHANGED" -> TransferReason.DESTINATION_CHANGED
        protocolCode == "PROTOCOL_UPDATE_REQUIRED" || code == 426 -> TransferReason.DESKTOP_UPDATE_REQUIRED
        protocolCode == "PAIRING_CODE_INVALID" -> TransferReason.PAIRING_CODE_INVALID
        protocolCode == "PAIRING_REQUIRED" -> TransferReason.PAIRING_REQUIRED
        code == 401 || code == 403 -> TransferReason.ACCESS_REVOKED
        protocolCode == "UPLOAD_BUSY" || code == 429 -> TransferReason.RECEIVER_BUSY
        code == 507 -> TransferReason.OUT_OF_SPACE
        code == 422 -> TransferReason.CHECKSUM_MISMATCH
        else -> if (retryable) TransferReason.COMPUTER_UNAVAILABLE else TransferReason.UNKNOWN
    }
    is IOException -> TransferReason.COMPUTER_UNAVAILABLE
    else -> TransferReason.UNKNOWN
}

/** An unavailable receiver must not turn untouched originals into permanent file failures. */
internal fun Exception.shouldRetryTransfer(): Boolean = when (this) {
    is SourceReadException, is FileNotFoundException, is SecurityException -> false
    is TransferHttpException -> retryable
    is IOException -> true
    else -> false
}

/** Constant-time byte accounting. Only the small visible sample is copied at the UI tick. */
internal class ProgressLedger(records: List<UploadRecord>, private val sampleLimit: Int = 100) {
    private val active = LinkedHashMap<Long, FileTransferProgress>()
    private val recent = ArrayDeque<FileTransferProgress>()
    private val positions = HashMap<Long, Long>()
    val totalFiles = records.size
    var totalBytes = records.sumOf { it.fileSize }; private set
    var completed = records.count { it.status == UploadStatus.COMPLETED }; private set
    var failed = records.count { it.status == UploadStatus.FAILED }; private set
    var skipped = records.count { it.status == UploadStatus.COMPLETED && it.skipped }; private set
    var sent = records.sumOf { it.sentBytes }; private set
    var saved = records.filter { it.status == UploadStatus.COMPLETED && !it.skipped }.sumOf { it.fileSize }; private set
    var acknowledged = records.sumOf { if (it.status == UploadStatus.COMPLETED) it.fileSize else it.acknowledgedBytes }; private set
    var resumed = records.filter { it.status != UploadStatus.COMPLETED }.sumOf { it.acknowledgedBytes }; private set
    private var lastSent = sent
    private var lastAt = System.nanoTime()

    @Synchronized fun start(record: UploadRecord) {
        positions[record.id] = record.acknowledgedBytes
        active[record.id] = record.progress(UploadStatus.UPLOADING, record.acknowledgedBytes)
    }
    @Synchronized fun wire(bytes: Long) { sent += bytes.coerceAtLeast(0) }
    @Synchronized fun sourcePrepared(before: UploadRecord, after: UploadRecord) {
        totalBytes += after.fileSize - before.fileSize
        acknowledge(after, after.acknowledgedBytes)
    }
    @Synchronized fun acknowledge(record: UploadRecord, offset: Long) {
        val before = positions.put(record.id, offset) ?: record.acknowledgedBytes
        acknowledged += offset - before
        active[record.id] = record.progress(UploadStatus.UPLOADING, offset)
    }
    @Synchronized fun finish(record: UploadRecord, status: UploadStatus, wasSkipped: Boolean = false) {
        acknowledge(record, if (status == UploadStatus.COMPLETED) record.fileSize else record.acknowledgedBytes)
        val item = active.remove(record.id) ?: record.progress(status, record.acknowledgedBytes)
        positions.remove(record.id)
        recent.addFirst(item.copy(status = status))
        while (recent.size > sampleLimit) recent.removeLast()
        if (status == UploadStatus.COMPLETED) { completed++; if (wasSkipped) skipped++ else saved += record.fileSize }
        if (status == UploadStatus.FAILED) failed++
    }
    @Synchronized fun snapshot(previous: TransferState): TransferState {
        val now = System.nanoTime()
        val elapsed = ((now - lastAt) / 1_000_000L).coerceAtLeast(1)
        val speed = (sent - lastSent) * 1000L / elapsed
        lastSent = sent; lastAt = now
        return previous.copy(totalFiles = totalFiles, completedFiles = completed, failedFiles = failed, skippedFiles = skipped,
            totalBytes = totalBytes, transferredBytes = acknowledged.coerceIn(0, totalBytes), sentBytes = sent, savedBytes = saved,
            resumedBytes = resumed, currentSpeedBytesPerSec = speed,
            pendingFiles = (totalFiles - completed - failed).coerceAtLeast(0), activeTransfers = active.values.toList() + recent.toList())
    }
    private fun UploadRecord.progress(status: UploadStatus, bytes: Long) = FileTransferProgress(id, fileName, contentUri, fileSize, bytes, status)
}

