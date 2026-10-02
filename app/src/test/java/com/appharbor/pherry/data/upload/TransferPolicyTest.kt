package com.appharbor.pherry.data.upload

import com.appharbor.pherry.data.db.UploadRecord
import com.appharbor.pherry.data.db.UploadStatus
import com.appharbor.pherry.data.network.TransferHttpException
import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class TransferPolicyTest {
    @Test fun receiverOutagesDuringPreparationAndSendingKeepTheQueueRetryable() {
        assertTrue(IOException("Connection reset").shouldRetryTransfer())
        for (code in listOf(408, 429, 500, 503)) {
            assertTrue("HTTP $code must preserve pending work", TransferHttpException(code, "Receiver unavailable").shouldRetryTransfer())
        }
        assertTrue(TransferHttpException(409, "Offset moved", "OFFSET_CHANGED").shouldRetryTransfer())
        assertTrue(TransferHttpException(409, "Chunk in progress", "UPLOAD_BUSY").shouldRetryTransfer())
        assertTrue(TransferHttpException(410, "Upload expired", "UPLOAD_EXPIRED").shouldRetryTransfer())
    }

    @Test fun missingSourcesAndInvalidContentRequireExplicitCorrection() {
        assertFalse(FileNotFoundException("Original removed").shouldRetryTransfer())
        assertFalse(SecurityException("Access revoked").shouldRetryTransfer())
        assertFalse(SourceReadException(IOException("Provider cannot read file")).shouldRetryTransfer())
        for (code in listOf(400, 401, 403, 404, 409, 410, 413, 422, 426, 507)) {
            assertFalse(TransferHttpException(code, "Invalid file").shouldRetryTransfer())
        }
    }

    @Test fun permanentProtocolReasonsRemainActionableInsteadOfLookingOffline() {
        assertEquals(TransferReason.PAIRING_REQUIRED, TransferHttpException(401, "Denied", "PAIRING_REQUIRED").transferReason())
        assertEquals(TransferReason.PAIRING_CODE_INVALID, TransferHttpException(401, "Wrong ticket", "PAIRING_CODE_INVALID").transferReason())
        assertEquals(TransferReason.ACCESS_REVOKED, TransferHttpException(403, "Revoked").transferReason())
        assertEquals(TransferReason.DESTINATION_CHANGED, TransferHttpException(409, "Different folder", "DESTINATION_CHANGED").transferReason())
        assertEquals(TransferReason.OUT_OF_SPACE, TransferHttpException(507, "Full").transferReason())
        assertEquals(TransferReason.UPGRADE_REVIEW_REQUIRED, TransferHttpException(409, "Move needs attention", "LEGACY_REVIEW_REQUIRED").transferReason())
        assertFalse(TransferHttpException(409, "Job has unfinished sessions", "JOB_HAS_PENDING_UPLOADS").shouldRetryTransfer())
    }

    private fun record(id: Long = 1, bytes: Long = 100, offset: Long = 0) = UploadRecord(id = id,
        mediaStoreId = id, contentUri = "content://media/$id", fileName = "$id.jpg", bucketName = "Camera",
        fileSize = bytes, receiverId = "pc-a", libraryId = "folder-a", sourceVersion = "generation:1:2:$bytes",
        acknowledgedBytes = offset)

    @Test fun receiptRequiresDestinationFolderAndCurrentSourceVersion() {
        val saved = record().copy(status = UploadStatus.COMPLETED)
        assertTrue(ReceiptPolicy.matches(saved, "pc-a", "folder-a", saved.contentUri, saved.sourceVersion))
        assertFalse(ReceiptPolicy.matches(saved, "pc-b", "folder-a", saved.contentUri, saved.sourceVersion))
        assertFalse(ReceiptPolicy.matches(saved, "pc-a", "folder-b", saved.contentUri, saved.sourceVersion))
        assertFalse(ReceiptPolicy.matches(saved, "pc-a", "folder-a", saved.contentUri, "edited"))
        assertFalse(ReceiptPolicy.matches(saved.copy(sourceVersion = ""), "pc-a", "folder-a", saved.contentUri, ""))
        assertNotEquals(ReceiptPolicy.key("a", "bc", "u", "v"), ReceiptPolicy.key("ab", "c", "u", "v"))
    }

    @Test fun deletingRequiresFullAccessAndAnAuthoritativeNonemptyScan() {
        assertTrue(ReceiptPolicy.shouldWithholdDeletes(false, true, 95, 5, 100))
        assertTrue(ReceiptPolicy.shouldWithholdDeletes(true, false, 95, 5, 100))
        assertTrue(ReceiptPolicy.shouldWithholdDeletes(true, true, 0, 100, 100))
        assertTrue(ReceiptPolicy.shouldWithholdDeletes(true, true, 10, 90, 100))
        assertFalse(ReceiptPolicy.shouldWithholdDeletes(true, true, 95, 5, 100))
    }

    @Test fun restartingAtSmallerServerOffsetDoesNotInflateProgress() {
        val file = record(offset = 80)
        val ledger = ProgressLedger(listOf(file))
        ledger.start(file)
        ledger.acknowledge(file, 40)
        assertEquals(40L, ledger.snapshot(TransferState()).transferredBytes)
        ledger.wire(60)
        ledger.acknowledge(file, 100)
        ledger.finish(file.copy(acknowledgedBytes = 100), UploadStatus.COMPLETED)
        val state = ledger.snapshot(TransferState())
        assertEquals(100L, state.transferredBytes)
        assertEquals(60L, state.sentBytes)
        assertEquals(1, state.completedFiles)
        assertEquals(0, state.pendingFiles)
    }

    @Test fun skippedContentCompletesWithoutInventingNetworkBytes() {
        val file = record()
        val ledger = ProgressLedger(listOf(file))
        ledger.start(file)
        ledger.finish(file.copy(acknowledgedBytes = 100), UploadStatus.COMPLETED, true)
        val state = ledger.snapshot(TransferState())
        assertEquals(100L, state.transferredBytes)
        assertEquals(0L, state.sentBytes)
        assertEquals(1, state.skippedFiles)
    }

    @Test fun twentyThousandFilesKeepOnlyABoundedVisibleSample() {
        val records = (1L..20_000L).map { record(it) }
        val ledger = ProgressLedger(records)
        for (file in records) {
            ledger.start(file)
            ledger.finish(file.copy(acknowledgedBytes = file.fileSize), UploadStatus.COMPLETED)
        }
        val state = ledger.snapshot(TransferState())
        assertEquals(20_000, state.completedFiles)
        assertEquals(2_000_000L, state.transferredBytes)
        assertTrue(state.activeTransfers.size <= 100)
    }
}
