package com.appharbor.pherry.ui.connect

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appharbor.pherry.data.model.ConnectionState
import com.appharbor.pherry.ui.components.Lamp
import com.appharbor.pherry.ui.components.LampState
import com.appharbor.pherry.ui.components.Notice
import com.appharbor.pherry.ui.components.Ph
import com.appharbor.pherry.ui.components.PhIcon
import com.appharbor.pherry.ui.components.PrintButton
import com.appharbor.pherry.ui.components.PrintButtonStyle
import com.appharbor.pherry.ui.theme.PherryShape
import com.appharbor.pherry.ui.theme.PherryTheme
import com.appharbor.pherry.ui.theme.Spacing

/**
 * "Your computer": the bottom sheet behind the top bar's computer chip. Shows the paired computer,
 * or every way to pair one. While connected, the ticket can be scanned again and another computer
 * paired without disconnecting first; [startExpanded] opens that "Pair a different computer" part
 * straight away (Settings' "Change computer").
 */
@Composable
fun ConnectSheet(
    onDismiss: () -> Unit,
    startExpanded: Boolean = false,
    viewModel: ConnectViewModel = hiltViewModel(),
) {
    val c = PherryTheme.colors
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val serverName by viewModel.serverName.collectAsStateWithLifecycle()
    val endpoint by viewModel.connectedEndpoint.collectAsStateWithLifecycle()
    val problem by viewModel.pairingProblem.collectAsStateWithLifecycle()
    val autoBackupEnabled by viewModel.autoBackupEnabled.collectAsStateWithLifecycle()
    val scanTicket = rememberTicketScanner(viewModel)
    var pairOther by rememberSaveable { mutableStateOf(startExpanded) }
    var askDisconnect by rememberSaveable { mutableStateOf(false) }

    // The link can drop on its own while the dialog is open; there is nothing left to disconnect then.
    LaunchedEffect(connectionState) {
        if (connectionState == ConnectionState.DISCONNECTED) askDisconnect = false
    }

    if (askDisconnect) {
        val computer = serverName.ifBlank { "your computer" }
        AlertDialog(
            onDismissRequest = { askDisconnect = false },
            containerColor = c.sheet,
            title = { Text("Disconnect from $computer?") },
            text = {
                // Disconnect only drops the live link: the pairing stays, so Pherry reconnects by itself.
                Text(
                    if (autoBackupEnabled) {
                        "Pherry reconnects to $computer the next time it opens or auto-backup runs. " +
                            "To stop backups, turn off auto-backup."
                    } else {
                        "Pherry reconnects to $computer the next time it opens."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // After disconnecting, the sheet stays open on the pairing options.
                        viewModel.onDisconnect()
                        askDisconnect = false
                    },
                    // Nothing is removed, so the confirm prints in ink; red is kept for deletions.
                    colors = ButtonDefaults.textButtonColors(contentColor = c.ink),
                ) { Text("Disconnect") }
            },
            dismissButton = {
                TextButton(
                    onClick = { askDisconnect = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = c.ink),
                ) { Text("Cancel") }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(start = Spacing.screen, end = Spacing.screen, bottom = Spacing.xl),
    ) {
        Text(
            text = "Your computer",
            style = MaterialTheme.typography.headlineSmall,
            color = c.ink,
            modifier = Modifier.semantics { heading() },
        )

        if (connectionState == ConnectionState.CONNECTED) {
            Spacer(Modifier.height(Spacing.lg))
            ConnectedBlock(
                name = serverName.ifBlank { "Your computer" },
                endpoint = endpoint,
                onDisconnect = { askDisconnect = true },
                onDone = onDismiss,
            )
            Spacer(Modifier.height(Spacing.lg))
            // Picks up a rotated pairing code without dropping the link (or an upload in flight).
            PrintButton(
                text = "Scan the ticket again",
                onClick = scanTicket,
                style = PrintButtonStyle.Outline,
                icon = Ph.Scan,
                modifier = Modifier.fillMaxWidth(),
            )
            // With the pairing options open, they show the problem themselves.
            val shownProblem = problem
            if (shownProblem != null && !pairOther) {
                Spacer(Modifier.height(Spacing.sm))
                Notice(
                    title = shownProblem,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            PairOtherToggle(expanded = pairOther, onToggle = { pairOther = !pairOther })
            // Switching computers connects to the new one from here; no disconnect first.
            // PairingOptions looks for computers on the Wi-Fi only while it is shown.
            AnimatedVisibility(visible = pairOther) {
                PairingOptions(viewModel = viewModel, modifier = Modifier.padding(top = Spacing.md))
            }
        } else {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = "Open Pherry Desktop on your computer and scan the pairing ticket it shows.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.ink2,
            )
            Spacer(Modifier.height(Spacing.lg))
            if (connectionState == ConnectionState.CONNECTING) {
                ConnectingLine(target = serverName.ifBlank { endpoint.ifBlank { "your computer" } })
                Spacer(Modifier.height(Spacing.lg))
            }
            PairingOptions(viewModel = viewModel)
            Spacer(Modifier.height(Spacing.xl))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PhIcon(Ph.Wifi, contentDescription = null, tint = c.ink3, size = 18.dp)
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = "Phone and computer need to be on the same Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.ink2,
                )
            }
        }
    }
}

/** Quiet disclosure row: "Pair a different computer" with a caret that turns when open. */
@Composable
private fun PairOtherToggle(expanded: Boolean, onToggle: () -> Unit) {
    val c = PherryTheme.colors
    val caretTurn by animateFloatAsState(if (expanded) 180f else 0f, tween(200), label = "pair-other-caret")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Spacing.touch)
            .clip(PherryShape.button)
            .clickable(role = Role.Button) { onToggle() }
            .semantics { stateDescription = if (expanded) "Open" else "Closed" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhIcon(Ph.Desktop, contentDescription = null, tint = c.ink2, size = 20.dp)
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = "Pair a different computer",
            style = MaterialTheme.typography.labelLarge,
            color = c.ink,
            modifier = Modifier.weight(1f),
        )
        PhIcon(Ph.CaretDown, contentDescription = null, tint = c.ink2, size = 18.dp, modifier = Modifier.rotate(caretTurn))
    }
}

/** The paired computer as a raised print: green lamp, its name, its address, and what to do next. */
@Composable
private fun ConnectedBlock(
    name: String,
    endpoint: String,
    onDisconnect: () -> Unit,
    onDone: () -> Unit,
) {
    val c = PherryTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PherryShape.print)
            .background(c.sheet)
            .border(1.dp, c.rule, PherryShape.print)
            .padding(Spacing.lg),
    ) {
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                stateDescription = "Connected"
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Lamp(LampState.On)
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (endpoint.isNotBlank()) {
                    Text(
                        text = endpoint,
                        style = PherryTheme.text.mono,
                        color = c.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(Spacing.md))
            PhIcon(Ph.Desktop, contentDescription = null, tint = c.ink3)
        }
        Spacer(Modifier.height(Spacing.lg))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Disconnect only drops the link and keeps the pairing: quiet ink, not safelight red.
            PrintButton("Disconnect", onClick = onDisconnect, style = PrintButtonStyle.Quiet)
            Spacer(Modifier.weight(1f))
            PrintButton("Done", onClick = onDone)
        }
    }
}
