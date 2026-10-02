package com.appharbor.pherry.ui.connect

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appharbor.pherry.data.model.ConnectionState
import com.appharbor.pherry.data.network.DiscoveredDesktop
import com.appharbor.pherry.ui.components.Hairline
import com.appharbor.pherry.ui.components.Lamp
import com.appharbor.pherry.ui.components.LampState
import com.appharbor.pherry.ui.components.Notice
import com.appharbor.pherry.ui.components.Ph
import com.appharbor.pherry.ui.components.PhIcon
import com.appharbor.pherry.ui.components.PrintButton
import com.appharbor.pherry.ui.components.PrintButtonStyle
import com.appharbor.pherry.ui.components.SectionHeading
import com.appharbor.pherry.ui.permissions.LocalNetworkAccess
import com.appharbor.pherry.ui.permissions.rememberLocalNetworkAccess
import com.appharbor.pherry.ui.theme.PherryShape
import com.appharbor.pherry.ui.theme.PherryTheme
import com.appharbor.pherry.ui.theme.Spacing
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

// Pairing pieces shared by onboarding step 2 and the connect sheet.

/**
 * Opens Google's code scanner (no camera permission needed) for the QR on Pherry Desktop's pairing
 * ticket and hands the raw payload, token included, to [viewModel].
 */
@Composable
internal fun rememberTicketScanner(viewModel: ConnectViewModel): () -> Unit {
    val context = LocalContext.current
    return remember<() -> Unit>(context, viewModel) {
        {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            GmsBarcodeScanning.getClient(context, options).startScan()
                .addOnSuccessListener { barcode -> viewModel.onScannedPayload(barcode.rawValue) }
                .addOnCanceledListener { /* Dismissed by the user: nothing to report. */ }
                .addOnFailureListener { e -> viewModel.onQrScanError(e.localizedMessage) }
        }
    }
}

/**
 * Every way to pair a computer, in order of ease: scan the ticket, pick one found on the Wi-Fi,
 * reuse a recent address, or type one. Looks for computers on the network while it is shown.
 */
@Composable
internal fun PairingOptions(
    viewModel: ConnectViewModel,
    modifier: Modifier = Modifier,
) {
    val c = PherryTheme.colors
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val ipAddress by viewModel.ipAddress.collectAsStateWithLifecycle()
    val pairingCode by viewModel.pairingCode.collectAsStateWithLifecycle()
    val problem by viewModel.pairingProblem.collectAsStateWithLifecycle()
    val recentTargets by viewModel.recentDesktopTargets.collectAsStateWithLifecycle()
    val nearbyDesktops by viewModel.nearbyDesktops.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val scanTicket = rememberTicketScanner(viewModel)
    var typing by rememberSaveable { mutableStateOf(false) }
    val network = rememberLocalNetworkAccess()

    // Android 17+: without local network access nothing on the Wi-Fi can be reached, so ask first.
    if (!network.granted) {
        LocalNetworkGate(access = network, modifier = modifier)
        return
    }

    // Discover "_pherry._tcp" desktops only while these options are on screen.
    DisposableEffect(viewModel) {
        viewModel.startDiscovery()
        onDispose { viewModel.stopDiscovery() }
    }

    Column(modifier.fillMaxWidth()) {
        PrintButton(
            text = "Scan the ticket",
            onClick = {
                focusManager.clearFocus()
                scanTicket()
            },
            icon = Ph.Scan,
            modifier = Modifier.fillMaxWidth(),
        )
        // With the address field open, the problem shows under the field instead.
        val shownProblem = problem
        if (shownProblem != null && !typing) {
            Spacer(Modifier.height(Spacing.sm))
            Notice(
                title = shownProblem,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        Spacer(Modifier.height(Spacing.xl))
        NearbyList(
            desktops = nearbyDesktops,
            onPair = { desktop ->
                focusManager.clearFocus()
                viewModel.onIpChanged(desktop.endpoint)
                typing = true
            },
        )

        if (recentTargets.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.lg))
            RecentTargets(
                targets = recentTargets,
                onSelect = { target ->
                    focusManager.clearFocus()
                    viewModel.onRecentTargetSelected(target)
                },
            )
        }

        Spacer(Modifier.height(Spacing.md))
        ManualAddress(
            value = ipAddress,
            pairingCode = pairingCode,
            onPairingCodeChange = viewModel::onPairingCodeChanged,
            onValueChange = viewModel::onIpChanged,
            error = problem,
            connecting = connectionState == ConnectionState.CONNECTING,
            expanded = typing,
            onExpandedChange = { typing = it },
            onConnect = {
                focusManager.clearFocus()
                viewModel.onConnect()
            },
        )

        Spacer(Modifier.height(Spacing.md))
        Text(
            text = "Scan the ticket or enter its pairing code to authorize this phone. The computer keeps each phone in its own folder.",
            style = MaterialTheme.typography.bodySmall,
            color = c.ink3,
        )
    }
}

/**
 * Explains Android 17's "Nearby devices" prompt before showing it, or how to grant it in system
 * settings once Android has stopped asking.
 */
@Composable
internal fun LocalNetworkGate(access: LocalNetworkAccess, modifier: Modifier = Modifier) {
    val c = PherryTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(PherryShape.print)
            .background(c.sheet)
            .border(1.dp, c.rule, PherryShape.print)
            .padding(Spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhIcon(Ph.Wifi, contentDescription = null, tint = c.ink)
            Spacer(Modifier.width(Spacing.md))
            Text(
                text = if (access.blocked) "Wi-Fi access is off for Pherry" else "Let Pherry find your computer",
                style = MaterialTheme.typography.titleMedium,
                color = c.ink,
            )
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = if (access.blocked) {
                "Android won't ask again. Open Pherry's settings, choose Permissions, then Nearby devices, and allow it."
            } else {
                "Android asks before an app talks to other devices on your Wi-Fi. Pherry only talks to Pherry Desktop."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = c.ink2,
        )
        Spacer(Modifier.height(Spacing.lg))
        PrintButton(
            text = if (access.blocked) "Open app settings" else "Allow",
            onClick = { if (access.blocked) access.openSettings() else access.request() },
            icon = if (access.blocked) Ph.Gear else Ph.Wifi,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Desktops answering on this Wi-Fi, or a quiet line while none have answered yet. */
@Composable
internal fun NearbyList(
    desktops: List<DiscoveredDesktop>,
    onPair: (DiscoveredDesktop) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = PherryTheme.colors
    Column(modifier.fillMaxWidth()) {
        SectionHeading("Found on your Wi-Fi")
        if (desktops.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Lamp(LampState.Busy)
                Spacer(Modifier.width(Spacing.md))
                Text(
                    text = "Looking for computers on this Wi-Fi…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.ink2,
                )
            }
        } else {
            desktops.forEachIndexed { index, desktop ->
                key(desktop.endpoint) {
                    if (index > 0) Hairline()
                    NearbyRow(desktop = desktop, onClick = { onPair(desktop) })
                }
            }
        }
    }
}

@Composable
private fun NearbyRow(desktop: DiscoveredDesktop, onClick: () -> Unit) {
    val c = PherryTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClickLabel = "Pair", onClick = onClick)
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhIcon(Ph.Desktop, contentDescription = null, tint = c.ink2)
        Spacer(Modifier.width(Spacing.lg))
        Column(Modifier.weight(1f)) {
            Text(
                text = desktop.name,
                style = MaterialTheme.typography.titleSmall,
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(desktop.endpoint, style = PherryTheme.text.mono, color = c.ink3, maxLines = 1)
        }
        Spacer(Modifier.width(Spacing.md))
        // Drawn like a small outline button; the whole row is the touch target.
        Box(
            modifier = Modifier
                .border(1.5.dp, c.ink, PherryShape.button)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text("Pair", style = MaterialTheme.typography.labelLarge, color = c.ink)
        }
    }
}

/** Addresses this phone connected to before, newest first. */
@Composable
private fun RecentTargets(targets: List<String>, onSelect: (String) -> Unit) {
    val c = PherryTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Text("Used before".uppercase(), style = PherryTheme.text.formLabel, color = c.ink2)
        Spacer(Modifier.height(Spacing.xs))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            targets.forEach { target ->
                key(target) {
                    AssistChip(
                        onClick = { onSelect(target) },
                        label = { Text(target, style = PherryTheme.text.mono, maxLines = 1) },
                        leadingIcon = { PhIcon(Ph.History, contentDescription = null, size = 16.dp) },
                        shape = PherryShape.button,
                        border = BorderStroke(1.dp, c.ruleStrong),
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = c.sheet,
                            labelColor = c.ink,
                            leadingIconContentColor = c.ink2,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * "Type the address instead": a disclosure row that opens the address field and its Connect
 * button. Errors print in red under the field.
 */
@Composable
internal fun ManualAddress(
    value: String,
    pairingCode: String,
    onPairingCodeChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    error: String?,
    connecting: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = PherryTheme.colors
    val caretTurn by animateFloatAsState(if (expanded) 180f else 0f, tween(200), label = "address-caret")
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.touch)
                .clip(PherryShape.button)
                .clickable(role = Role.Button) { onExpandedChange(!expanded) }
                .semantics { stateDescription = if (expanded) "Open" else "Closed" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhIcon(Ph.Link, contentDescription = null, tint = c.ink2, size = 20.dp)
            Spacer(Modifier.width(Spacing.md))
            Text(
                text = "Type the address instead",
                style = MaterialTheme.typography.labelLarge,
                color = c.ink,
                modifier = Modifier.weight(1f),
            )
            PhIcon(Ph.CaretDown, contentDescription = null, tint = c.ink2, size = 18.dp, modifier = Modifier.rotate(caretTurn))
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.fillMaxWidth().padding(top = Spacing.sm)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = PherryTheme.text.mono,
                    label = { Text("Computer address") },
                    placeholder = { Text("192.168.1.42:3210", style = PherryTheme.text.mono) },
                    leadingIcon = { PhIcon(Ph.Desktop, contentDescription = null, size = 20.dp) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = {
                        Text(
                            text = error ?: "Pherry Desktop shows it on the pairing ticket, under the QR code.",
                            modifier = if (error != null) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                        autoCorrectEnabled = false,
                    ),
                    keyboardActions = KeyboardActions(onDone = { onConnect() }),
                    shape = PherryShape.print,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = c.ink,
                        unfocusedTextColor = c.ink,
                        errorTextColor = c.ink,
                        focusedContainerColor = c.sheet,
                        unfocusedContainerColor = c.sheet,
                        errorContainerColor = c.sheet,
                        cursorColor = c.ink,
                        errorCursorColor = c.red,
                        focusedBorderColor = c.ink,
                        unfocusedBorderColor = c.ink3,
                        errorBorderColor = c.red,
                        focusedLeadingIconColor = c.ink2,
                        unfocusedLeadingIconColor = c.ink2,
                        errorLeadingIconColor = c.red,
                        focusedLabelColor = c.ink,
                        unfocusedLabelColor = c.ink2,
                        errorLabelColor = c.red,
                        focusedPlaceholderColor = c.ink3,
                        unfocusedPlaceholderColor = c.ink3,
                        focusedSupportingTextColor = c.ink3,
                        unfocusedSupportingTextColor = c.ink3,
                        errorSupportingTextColor = c.red,
                    ),
                )
                Spacer(Modifier.height(Spacing.sm))
                OutlinedTextField(
                    value = pairingCode,
                    onValueChange = onPairingCodeChange,
                    label = { Text("Pairing code") },
                    supportingText = { Text("Shown on the computer's ticket. Leave blank for a phone already authorized here.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onConnect() }),
                    shape = PherryShape.print,
                )
                Spacer(Modifier.height(Spacing.sm))
                PrintButton(
                    text = if (connecting) "Connecting…" else "Connect",
                    onClick = onConnect,
                    enabled = !connecting,
                    style = PrintButtonStyle.Outline,
                    icon = Ph.Plugs,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** A blinking lamp and the computer being reached, shown while a connection is being made. */
@Composable
internal fun ConnectingLine(target: String, modifier: Modifier = Modifier) {
    val c = PherryTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(PherryShape.print)
            .background(c.well)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Lamp(LampState.Busy)
        Spacer(Modifier.width(Spacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                text = "Connecting to $target…",
                style = MaterialTheme.typography.titleSmall,
                color = c.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Make sure Pherry Desktop is open and both are on the same Wi-Fi.",
                style = MaterialTheme.typography.bodySmall,
                color = c.ink2,
            )
        }
    }
}
