package com.appharbor.pherry.ui.activity

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.appharbor.pherry.data.db.UploadStatus
import com.appharbor.pherry.data.upload.DedupSkip
import com.appharbor.pherry.data.upload.FileTransferProgress
import com.appharbor.pherry.data.upload.TransferState
import com.appharbor.pherry.ui.components.EdgeText
import com.appharbor.pherry.ui.components.EmptyStrip
import com.appharbor.pherry.ui.components.Envelope
import com.appharbor.pherry.ui.components.FilmRow
import com.appharbor.pherry.ui.components.Fmt
import com.appharbor.pherry.ui.components.Frame
import com.appharbor.pherry.ui.components.Hairline
import com.appharbor.pherry.ui.components.JobBar
import com.appharbor.pherry.ui.components.Lamp
import com.appharbor.pherry.ui.components.LampState
import com.appharbor.pherry.ui.components.LocalSnackbarHost
import com.appharbor.pherry.ui.components.Notice
import com.appharbor.pherry.ui.components.Ph
import com.appharbor.pherry.ui.components.PhIcon
import com.appharbor.pherry.ui.components.PrintButton
import com.appharbor.pherry.ui.components.PrintButtonStyle
import com.appharbor.pherry.ui.components.SectionHeading
import com.appharbor.pherry.ui.theme.PherryShape
import com.appharbor.pherry.ui.theme.PherryTheme
import com.appharbor.pherry.ui.theme.Spacing
import kotlinx.coroutines.launch

private const val MAX_FAILED_ROWS = 20
private const val SHOWN_SKIPS = 3

/**
 * Where the job on this phone stands. [Paused] is a batch that ended with files still queued
 * (stopped, or interrupted); [Queued] is queued work with no batch in memory (after a restart).
 * [Disconnected] is either of those while the computer is unreachable: resuming can't work yet.
 */
private enum class JobPhase { Starting, Sending, Paused, Disconnected, Finished, Queued }

/** The in-memory batch split by status in one pass (a batch can hold 20,000+ files). */
private class BatchBreakdown(
    val uploading: List<FileTransferProgress>,
    val pending: Int,
    val failed: List<FileTransferProgress>,
)

private fun breakdown(active: List<FileTransferProgress>): BatchBreakdown {
    val uploading = ArrayList<FileTransferProgress>()
    val failed = ArrayList<FileTransferProgress>()
    var pending = 0
    for (t in active) {
        when (t.status) {
            UploadStatus.UPLOADING -> uploading.add(t)
            UploadStatus.FAILED -> failed.add(t)
            UploadStatus.PENDING -> pending++
            UploadStatus.COMPLETED -> Unit
        }
    }
    return BatchBreakdown(uploading, pending, failed)
}

/** Null when there is nothing to show at all. */
private fun jobPhase(transfer: TransferState, queuedCount: Int, connected: Boolean): JobPhase? = when {
    transfer.isTransferring && transfer.activeTransfers.isEmpty() -> JobPhase.Starting
    transfer.isTransferring -> JobPhase.Sending
    transfer.totalFiles > 0 && transfer.completedFiles + transfer.failedFiles < transfer.totalFiles ->
        if (connected) JobPhase.Paused else JobPhase.Disconnected
    transfer.totalFiles > 0 -> JobPhase.Finished
    queuedCount > 0 -> if (connected) JobPhase.Queued else JobPhase.Disconnected
    else -> null
}

/** Queued work with no batch in memory: the envelope shows the queued count, not batch progress. */
private fun showsQueueOnly(phase: JobPhase, transfer: TransferState): Boolean =
    phase == JobPhase.Queued || (phase == JobPhase.Disconnected && transfer.totalFiles == 0)

@Composable
internal fun TransfersNow(
    transfer: TransferState,
    queuedCount: Int,
    failedCount: Int,
    computer: String,
    connected: Boolean,
    listState: LazyListState,
    onStop: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onConnect: () -> Unit,
    onOpenLibrary: () -> Unit,
    /** Runs before Resume or Retry starts sending (the shell asks for notification permission here). */
    onBeforeTransfer: () -> Unit = {},
) {
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val phase = jobPhase(transfer, queuedCount, connected)
    val batch = remember(transfer.activeTransfers) { breakdown(transfer.activeTransfers) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, top = Spacing.lg, bottom = Spacing.xxl),
    ) {
        if (phase == null) {
            item(key = "empty") {
                EmptyStrip(
                    title = "Nothing is sending",
                    body = "Back up from Home, or pick photos in the Library.",
                    action = {
                        PrintButton("Open Library", onClick = onOpenLibrary, style = PrintButtonStyle.Outline, icon = Ph.Images)
                    },
                )
            }
        } else {
            item(key = "job") {
                JobEnvelope(
                    phase = phase,
                    transfer = transfer,
                    queuedCount = queuedCount,
                    computer = computer,
                    connected = connected,
                    onStop = {
                        onStop()
                        scope.launch { snackbar.showSnackbar("Stopped. The rest stay queued until you resume.") }
                    },
                    onResume = {
                        onBeforeTransfer()
                        onResume()
                    },
                    onConnect = onConnect,
                )
            }

            if (batch.uploading.isNotEmpty()) {
                item(key = "line-head") {
                    SectionHeading("On the line now", modifier = Modifier.padding(top = Spacing.xl))
                }
                item(key = "line-strip") {
                    DevelopingStrip(files = batch.uploading, modifier = Modifier.padding(top = Spacing.md))
                }
            }

            if (!showsQueueOnly(phase, transfer)) {
                item(key = "ledger") {
                    BatchLedger(
                        waiting = if (transfer.activeTransfers.isNotEmpty()) batch.pending else queuedCount,
                        sendingNow = batch.uploading.size,
                        sent = (transfer.completedFiles - transfer.skippedFiles).coerceAtLeast(0),
                        alreadyThere = transfer.skippedFiles,
                        failed = transfer.failedFiles,
                        computer = computer,
                        modifier = Modifier.padding(top = Spacing.xl),
                    )
                }
            }
        }

        if (batch.failed.isNotEmpty()) {
            item(key = "failed-head") {
                SectionHeading("Didn't send", modifier = Modifier.padding(top = Spacing.xl))
            }
            items(batch.failed.take(MAX_FAILED_ROWS), key = { "failed-${it.recordId}" }) { file ->
                FailedRow(file)
                Hairline()
            }
            if (batch.failed.size > MAX_FAILED_ROWS) {
                item(key = "failed-more") {
                    MoreLine("and ${Fmt.count(batch.failed.size - MAX_FAILED_ROWS)} more")
                }
            }
        }

        // Retry covers every failed record on the phone, not only this batch's, so it uses the database count.
        if (failedCount > 0 && !transfer.isTransferring) {
            item(key = "retry") {
                Notice(
                    title = "${Fmt.plural(failedCount, "file")} didn't reach $computer",
                    detail = "Nothing was lost on this phone. Check there's space on $computer, then retry.",
                    actionLabel = "Retry ${Fmt.count(failedCount)}",
                    onAction = {
                        onBeforeTransfer()
                        onRetry()
                    },
                    modifier = Modifier.padding(top = Spacing.lg),
                )
            }
        }

        if (transfer.skippedDuplicates.isNotEmpty()) {
            item(key = "skipped") {
                SkippedSection(
                    skips = transfer.skippedDuplicates,
                    total = transfer.skippedFiles,
                    batch = transfer.activeTransfers,
                    computer = computer,
                    modifier = Modifier.padding(top = Spacing.xl),
                )
            }
        }
    }
}

// ── The job envelope ─────────────────────────────────────────────────────────

@Composable
private fun JobEnvelope(
    phase: JobPhase,
    transfer: TransferState,
    queuedCount: Int,
    computer: String,
    connected: Boolean,
    onStop: () -> Unit,
    onResume: () -> Unit,
    onConnect: () -> Unit,
) {
    val c = PherryTheme.colors
    val progress by animateFloatAsState(transfer.progressPercent.coerceIn(0f, 1f), tween(300), label = "job-progress")
    val busy = phase == JobPhase.Starting || phase == JobPhase.Sending
    val queueOnly = showsQueueOnly(phase, transfer)

    // Worked out first so the lamp can announce how the job ended. A queue with no batch has no outcome yet.
    val outcome = if (queueOnly) emptyList() else buildList {
        if (transfer.failedFiles > 0) add("${Fmt.count(transfer.failedFiles)} failed")
        if (transfer.skippedFiles > 0) add("${Fmt.count(transfer.skippedFiles)} already on $computer")
    }
    val note = when {
        // Only this batch is known to be done: other queued or failed files may still exist.
        phase == JobPhase.Finished && transfer.failedFiles == 0 -> "Everything in this batch is on $computer."
        phase == JobPhase.Paused && queuedCount > 0 -> "${Fmt.plural(queuedCount, "file")} still to send."
        phase == JobPhase.Disconnected && queuedCount > 0 ->
            "${Fmt.plural(queuedCount, "file")} still to send. Reconnect to $computer to finish."
        phase == JobPhase.Disconnected -> "Reconnect to $computer to finish."
        else -> null
    }
    val label = when (phase) {
        JobPhase.Starting, JobPhase.Sending -> "Sending to $computer"
        JobPhase.Paused -> "Paused"
        JobPhase.Disconnected -> "Not connected to $computer"
        JobPhase.Finished -> "Finished"
        JobPhase.Queued -> "Waiting to send to $computer"
    }
    // The only live region on this screen: it speaks when the job changes phase, never on each tick.
    val announcement = when (phase) {
        JobPhase.Starting, JobPhase.Sending, JobPhase.Queued -> label
        JobPhase.Paused, JobPhase.Disconnected -> listOfNotNull(label, note).joinToString(". ")
        JobPhase.Finished -> listOfNotNull(label, outcome.joinToString(", ").ifEmpty { null }, note).joinToString(". ")
    }
    // The data line under the count: state and destination first, then the numbers.
    val data = buildList {
        when (phase) {
            JobPhase.Starting -> add("Starting…")
            JobPhase.Paused -> add("Paused")
            JobPhase.Finished -> add("Finished")
            else -> Unit
        }
        add(if (phase == JobPhase.Disconnected) "Not connected to $computer" else "to $computer")
        if (!queueOnly && transfer.totalBytes > 0) add("${Fmt.bytes(transfer.transferredBytes)} of ${Fmt.bytes(transfer.totalBytes)}")
        if (phase == JobPhase.Sending) {
            if (transfer.currentSpeedBytesPerSec > 0) add(Fmt.speed(transfer.currentSpeedBytesPerSec))
            Fmt.remaining(transfer.estimatedSecondsRemaining).takeIf { it.isNotEmpty() }?.let(::add)
        }
    }

    Envelope {
        if (queueOnly) {
            val caption = if (queuedCount == 1) "file is queued, waiting to send" else "files are queued, waiting to send"
            Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "${Fmt.count(queuedCount)} $caption" }) {
                Text(Fmt.count(queuedCount), style = MaterialTheme.typography.displayLarge, color = c.onEnvelope)
                Text(caption, style = MaterialTheme.typography.bodyMedium, color = c.onEnvelope)
            }
        } else {
            val done = transfer.completedFiles
            val total = transfer.totalFiles
            val percent = (progress * 100).toInt()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {
                        contentDescription = if (total > 0) {
                            "${Fmt.count(done)} of ${Fmt.plural(total, "file")} done, $percent percent"
                        } else {
                            "Getting the files ready"
                        }
                    },
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(Fmt.count(done), style = MaterialTheme.typography.displayLarge, color = c.onEnvelope)
                Text(
                    " / ${if (total > 0) Fmt.count(total) else "…"}",
                    style = MaterialTheme.typography.headlineMedium,
                    color = c.onEnvelope2,
                    modifier = Modifier.padding(bottom = Spacing.sm),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "$percent%",
                    style = MaterialTheme.typography.headlineMedium,
                    color = c.onEnvelope,
                    modifier = Modifier.padding(bottom = Spacing.sm),
                )
            }
            Spacer(Modifier.height(Spacing.md))
            JobBar(progress)
        }

        // Outside the branches above so the lamp stays one node and its announcement survives a phase
        // change. The numbers beside it change on every tick, so only the lamp's description is live.
        Spacer(Modifier.height(Spacing.sm))
        Row {
            Box(
                Modifier
                    .padding(top = Spacing.xxs)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = announcement
                    },
            ) {
                Lamp(if (busy) LampState.Busy else LampState.Idle, onEnvelope = true)
            }
            Spacer(Modifier.width(Spacing.sm))
            Text(
                data.joinToString(" · ").uppercase(),
                style = PherryTheme.text.monoCaps,
                color = c.onEnvelope2,
                modifier = Modifier.weight(1f),
            )
        }

        if (outcome.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.md))
            Text(outcome.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = c.onEnvelope)
        }
        if (note != null) {
            Spacer(Modifier.height(if (outcome.isEmpty()) Spacing.md else Spacing.xxs))
            // Already spoken by the lamp's announcement.
            Text(note, style = MaterialTheme.typography.bodyMedium, color = c.onEnvelope, modifier = Modifier.clearAndSetSemantics {})
        }

        when {
            busy -> {
                Spacer(Modifier.height(Spacing.lg))
                PrintButton("Stop", onClick = onStop, style = PrintButtonStyle.Outline, icon = Ph.Stop, onEnvelope = true)
            }
            // Resuming can't reach the computer from here: offer the way back to it instead (as Home does).
            !connected && (queuedCount > 0 || phase == JobPhase.Disconnected) -> {
                Spacer(Modifier.height(Spacing.lg))
                PrintButton(
                    text = "Reconnect",
                    onClick = onConnect,
                    style = PrintButtonStyle.Outline,
                    icon = Ph.Desktop,
                    onEnvelope = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            queuedCount > 0 -> {
                Spacer(Modifier.height(Spacing.lg))
                PrintButton(
                    text = "Resume sending",
                    onClick = onResume,
                    icon = Ph.Play,
                    onEnvelope = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ── On the line now ──────────────────────────────────────────────────────────

private fun sentFraction(file: FileTransferProgress): Float =
    if (file.fileSize > 0) (file.bytesTransferred.toFloat() / file.fileSize).coerceIn(0f, 1f) else 0f

/**
 * The files uploading right now as one strip of film. Each frame develops from the orange negative
 * into the print as its bytes reach the computer.
 */
@Composable
private fun DevelopingStrip(files: List<FileTransferProgress>, modifier: Modifier = Modifier) {
    // Pherry sends 3 files at once, or 6 with faster transfers on: one slot per frame.
    val columns = if (files.size > 3) 6 else 3
    val shown = files.take(columns)
    FilmRow(
        count = shown.size,
        columns = columns,
        sprockets = true,
        modifier = modifier,
        edgeTop = { i -> EdgeText("${(sentFraction(shown[i]) * 100).toInt()}%") },
        edgeBottom = { i -> EdgeText(Fmt.bytes(shown[i].fileSize), dim = true) },
    ) { i ->
        // Keyed by record so a slot that picks up the next file starts again from the negative.
        key(shown[i].recordId) { DevelopingFrame(shown[i]) }
    }
}

@Composable
private fun DevelopingFrame(file: FileTransferProgress) {
    val c = PherryTheme.colors
    val fraction = sentFraction(file)
    val develop by animateFloatAsState(fraction, tween(400), label = "develop")
    val description = "${file.fileName}, ${(fraction * 100).toInt()} percent sent"
    if (canPreview(file.fileName, file.contentUri)) {
        Frame(
            model = file.contentUri,
            contentDescription = description,
            aspectRatio = 1f,
            develop = develop,
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        // No still to develop: an unexposed frame with the file's kind and a strip of edge orange filling as it sends.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(PherryShape.frame)
                .background(c.film2)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            PhIcon(if (Fmt.isVideoName(file.fileName)) Ph.Video else Ph.File, contentDescription = null, tint = c.filmInk)
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(develop)
                    .height(3.dp)
                    .background(c.edge),
            )
        }
    }
}

// ── Ledger ───────────────────────────────────────────────────────────────────

@Composable
private fun BatchLedger(
    waiting: Int,
    sendingNow: Int,
    sent: Int,
    alreadyThere: Int,
    failed: Int,
    computer: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Hairline()
        LedgerRow(Ph.Hourglass, waiting, "waiting")
        if (sendingNow > 0) {
            Hairline()
            LedgerRow(Ph.Upload, sendingNow, "sending now")
        }
        Hairline()
        LedgerRow(Ph.CheckCircle, sent, "sent")
        if (alreadyThere > 0) {
            Hairline()
            LedgerRow(Ph.Copy, alreadyThere, "already on $computer")
        }
        if (failed > 0) {
            Hairline()
            LedgerRow(Ph.WarningCircle, failed, "failed", danger = true)
        }
        Hairline()
    }
}

@Composable
private fun LedgerRow(@DrawableRes icon: Int, count: Int, label: String, danger: Boolean = false) {
    val c = PherryTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touch)
            .semantics(mergeDescendants = true) { contentDescription = "${Fmt.count(count)} $label" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhIcon(icon, contentDescription = null, tint = if (danger) c.red else c.ink2, size = 20.dp)
        Spacer(Modifier.width(Spacing.md))
        Text(
            Fmt.count(count),
            style = PherryTheme.text.mono,
            color = if (danger) c.red else c.ink,
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = c.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// ── Failed ───────────────────────────────────────────────────────────────────

@Composable
private fun FailedRow(file: FileTransferProgress) {
    val c = PherryTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(vertical = Spacing.sm)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Frame(
            model = file.contentUri.takeIf { canPreview(file.fileName, it) },
            contentDescription = null,
            isVideo = Fmt.isVideoName(file.fileName),
            failed = true,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(file.fileName, style = MaterialTheme.typography.titleSmall, color = c.ink, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(Fmt.bytes(file.fileSize), style = PherryTheme.text.mono, color = c.ink2, maxLines = 1)
        }
    }
}

@Composable
private fun MoreLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = PherryTheme.colors.ink2,
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.md),
    )
}

// ── Already on the computer ──────────────────────────────────────────────────

@Composable
private fun SkippedSection(
    skips: List<DedupSkip>,
    total: Int,
    batch: List<FileTransferProgress>,
    computer: String,
    modifier: Modifier = Modifier,
) {
    val c = PherryTheme.colors
    val shown = skips.take(SHOWN_SKIPS)
    // DedupSkip carries no URI; borrow it from the batch so the row can show the picture.
    val uris = remember(shown) {
        val ids = shown.mapTo(HashSet()) { it.skippedRecordId }
        batch.asSequence().filter { it.recordId in ids }.associate { it.recordId to it.contentUri }
    }
    val more = (maxOf(total, skips.size) - shown.size).coerceAtLeast(0)
    Column(modifier.fillMaxWidth()) {
        SectionHeading("Already on $computer")
        Text(
            "These were identical to files $computer already has, so nothing was sent.",
            style = MaterialTheme.typography.bodySmall,
            color = c.ink2,
            modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xs),
        )
        shown.forEach { skip ->
            SkipRow(skip = skip, contentUri = uris[skip.skippedRecordId].orEmpty())
            Hairline()
        }
        if (more > 0) MoreLine("and ${Fmt.count(more)} more")
    }
}

@Composable
private fun SkipRow(skip: DedupSkip, contentUri: String) {
    val c = PherryTheme.colors
    val sameAs = when {
        skip.matchedFileName != null && skip.matchedBucketName != null -> "Same as ${skip.matchedFileName} in ${skip.matchedBucketName}"
        skip.matchedFileName != null -> "Same as ${skip.matchedFileName}"
        else -> "Same as a file already there"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(vertical = Spacing.sm)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Frame(
            model = contentUri.takeIf { canPreview(skip.skippedFileName, it) },
            contentDescription = null,
            isVideo = Fmt.isVideoName(skip.skippedFileName),
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(skip.skippedFileName, style = MaterialTheme.typography.titleSmall, color = c.ink, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(sameAs, style = MaterialTheme.typography.bodySmall, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
