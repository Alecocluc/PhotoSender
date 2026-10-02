package com.appharbor.pherry.ui.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appharbor.pherry.data.model.ConnectionState
import com.appharbor.pherry.data.network.RememberedComputer
import com.appharbor.pherry.data.preferences.ThemeMode
import com.appharbor.pherry.ui.components.Hairline
import com.appharbor.pherry.ui.components.Lamp
import com.appharbor.pherry.ui.components.LampState
import com.appharbor.pherry.ui.components.PherryMark
import com.appharbor.pherry.ui.components.Ph
import com.appharbor.pherry.ui.components.PrintButton
import com.appharbor.pherry.ui.components.PrintButtonStyle
import com.appharbor.pherry.ui.components.PrintSegmented
import com.appharbor.pherry.ui.components.ScreenHeader
import com.appharbor.pherry.ui.components.SectionHeading
import com.appharbor.pherry.ui.components.SwitchRow
import com.appharbor.pherry.ui.gallery.UploadMode
import com.appharbor.pherry.ui.theme.PherryShape
import com.appharbor.pherry.ui.theme.PherryTheme
import com.appharbor.pherry.ui.theme.Spacing
import com.appharbor.pherry.ui.permissions.hasFullMediaPermission

@Composable
fun SettingsScreen(
    onManageComputer: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val serverName by viewModel.serverName.collectAsStateWithLifecycle()
    val connectedEndpoint by viewModel.connectedEndpoint.collectAsStateWithLifecycle()
    val rememberedComputer by viewModel.rememberedComputer.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val dynamicColorEnabled by viewModel.dynamicColorEnabled.collectAsStateWithLifecycle()
    val highSpeedTransferEnabled by viewModel.highSpeedTransferEnabled.collectAsStateWithLifecycle()
    val autoBackupEnabled by viewModel.autoBackupEnabled.collectAsStateWithLifecycle()
    val autoBackupRequiresCharging by viewModel.autoBackupRequiresCharging.collectAsStateWithLifecycle()
    val confirmDestructiveSync by viewModel.confirmDestructiveSync.collectAsStateWithLifecycle()
    val wifiOnlyTransfer by viewModel.wifiOnlyTransfer.collectAsStateWithLifecycle()
    val keepScreenAwake by viewModel.keepScreenAwake.collectAsStateWithLifecycle()
    val defaultUploadModeName by viewModel.defaultUploadMode.collectAsStateWithLifecycle()
    val deviceName by viewModel.deviceName.collectAsStateWithLifecycle()
    val uploadMode = UploadMode.entries.firstOrNull { it.name == defaultUploadModeName } ?: UploadMode.ADD

    val c = PherryTheme.colors
    val context = LocalContext.current
    val versionName = remember(context) { context.appVersionName() }
    val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val computer = serverName.ifBlank { "your computer" }

    var askAutoBackup by rememberSaveable { mutableStateOf(false) }
    var askDisconnect by rememberSaveable { mutableStateOf(false) }
    var askMirror by rememberSaveable { mutableStateOf(false) }
    var editingName by rememberSaveable { mutableStateOf(false) }
    var nameDraft by rememberSaveable { mutableStateOf("") }

    if (editingName) {
        AlertDialog(
            onDismissRequest = { editingName = false },
            title = { Text("Name this phone") },
            text = {
                OutlinedTextField(value = nameDraft, onValueChange = { nameDraft = it.take(64) },
                    label = { Text("Phone name") }, singleLine = true)
            },
            confirmButton = { TextButton(enabled = nameDraft.isNotBlank(), onClick = {
                viewModel.saveDeviceName(nameDraft); editingName = false
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editingName = false }) { Text("Cancel") } },
        )
    }
    if (askMirror) {
        AlertDialog(
            onDismissRequest = { askMirror = false },
            title = { Text("Enable mirror deletions?") },
            text = { Text("After you delete a photo on this phone, a manual mirror can also remove its backup from this phone's folder on the computer. Every deletion is previewed and requires your confirmation. Auto-backup only adds files. Keep this off if the computer should preserve everything.") },
            confirmButton = { TextButton(onClick = {
                viewModel.onDefaultUploadModeSelected(UploadMode.SYNC); askMirror = false
            }) { Text("Enable mirror deletions", color = c.red) } },
            dismissButton = { TextButton(onClick = { askMirror = false }) { Text("Keep backups") } },
        )
    }

    // The link can drop on its own while the dialog is open; there is nothing left to disconnect then.
    LaunchedEffect(connectionState) {
        if (connectionState == ConnectionState.DISCONNECTED) askDisconnect = false
    }

    if (askAutoBackup) {
        AlertDialog(
            onDismissRequest = { askAutoBackup = false },
            containerColor = c.sheet,
            title = { Text("Turn on auto-backup") },
            text = {
                Text(
                    "Pherry will send new photos and videos to $computer by itself, about every 15 minutes " +
                        "when the phone is on Wi-Fi. Start with everything already on this phone, or only what you take from now on?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.enableAutoBackup(includeExisting = true)
                        askAutoBackup = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = c.ink),
                ) { Text("Everything") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.enableAutoBackup(includeExisting = false)
                        askAutoBackup = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = c.ink),
                ) { Text("Only new") }
            },
        )
    }

    if (askDisconnect) {
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
                        viewModel.disconnect()
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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.screen, end = Spacing.screen, bottom = Spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl),
    ) {
        item(key = "header") {
            ScreenHeader(title = "Settings")
        }

        item(key = "computer") {
            SettingsSection("Computer") {
                com.appharbor.pherry.ui.components.ActionRow(
                    title = "This phone", subtitle = deviceName, icon = Ph.Phone,
                    onClick = { nameDraft = deviceName; editingName = true },
                )
                ComputerBlock(
                    state = connectionState,
                    serverName = serverName,
                    endpoint = connectedEndpoint,
                    remembered = rememberedComputer,
                    onManage = onManageComputer,
                    onRetry = viewModel::reconnect,
                    onDisconnect = { askDisconnect = true },
                    modifier = Modifier.padding(top = Spacing.md),
                )
            }
        }

        item(key = "backup") {
            SettingsSection("Backup") {
                SwitchRow(
                    title = "Auto-backup",
                    subtitle = "Sends new photos and videos to $computer every 15 minutes or so, while on Wi-Fi and $computer is on.",
                    icon = Ph.Clock,
                    checked = autoBackupEnabled,
                    onCheckedChange = { on -> if (on) askAutoBackup = true else viewModel.disableAutoBackup() },
                )
                Hairline()
                SwitchRow(
                    title = "Only while charging",
                    subtitle = if (autoBackupEnabled) {
                        "Auto-backup waits until the phone is plugged in."
                    } else {
                        "Turn on auto-backup to use this."
                    },
                    icon = Ph.BatteryCharging,
                    checked = autoBackupRequiresCharging,
                    onCheckedChange = viewModel::onAutoBackupChargingChanged,
                    enabled = autoBackupEnabled,
                )
                Hairline()
                SwitchRow(
                    title = "Unmetered networks only",
                    subtitle = "Transfers wait on metered networks. Turn off to send over a metered hotspot.",
                    icon = Ph.Wifi,
                    checked = wifiOnlyTransfer,
                    onCheckedChange = viewModel::onWifiOnlyTransferChanged,
                )

            }
        }

        item(key = "advanced") {
            SettingsSection("Advanced") {
                SwitchRow(
                    title = "Mirror deletions",
                    subtitle = if (hasFullMediaPermission(context))
                        "Manual backups can remove this phone's deleted photos from its computer folder. Always asks before deleting."
                    else "Requires full photo and video access. Limited access never permits deletions.",
                    icon = Ph.Trash,
                    checked = uploadMode == UploadMode.SYNC,
                    enabled = hasFullMediaPermission(context) || uploadMode == UploadMode.SYNC,
                    onCheckedChange = { on ->
                        if (on) askMirror = true else viewModel.onDefaultUploadModeSelected(UploadMode.ADD)
                    },
                )
                if (uploadMode == UploadMode.SYNC) {
                    Hairline()
                    SwitchRow(
                        title = "Review upload-only mirror plans",
                        subtitle = "Preview even when no files will be deleted. Deletions always need confirmation.",
                        icon = Ph.Eye,
                        checked = confirmDestructiveSync,
                        onCheckedChange = viewModel::onConfirmDestructiveSyncChanged,
                    )
                }
            }
        }

        item(key = "transfers") {
            SettingsSection("Transfers") {
                SwitchRow(
                    title = "Faster transfers",
                    subtitle = "Allows more files in parallel when the network and computer can keep up.",
                    icon = Ph.Lightning,
                    checked = highSpeedTransferEnabled,
                    onCheckedChange = viewModel::onHighSpeedTransferChanged,
                )
                Hairline()
                SwitchRow(
                    title = "Keep screen on while sending",
                    subtitle = "Only while the Transfers tab is open.",
                    icon = Ph.Phone,
                    checked = keepScreenAwake,
                    onCheckedChange = viewModel::onKeepScreenAwakeChanged,
                )
            }
        }

        item(key = "appearance") {
            SettingsSection("Appearance") {
                Spacer(Modifier.height(Spacing.md))
                Text("Theme", style = MaterialTheme.typography.titleSmall, color = c.ink)
                Spacer(Modifier.height(Spacing.sm))
                PrintSegmented(
                    options = listOf(
                        ThemeMode.SYSTEM to "System",
                        ThemeMode.LIGHT to "Light",
                        ThemeMode.DARK to "Dark",
                    ),
                    selected = themeMode,
                    onSelect = viewModel::onThemeModeSelected,
                )
                Spacer(Modifier.height(Spacing.md))
                Hairline()
                SwitchRow(
                    title = "Use wallpaper colours",
                    subtitle = "Replaces Pherry yellow with colours from your wallpaper. Android 12 and newer.",
                    icon = Ph.Sparkle,
                    checked = dynamicColorEnabled && dynamicColorSupported,
                    onCheckedChange = viewModel::onDynamicColorChanged,
                    enabled = dynamicColorSupported,
                )
            }
        }

        item(key = "about") {
            SettingsSection("About") {
                AboutBlock(versionName = versionName, modifier = Modifier.padding(top = Spacing.lg))
            }
        }
    }
}

/** A titled group of rows: printed caps heading over a hairline, rows straight on the paper. */
@Composable
private fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        SectionHeading(title)
        content()
    }
}

/** The paired computer on a raised print: lamp, name, address, and what you can do about it. */
@Composable
private fun ComputerBlock(
    state: ConnectionState,
    serverName: String,
    endpoint: String,
    remembered: RememberedComputer?,
    onManage: () -> Unit,
    onRetry: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = PherryTheme.colors
    // A saved computer that isn't linked right now is still paired: name it and say it's offline.
    val offline = state == ConnectionState.DISCONNECTED && remembered != null
    val savedName = remembered?.name?.takeIf { it.isNotBlank() }
    val title = when (state) {
        ConnectionState.CONNECTED -> serverName.ifBlank { "Your computer" }
        ConnectionState.CONNECTING -> serverName.ifBlank { "Connecting…" }
        ConnectionState.DISCONNECTED -> if (offline) savedName ?: "Your computer" else "No computer"
    }
    val address = when (state) {
        ConnectionState.DISCONNECTED -> remembered?.address ?: "Not paired"
        else -> endpoint.ifBlank { "Not paired" }
    }
    val status = when (state) {
        ConnectionState.CONNECTED -> "Connected"
        ConnectionState.CONNECTING -> "Connecting"
        ConnectionState.DISCONNECTED -> when {
            remembered?.disconnectedByUser == true -> "Disconnected"
            offline -> "Not answering"
            else -> "Not connected"
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .clip(PherryShape.print)
            .background(c.sheet)
            .border(1.dp, c.rule, PherryShape.print)
            .padding(Spacing.lg),
    ) {
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) { stateDescription = status },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Lamp(
                when (state) {
                    ConnectionState.CONNECTED -> LampState.On
                    ConnectionState.CONNECTING -> LampState.Busy
                    ConnectionState.DISCONNECTED -> LampState.Idle
                }
            )
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    address,
                    style = PherryTheme.text.mono,
                    color = c.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        when (state) {
            ConnectionState.CONNECTING -> {
                Spacer(Modifier.height(Spacing.md))
                Text(
                    "Make sure Pherry Desktop is open on ${serverName.ifBlank { "your computer" }} and both are on the same Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.ink2,
                )
            }
            ConnectionState.DISCONNECTED -> {
                Spacer(Modifier.height(Spacing.md))
                Text(
                    when {
                        remembered?.disconnectedByUser == true ->
                            "Disconnected. Pherry reconnects the next time it opens, or connect now."
                        offline ->
                            "Not answering. Make sure Pherry Desktop is open on ${savedName ?: "your computer"} and both are on the same Wi-Fi."
                        else -> "Pair this phone with Pherry Desktop on your computer to back up."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = c.ink2,
                )
            }
            ConnectionState.CONNECTED -> Unit
        }

        Spacer(Modifier.height(Spacing.lg))
        if (offline) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PrintButton(
                    if (remembered?.disconnectedByUser == true) "Connect" else "Try again",
                    onClick = onRetry,
                    icon = Ph.Refresh,
                    modifier = Modifier.weight(1f),
                )
                PrintButton(
                    "Change computer",
                    onClick = onManage,
                    style = PrintButtonStyle.Outline,
                    modifier = Modifier.weight(1f),
                )
            }
        } else if (state == ConnectionState.DISCONNECTED) {
            PrintButton("Pair a computer", onClick = onManage, icon = Ph.Desktop, modifier = Modifier.fillMaxWidth())
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PrintButton(
                    "Change computer",
                    onClick = onManage,
                    style = PrintButtonStyle.Outline,
                    modifier = Modifier.weight(1f),
                )
                // Disconnect only drops the link and keeps the pairing: quiet ink, not safelight red.
                PrintButton("Disconnect", onClick = onDisconnect, style = PrintButtonStyle.Quiet)
            }
        }
    }
}

@Composable
private fun AboutBlock(versionName: String?, modifier: Modifier = Modifier) {
    val c = PherryTheme.colors
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PherryMark(size = 48.dp)
            Spacer(Modifier.width(Spacing.md))
            Column {
                Text("Pherry", style = MaterialTheme.typography.titleMedium, color = c.ink)
                if (versionName != null) {
                    Text("Version $versionName", style = PherryTheme.text.mono, color = c.ink2)
                }
            }
        }
        Spacer(Modifier.height(Spacing.md))
        Text("Open source, MIT licence.", style = MaterialTheme.typography.bodySmall, color = c.ink2)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            "Photos travel only between this phone and your computer, over your own network.",
            style = MaterialTheme.typography.bodySmall,
            color = c.ink2,
        )
    }
}

private fun Context.appVersionName(): String? = runCatching {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0)
    }
    info.versionName
}.getOrNull()?.takeIf { it.isNotBlank() }
