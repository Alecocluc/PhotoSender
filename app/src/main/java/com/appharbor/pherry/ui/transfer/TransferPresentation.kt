package com.appharbor.pherry.ui.transfer

import com.appharbor.pherry.data.upload.TransferPhase
import com.appharbor.pherry.data.upload.TransferReason
import com.appharbor.pherry.data.upload.TransferState

internal enum class TransferAction { NONE, RETRY, CONNECT, SETTINGS, LIBRARY }

internal data class TransferCopy(
    val title: String,
    val detail: String,
    val action: TransferAction = TransferAction.NONE,
    val actionLabel: String? = null,
)

/** User-facing language belongs here. Diagnostic messages must never become product copy. */
internal fun transferCopy(state: TransferState, computer: String, connected: Boolean): TransferCopy {
    if (state.phase == TransferPhase.COMPLETE) return TransferCopy(
        "Backup finished", "All files in this backup are saved on $computer.")
    if (state.phase == TransferPhase.PAUSED) return TransferCopy(
        "Backup paused", "Your place is saved. Resume when you are ready.", TransferAction.RETRY, "Resume sending")
    reasonCopy(state.reason, computer)?.let { return it }
    return when (state.phase) {
        TransferPhase.PREPARING -> TransferCopy("Preparing your backup", "Checking the originals and available space on $computer.")
        TransferPhase.UPLOADING -> TransferCopy("Sending to $computer", "Your original photos and videos are being saved.")
        TransferPhase.VERIFYING -> TransferCopy("Verifying your backup", "Waiting for $computer to confirm that the files are saved.")
        TransferPhase.WAITING_FOR_NETWORK -> TransferCopy("Waiting for a network", "Connect to a network that can reach $computer. Your queue is saved.", TransferAction.RETRY, "Try again")
        TransferPhase.WAITING_FOR_COMPUTER -> TransferCopy(
            if (connected) "Waiting for the computer" else "Can't reach $computer",
            if (connected) "The backup is waiting for a response. Your place is saved." else "Open Pherry Desktop and connect both devices to the same network. Your queue is saved.",
            TransferAction.RETRY, "Try again")
        TransferPhase.FAILED -> TransferCopy("Backup needs attention", "Some files could not be saved. Your originals are still on this phone.", TransferAction.RETRY, "Try again")
        else -> TransferCopy("Ready to back up", "Choose photos from your library.")
    }
}

internal fun reasonCopy(reason: TransferReason, computer: String): TransferCopy? = when (reason) {
    TransferReason.NONE -> null
    TransferReason.NETWORK_REQUIRED -> TransferCopy("Waiting for a network", "Connect to a network that can reach $computer. Your queue is saved.", TransferAction.RETRY, "Try again")
    TransferReason.UNMETERED_REQUIRED -> TransferCopy("Waiting for an unmetered network", "Transfers are set to use an unmetered network. Join one, or change the transfer setting to use this network.", TransferAction.SETTINGS, "Network settings")
    TransferReason.COMPUTER_UNAVAILABLE -> TransferCopy("Computer is not responding", "Make sure Pherry Desktop is open on $computer and both devices are on the same network. Your place is saved.", TransferAction.RETRY, "Try again")
    TransferReason.OUT_OF_SPACE -> TransferCopy("The computer needs more space", "Free space in the backup folder on $computer, then try again. Files already saved stay there.", TransferAction.RETRY, "Try again")
    TransferReason.PAIRING_REQUIRED -> TransferCopy("Pair this phone", "Scan the ticket or enter the pairing code shown by Pherry Desktop.", TransferAction.CONNECT, "Pair computer")
    TransferReason.PAIRING_CODE_INVALID -> TransferCopy("That pairing code did not work", "Enter the current code shown by Pherry Desktop, or scan its pairing ticket.", TransferAction.CONNECT, "Try pairing again")
    TransferReason.ACCESS_REVOKED -> TransferCopy("Pairing needs to be renewed", "$computer no longer authorizes this phone. Scan its current pairing ticket to continue.", TransferAction.CONNECT, "Pair again")
    TransferReason.DESKTOP_UPDATE_REQUIRED -> TransferCopy("Update Pherry Desktop", "Install the latest version on $computer, then connect again. Your queue is saved.", TransferAction.CONNECT, "Connect again")
    TransferReason.DESTINATION_CHANGED -> TransferCopy("The backup folder changed", "Connect again to review the computer's current destination before continuing.", TransferAction.CONNECT, "Review computer")
    TransferReason.RECEIVER_BUSY -> TransferCopy("The computer is busy", "Pherry Desktop is finishing another operation. Your queue is saved; try again shortly.", TransferAction.RETRY, "Try again")
    TransferReason.SOURCE_UNAVAILABLE -> TransferCopy("Some originals need attention", "A file was removed, changed or is no longer accessible. Check photo access and review the files that did not send.", TransferAction.LIBRARY, "Open Library")
    TransferReason.CHECKSUM_MISMATCH -> TransferCopy("A file needs to be sent again", "Its contents did not match the original. Retrying reads the original again before sending it.", TransferAction.RETRY, "Retry files")
    TransferReason.RECEIPT_PENDING -> TransferCopy("Confirming the backup", "The files were sent. Pherry is waiting for $computer to confirm the saved backup.", TransferAction.RETRY, "Check again")
    TransferReason.USER_PAUSED -> TransferCopy("Backup paused", "Your place is saved. Resume when you are ready.", TransferAction.RETRY, "Resume sending")
    TransferReason.ANDROID_INTERRUPTED -> TransferCopy("Waiting to resume", "Android stopped this transfer. Your place is saved and the remaining files can continue.", TransferAction.RETRY, "Resume sending")
    TransferReason.FILE_FAILURES -> TransferCopy("Some files did not send", "Your other files are saved. Review the failed files or retry them.", TransferAction.RETRY, "Retry files")
    TransferReason.UNKNOWN -> TransferCopy("The backup could not continue", "Your place is saved. Check the connection and available space, then try again.", TransferAction.RETRY, "Try again")
}
