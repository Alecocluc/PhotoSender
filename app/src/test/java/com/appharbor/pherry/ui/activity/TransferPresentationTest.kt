package com.appharbor.pherry.ui.activity

import com.appharbor.pherry.data.upload.TransferPhase
import com.appharbor.pherry.data.upload.TransferState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
