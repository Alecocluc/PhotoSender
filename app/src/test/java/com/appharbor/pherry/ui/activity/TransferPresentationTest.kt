package com.appharbor.pherry.ui.activity

import com.appharbor.pherry.data.upload.TransferPhase
import com.appharbor.pherry.data.upload.TransferState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import com.appharbor.pherry.data.upload.TransferReason
import com.appharbor.pherry.data.model.ConnectionState
import com.appharbor.pherry.ui.transfer.TransferAction
import com.appharbor.pherry.ui.transfer.transferCopy
import com.appharbor.pherry.ui.home.HomeState
import com.appharbor.pherry.ui.home.UnsentState
import com.appharbor.pherry.ui.home.envelopeState
import com.appharbor.pherry.ui.home.homeTransferState
import org.junit.Test

class TransferPresentationTest {
    @Test fun constraintsDoNotLookLikeAnUpload() {
        val transfer = TransferState(phase = TransferPhase.WAITING_FOR_NETWORK, isTransferring = true, totalFiles = 20)
        assertEquals(JobPhase.Waiting, jobPhase(transfer, 20, true))
    }

    @Test fun allBytesSentIsNotFinishedUntilVerificationCompletes() {
        val transfer = TransferState(phase = TransferPhase.VERIFYING, totalBytes = 100, transferredBytes = 100, totalFiles = 1)
        assertEquals(JobPhase.Verifying, jobPhase(transfer, 1, true))
    }

    @Test fun durablePauseWinsOverStaleBatchCounters() {
        val transfer = TransferState(phase = TransferPhase.PAUSED, totalFiles = 5, completedFiles = 5)
        assertEquals(JobPhase.Paused, jobPhase(transfer, 5, true))
    }

    @Test fun failedJobNeverLooksFinished() {
        val transfer = TransferState(phase = TransferPhase.FAILED, totalFiles = 2, failedFiles = 2)
        assertEquals(JobPhase.Failed, jobPhase(transfer, 0, true))
    }

    @Test fun restartedQueueShowsItsConnectionRequirement() {
        assertEquals(JobPhase.Disconnected, jobPhase(TransferState(), 8, false))
        assertEquals(JobPhase.Queued, jobPhase(TransferState(), 8, true))
        assertNull(jobPhase(TransferState(), 0, true))
    }

    @Test fun rawDiagnosticsNeverBecomeTransferCopy() {
        val copy = transferCopy(TransferState(phase = TransferPhase.WAITING_FOR_COMPUTER,
            message = "java.net.SocketTimeoutException: secret host"), "Laptop", connected = true)
        assertFalse(copy.title.contains("Not connected", ignoreCase = true))
        assertFalse(copy.detail.contains("SocketTimeoutException"))
        assertEquals("Try again", copy.actionLabel)
    }

    @Test fun genericNetworkWaitDoesNotInventAnUnmeteredRequirement() {
        val copy = transferCopy(TransferState(phase = TransferPhase.WAITING_FOR_NETWORK,
            reason = TransferReason.NETWORK_REQUIRED), "Laptop", false)
        assertFalse((copy.title + copy.detail).contains("unmetered", ignoreCase = true))
        assertEquals(TransferAction.RETRY, copy.action)
    }

    @Test fun unmeteredSettingOffersSettingsWithoutClaimingCurrentNetworkType() {
        val copy = transferCopy(TransferState(phase = TransferPhase.WAITING_FOR_NETWORK,
            reason = TransferReason.UNMETERED_REQUIRED), "Laptop", false)
        assertEquals(TransferAction.SETTINGS, copy.action)
        assertFalse(copy.detail.contains("This network is metered"))
    }

    @Test fun revokedPairingAndWrongCodeHaveDifferentRecoveryCopy() {
        val revoked = transferCopy(TransferState(reason = TransferReason.ACCESS_REVOKED), "Laptop", false)
        val invalid = transferCopy(TransferState(reason = TransferReason.PAIRING_CODE_INVALID), "Laptop", false)
        assertEquals(TransferAction.CONNECT, revoked.action)
        assertEquals(TransferAction.CONNECT, invalid.action)
        assertTrue(revoked.detail.contains("no longer authorizes"))
        assertTrue(invalid.detail.contains("current code"))
    }

    @Test fun homeWaitingIsNotSendingEvenWithActiveWorker() {
        for (phase in listOf(TransferPhase.WAITING_FOR_NETWORK, TransferPhase.WAITING_FOR_COMPUTER)) {
            val state = TransferState(phase = phase, isTransferring = true, pendingFiles = 4)
            assertEquals(HomeState.Waiting, envelopeState(true, ConnectionState.CONNECTED, null,
                true, state, queuedCount = 4, unsent = UnsentState()))
            assertEquals(TransferAction.RETRY, transferCopy(state, "Laptop", true).action)
        }
    }

    @Test fun upgradeGateOffersReviewInsteadOfBlindRetry() {
        val copy = transferCopy(TransferState(phase = TransferPhase.FAILED,
            reason = TransferReason.UPGRADE_REVIEW_REQUIRED), "Laptop", true)
        assertEquals(TransferAction.REVIEW_UPGRADE, copy.action)
    }

    @Test fun completionIsAuthoritativeDespitePreviousTransientReason() {
        val copy = transferCopy(TransferState(phase = TransferPhase.COMPLETE,
            reason = TransferReason.COMPUTER_UNAVAILABLE), "Laptop", true)
        assertEquals("Backup finished", copy.title)
        assertEquals(TransferAction.NONE, copy.action)
    }

    @Test fun homeShowsRevokedPairingAfterAnEarlierCompletedJob() {
        val visible = homeTransferState(TransferState(phase = TransferPhase.COMPLETE), false, TransferReason.ACCESS_REVOKED)
        assertEquals(TransferAction.CONNECT, transferCopy(visible, "Laptop", false).action)
    }

    @Test fun upgradeReviewDoesNotPresentUnverifiedLegacyFilesAsNew() {
        val previous = TransferState(phase = TransferPhase.COMPLETE, completedFiles = 3)
        val library = UnsentState(count = 52, libraryCount = 55, backedUpCount = 3, computed = true)
        assertEquals(HomeState.Attention, envelopeState(true, ConnectionState.CONNECTED, null,
            true, previous, queuedCount = 0, unsent = library, upgradeReviewRequired = true))
        val copy = transferCopy(homeTransferState(previous, true, TransferReason.NONE, true), "Laptop", true)
        assertEquals("Review backup", copy.actionLabel)
        assertTrue(copy.detail.contains("compared"))
        assertFalse(copy.title.contains("new", ignoreCase = true))
    }
}
