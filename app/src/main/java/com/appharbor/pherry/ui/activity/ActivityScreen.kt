package com.appharbor.pherry.ui.activity

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appharbor.pherry.data.model.ConnectionState
import com.appharbor.pherry.ui.components.Fmt
import com.appharbor.pherry.ui.components.Hairline
import com.appharbor.pherry.ui.components.Lamp
import com.appharbor.pherry.ui.components.LampState
import com.appharbor.pherry.ui.components.ScreenHeader
import com.appharbor.pherry.ui.history.HistoryViewModel
import com.appharbor.pherry.ui.settings.SettingsViewModel
import com.appharbor.pherry.ui.theme.PherryTheme
import com.appharbor.pherry.ui.theme.Spacing
import com.appharbor.pherry.ui.transfer.TransferViewModel

private enum class TransfersTab(val label: String) { Now("Now"), History("History") }

@Composable
fun ActivityScreen(
    onOpenLibrary: () -> Unit,
    onConnectClick: () -> Unit,
    onBeforeTransfer: () -> Unit = {},
) {
    val transferViewModel: TransferViewModel = hiltViewModel()
    val historyViewModel: HistoryViewModel = hiltViewModel()
    val settingsViewModel: SettingsViewModel = hiltViewModel()

    val transfer by transferViewModel.transferState.collectAsStateWithLifecycle()
    val serverName by transferViewModel.serverName.collectAsStateWithLifecycle()
    val queuedCount by transferViewModel.queuedCount.collectAsStateWithLifecycle()
    val connectionState by historyViewModel.connectionState.collectAsStateWithLifecycle()
    val completedCount by historyViewModel.completedCount.collectAsStateWithLifecycle()
    val failedCount by historyViewModel.failedCount.collectAsStateWithLifecycle()
    val totalBytes by historyViewModel.totalTransferredBytes.collectAsStateWithLifecycle()
    val lastSentAt by historyViewModel.lastSyncTimestamp.collectAsStateWithLifecycle()
    val records by historyViewModel.recentHistory.collectAsStateWithLifecycle()
    val verifyState by historyViewModel.verifyState.collectAsStateWithLifecycle()
    val keepScreenAwake by settingsViewModel.keepScreenAwake.collectAsStateWithLifecycle()

    // Keep the display on while a transfer runs, if the user asked for it in Settings.
    val view = LocalView.current
    DisposableEffect(view, keepScreenAwake, transfer.isTransferring) {
        view.keepScreenOn = keepScreenAwake && transfer.isTransferring
        onDispose { view.keepScreenOn = false }
    }

    var tab by rememberSaveable { mutableStateOf(TransfersTab.Now) }
    // The tab is restored on every visit; a running job always brings Now forward, so "Back up", "Send",
    // "Resume" and "See transfer" land on the job they started even if History was open last time.
    LaunchedEffect(transfer.isTransferring) {
        if (transfer.isTransferring) tab = TransfersTab.Now
    }
    val nowListState = rememberLazyListState()
    val historyListState = rememberLazyListState()
    val computer = serverName.ifBlank { "your computer" }
    val connected = connectionState == ConnectionState.CONNECTED

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Transfers",
            data = headerData(toSend = queuedCount, done = completedCount, failed = failedCount),
            modifier = Modifier.padding(horizontal = Spacing.screen),
        )
        TransfersTabs(selected = tab, sending = transfer.isTransferring, onSelect = { tab = it })

        when (tab) {
            TransfersTab.Now -> TransfersNow(
                transfer = transfer,
                queuedCount = queuedCount,
                failedCount = failedCount,
                computer = computer,
                connected = connected,
                listState = nowListState,
                onStop = transferViewModel::cancelTransfer,
                // Every action that starts sending asks for the notification permission first.
                onResume = {
                    onBeforeTransfer()
                    transferViewModel.resumeQueued()
                },
                onRetry = {
                    onBeforeTransfer()
                    historyViewModel.retryFailed()
                },
                onConnect = onConnectClick,
                onOpenLibrary = onOpenLibrary,
            )

            TransfersTab.History -> TransfersHistory(
                records = records,
                completedCount = completedCount,
                failedCount = failedCount,
                totalBytes = totalBytes,
                lastSentAt = lastSentAt,
                verify = verifyState,
                connected = connected,
                isTransferring = transfer.isTransferring,
                computer = computer,
                listState = historyListState,
                // A check re-queues missing files and starts sending them, so it asks first too.
                onCheck = {
                    onBeforeTransfer()
                    historyViewModel.verifyBackup()
                },
                onRetry = {
                    onBeforeTransfer()
                    historyViewModel.retryFailed()
                    tab = TransfersTab.Now
                },
                onClear = historyViewModel::clearHistory,
            )
        }
    }
}

/**
 * The data line under the title: the whole ledger in one breath. [done] counts every file on the computer,
 * including ones skipped because it already had them, so it reads "backed up", not "sent". [toSend] counts
 * the queued files and the ones on the line together, so it reads "to send", not "waiting" (the ledger
 * below splits the two).
 */
private fun headerData(toSend: Int, done: Int, failed: Int): String {
    if (toSend == 0 && done == 0 && failed == 0) return "Nothing sent yet"
    return buildList {
        if (toSend > 0) add("${Fmt.count(toSend)} to send")
        add("${Fmt.count(done)} backed up")
        if (failed > 0) add("${Fmt.count(failed)} failed")
    }.joinToString(" · ")
}

/** Printed tabs: ink text, a 2dp ink rule under the chosen one, a busy lamp beside "Now" while sending. */
@Composable
private fun TransfersTabs(selected: TransfersTab, sending: Boolean, onSelect: (TransfersTab) -> Unit) {
    val c = PherryTheme.colors
    SecondaryTabRow(
        selectedTabIndex = selected.ordinal,
        containerColor = c.paper,
        contentColor = c.ink,
        indicator = {
            TabRowDefaults.SecondaryIndicator(
                modifier = Modifier.tabIndicatorOffset(selected.ordinal, matchContentSize = false),
                height = 2.dp,
                color = c.ink,
            )
        },
        divider = { Hairline() },
    ) {
        TransfersTab.entries.forEach { item ->
            val busy = item == TransfersTab.Now && sending
            Tab(
                selected = item == selected,
                onClick = { onSelect(item) },
                selectedContentColor = c.ink,
                unselectedContentColor = c.ink2,
                modifier = if (busy) Modifier.semantics { stateDescription = "Sending" } else Modifier,
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.label, style = MaterialTheme.typography.labelLarge)
                        if (busy) {
                            Spacer(Modifier.width(Spacing.sm))
                            Lamp(LampState.Busy)
                        }
                    }
                },
            )
        }
    }
}

// ── Shared helpers for both tabs ─────────────────────────────────────────────

private val PreviewableExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

/** Coil can draw a still for this file (photos only; video frames need a decoder Pherry doesn't ship). */
internal fun canPreview(fileName: String, contentUri: String): Boolean =
    contentUri.isNotBlank() && fileName.substringAfterLast('.', "").lowercase() in PreviewableExtensions
