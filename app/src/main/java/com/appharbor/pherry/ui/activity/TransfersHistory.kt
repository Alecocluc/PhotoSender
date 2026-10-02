package com.appharbor.pherry.ui.activity

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.appharbor.pherry.data.db.UploadRecord
import com.appharbor.pherry.data.upload.VerifyState
import com.appharbor.pherry.ui.components.EmptyStrip
import com.appharbor.pherry.ui.components.Fmt
import com.appharbor.pherry.ui.components.Frame
import com.appharbor.pherry.ui.components.GuideWords
import com.appharbor.pherry.ui.components.Hairline
import com.appharbor.pherry.ui.components.JobBar
import com.appharbor.pherry.ui.components.LocalSnackbarHost
import com.appharbor.pherry.ui.components.Notice
import com.appharbor.pherry.ui.components.Ph
import com.appharbor.pherry.ui.components.PhIcon
import com.appharbor.pherry.ui.components.PrintButton
import com.appharbor.pherry.ui.components.PrintButtonStyle
import com.appharbor.pherry.ui.history.HistoryViewModel
import com.appharbor.pherry.ui.theme.PherryShape
import com.appharbor.pherry.ui.theme.PherryTheme
import com.appharbor.pherry.ui.theme.Spacing
import kotlinx.coroutines.launch
import java.util.Calendar

/** One day of the ledger, newest first. */
private class DayGroup(val key: Long, val label: String, val records: List<UploadRecord>)

private fun groupByDay(records: List<UploadRecord>): List<DayGroup> {
    val cal = Calendar.getInstance()
    val days = LinkedHashMap<Long, MutableList<UploadRecord>>()
    for (record in records) {
        val key = if (record.uploadedAt <= 0) -1L else {
            cal.timeInMillis = record.uploadedAt
            cal.get(Calendar.YEAR) * 1000L + cal.get(Calendar.DAY_OF_YEAR)
        }
        days.getOrPut(key) { mutableListOf() }.add(record)
    }
    return days.map { (key, rs) -> DayGroup(key, Fmt.day(rs.first().uploadedAt), rs) }
}

@Composable
internal fun TransfersHistory(
    records: List<UploadRecord>?,
    completedCount: Int,
    historyCount: Int,
    failedCount: Int,
    totalBytes: Long,
    lastSentAt: Long,
    verify: VerifyState,
    connected: Boolean,
    isTransferring: Boolean,
    computer: String,
    listState: LazyListState,
    onCheck: () -> Unit,
    onAudit: () -> Unit,
    onRetry: () -> Unit,
    onClear: () -> Unit,
    /** Runs before Retry starts sending (the shell asks for notification permission here). */
    onBeforeTransfer: () -> Unit = {},
) {
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    var askClear by rememberSaveable { mutableStateOf(false) }

    // The 500-row ledger query runs only while this tab is on screen (the same ViewModel instance the
    // screen collects [records] from; it keeps the last list while hidden).
    val historyViewModel: HistoryViewModel = hiltViewModel()
    DisposableEffect(historyViewModel) {
        historyViewModel.setHistoryShown(true)
        onDispose { historyViewModel.setHistoryShown(false) }
    }

    if (askClear) {
        val c = PherryTheme.colors
        AlertDialog(
            onDismissRequest = { askClear = false },
            title = { Text("Clear transfer history?") },
            text = {
                Text(
                    "Hide completed transfers from this list. Files on $computer and their backed-up status stay saved."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askClear = false
                    onClear()
                    scope.launch { snackbar.showSnackbar("Transfer history cleared.") }
                }) { Text("Clear history", color = c.red) }
            },
            dismissButton = {
                TextButton(onClick = { askClear = false }) { Text("Cancel", color = c.ink) }
            },
        )
    }

    // Until the database answers, draw nothing rather than flashing the empty state.
    if (records == null) {
        Box(Modifier.fillMaxSize())
        return
    }

    val days = remember(records) { groupByDay(records) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, top = Spacing.lg, bottom = Spacing.xxl),
    ) {
        if (records.isNotEmpty() || completedCount > 0) {
            item(key = "summary") {
                SummaryBlock(
                    completedCount = completedCount,
                    totalBytes = totalBytes,
                    lastSentAt = lastSentAt,
                    verify = verify,
                    connected = connected,
                    computer = computer,
                    onCheck = onCheck,
                    onAudit = onAudit,
                )
            }
        }

        if (failedCount > 0 && !isTransferring) {
            item(key = "failed") {
                Notice(
                    title = "${Fmt.plural(failedCount, "file")} didn't reach $computer",
                    detail = "Nothing was lost on this phone. Check there's space on $computer, then retry.",
                    actionLabel = "Retry ${Fmt.count(failedCount)}",
                    onAction = {
                        onBeforeTransfer()
                        onRetry()
                    },
                    modifier = Modifier.padding(top = Spacing.md),
                )
            }
        }

        if (records.isEmpty()) {
            item(key = "empty") {
                EmptyStrip(title = if (completedCount > 0) "History cleared" else "No transfers yet", body = "New transfers appear here, newest first.")
            }
            return@LazyColumn
        }

        item(key = "ledger-gap") { Spacer(Modifier.height(Spacing.lg)) }

        days.forEach { day ->
            stickyHeader(key = "day-${day.key}", contentType = "day") {
                GuideWords(text = day.label, trailing = Fmt.plural(day.records.size, "file"))
            }
            items(day.records, key = { "rec-${it.id}" }, contentType = { "record" }) { record ->
                LedgerEntry(record)
                Hairline()
            }
        }

        if (historyCount > records.size) {
            item(key = "limit") {
                PrintButton("Show older transfers (${Fmt.count(historyCount - records.size)} more)",
                    onClick = historyViewModel::loadMoreHistory, style = PrintButtonStyle.Outline,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.lg))
            }
        }

        item(key = "clear") {
            Column(Modifier.fillMaxWidth().padding(top = Spacing.xl)) {
                PrintButton(
                    text = "Clear history",
                    onClick = { askClear = true },
                    style = PrintButtonStyle.DangerOutline,
                    icon = Ph.Trash,
                    enabled = !isTransferring,
                )
                if (isTransferring) {
                    Text(
                        "You can clear the history once this transfer finishes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PherryTheme.colors.ink3,
                        modifier = Modifier.padding(top = Spacing.sm),
                    )
                }
            }
        }
    }
}

// ── Summary and backup check ─────────────────────────────────────────────────

@Composable
private fun SummaryBlock(
    completedCount: Int,
    totalBytes: Long,
    lastSentAt: Long,
    verify: VerifyState,
    connected: Boolean,
    computer: String,
    onCheck: () -> Unit,
    onAudit: () -> Unit,
) {
    val c = PherryTheme.colors
    val summary = buildList {
        // "Backed up", not "sent": the count includes files skipped because the computer already had them.
        add("${Fmt.count(completedCount)} backed up")
        add(Fmt.bytes(totalBytes))
        if (lastSentAt > 0) add("last ${Fmt.stamp(lastSentAt)}")
    }.joinToString(" · ")

    Column(
        Modifier
            .fillMaxWidth()
            .clip(PherryShape.print)
            .background(c.sheet)
            .border(1.dp, c.rule, PherryShape.print)
            .padding(Spacing.lg),
    ) {
        Text(summary.uppercase(), style = PherryTheme.text.monoCaps, color = c.ink2)
        Spacer(Modifier.height(Spacing.md))
        Hairline()
        Spacer(Modifier.height(Spacing.md))

        Text("Check the backup", style = MaterialTheme.typography.titleMedium, color = c.ink)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            "Ask $computer whether every file sent from this phone is still there. Missing files are queued again.",
            style = MaterialTheme.typography.bodySmall,
            color = c.ink2,
        )
        Spacer(Modifier.height(Spacing.md))
        PrintButton(
            text = if (verify.isVerifying) "Checking…" else "Check now",
            onClick = onCheck,
            style = PrintButtonStyle.Outline,
            icon = Ph.ShieldCheck,
            enabled = !verify.isVerifying && connected && completedCount > 0,
        )
        Spacer(Modifier.height(Spacing.sm))
        PrintButton(
            text = "Verify file contents",
            onClick = onAudit,
            style = PrintButtonStyle.Quiet,
            icon = Ph.SealCheck,
            enabled = !verify.isVerifying && connected && completedCount > 0,
        )
        Text("Reads the computer's files and checks their contents. Large backups take longer.",
            style = MaterialTheme.typography.bodySmall, color = c.ink2)

        Column(Modifier.fillMaxWidth()) {
            when {
                verify.isVerifying -> {
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        "Checking ${Fmt.count(verify.checked)} of ${Fmt.count(verify.total)}…",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.ink,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    JobBar(
                        progress = if (verify.total > 0) verify.checked.toFloat() / verify.total else 0f,
                        onEnvelope = false,
                        modifier = Modifier.height(4.dp),
                    )
                }
                !connected -> CheckLine(text = "Connect to $computer to check.", icon = Ph.Info, tint = c.ink3)
                verify.summary != null -> {
                    // Worded here from the counts so the result names the computer and reads like the rest of Pherry.
                    // Only files the computer answered for are called present or missing; the rest are named apart.
                    val answered = (verify.checked - verify.unknown - verify.unreadable).coerceAtLeast(0)
                    val confirmed = (answered - verify.missing).coerceAtLeast(0)
                    val gaps = buildList {
                        if (verify.unreadable > 0) add("Not checked: ${Fmt.plural(verify.unreadable, "file")} no longer on this phone.")
                        if (verify.unknown > 0) add("No answer for ${Fmt.plural(verify.unknown, "file")}. Check again to retry.")
                    }
                    when {
                        verify.unreachable -> CheckLine(
                            text = "The last check couldn't reach $computer. Try again.",
                            icon = Ph.WarningCircle,
                            tint = c.red,
                        )
                        verify.total == 0 -> CheckLine(
                            text = "Nothing sent yet to check.",
                            icon = Ph.Info,
                            tint = c.ink3,
                        )
                        verify.interrupted -> CheckLine(
                            text = listOfNotNull(
                                "Checked ${Fmt.count(answered)} of ${Fmt.count(verify.total)}. " +
                                    "Lost the connection to $computer. Check again when it's back.",
                                when {
                                    verify.missing == 1 -> "1 file that wasn't there is queued to send again."
                                    verify.missing > 1 -> "${Fmt.count(verify.missing)} files that weren't there are queued to send again."
                                    else -> null
                                },
                            ).joinToString(" "),
                            icon = Ph.WarningCircle,
                            tint = c.red,
                        )
                        verify.missing > 0 -> CheckLine(
                            text = (listOf(
                                "${Fmt.count(verify.missing)} of ${Fmt.plural(verify.total, "file")} weren't on $computer. " +
                                    "They're queued to send again.",
                            ) + gaps).joinToString(" "),
                            icon = Ph.Info,
                            tint = c.ink2,
                        )
                        gaps.isEmpty() -> CheckLine(
                            text = "${Fmt.plural(verify.total, "file")} checked. Every one is on $computer.",
                            icon = Ph.SealCheck,
                            tint = c.ink,
                        )
                        else -> CheckLine(
                            text = (listOf("${Fmt.count(confirmed)} of ${Fmt.count(verify.total)} confirmed on $computer.") + gaps)
                                .joinToString(" "),
                            icon = Ph.Info,
                            tint = c.ink2,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckLine(text: String, @DrawableRes icon: Int, tint: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = Spacing.md)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.Top,
    ) {
        PhIcon(icon, contentDescription = null, tint = tint, size = 18.dp)
        Spacer(Modifier.width(Spacing.sm))
        Text(text, style = MaterialTheme.typography.bodySmall, color = PherryTheme.colors.ink, modifier = Modifier.weight(1f))
    }
}

// ── Ledger entry ─────────────────────────────────────────────────────────────

@Composable
private fun LedgerEntry(record: UploadRecord) {
    val c = PherryTheme.colors
    val data = listOf(Fmt.bytes(record.fileSize), record.bucketName)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
        .uppercase()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(vertical = Spacing.sm)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Frame(
            model = record.contentUri.takeIf { canPreview(record.fileName, it) },
            contentDescription = null,
            isVideo = Fmt.isVideoName(record.fileName),
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                record.fileName,
                style = MaterialTheme.typography.titleSmall,
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            Text(data, style = PherryTheme.text.monoCaps, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(Spacing.md))
        Text(Fmt.clock(record.uploadedAt), style = PherryTheme.text.mono, color = c.ink2)
    }
}
