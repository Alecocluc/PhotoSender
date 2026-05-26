package com.appharbor.pherry.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Brightness4
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appharbor.pherry.data.preferences.ThemeMode
import com.appharbor.pherry.ui.components.PherryMark
import com.appharbor.pherry.ui.components.ScreenHeader
import com.appharbor.pherry.ui.components.SectionCard
import com.appharbor.pherry.ui.components.SegmentedToggle
import com.appharbor.pherry.ui.components.ToggleRow
import com.appharbor.pherry.ui.gallery.UploadMode
import com.appharbor.pherry.ui.theme.Spacing

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val dynamicColorEnabled by viewModel.dynamicColorEnabled.collectAsStateWithLifecycle()
    val highSpeedTransferEnabled by viewModel.highSpeedTransferEnabled.collectAsStateWithLifecycle()
    val autoArchiveEnabled by viewModel.autoArchiveEnabled.collectAsStateWithLifecycle()
    val confirmDestructiveSync by viewModel.confirmDestructiveSync.collectAsStateWithLifecycle()
    val wifiOnlyTransfer by viewModel.wifiOnlyTransfer.collectAsStateWithLifecycle()
    val keepScreenAwake by viewModel.keepScreenAwake.collectAsStateWithLifecycle()
    val defaultUploadModeName by viewModel.defaultUploadMode.collectAsStateWithLifecycle()
    val defaultUploadMode = UploadMode.entries.firstOrNull { it.name == defaultUploadModeName } ?: UploadMode.ADD

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.screen),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        item {
            Spacer(Modifier.height(Spacing.sm))
            ScreenHeader(title = "Settings", subtitle = "Appearance and transfer behavior")
            Spacer(Modifier.height(Spacing.sm))
        }

        item {
            SectionCard(title = "Appearance") {
                Text(
                    text = "Theme Mode",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Spacing.sm))

                SegmentedToggle(
                    options = listOf(
                        ThemeMode.SYSTEM to "System",
                        ThemeMode.LIGHT to "Light",
                        ThemeMode.DARK to "Dark",
                    ),
                    selected = themeMode,
                    onSelect = { viewModel.onThemeModeSelected(it) },
                    fillWidth = true,
                    leadingIcons = mapOf(
                        ThemeMode.SYSTEM to Icons.Outlined.Brightness6,
                        ThemeMode.LIGHT to Icons.Outlined.LightMode,
                        ThemeMode.DARK to Icons.Outlined.Brightness4,
                    ),
                )

                Spacer(Modifier.height(Spacing.md))

                ToggleRow(
                    icon = Icons.Filled.Palette,
                    title = "Dynamic Color",
                    subtitle = "Use Material You wallpaper-based colors",
                    checked = dynamicColorEnabled,
                    onCheckedChange = viewModel::onDynamicColorChanged,
                )
            }
        }

        item {
            SectionCard(title = "Transfer") {
                Text(
                    text = "Default action",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Spacing.sm))
                SegmentedToggle(
                    options = listOf(
                        UploadMode.ADD to "Add new",
                        UploadMode.SYNC to "Sync library",
                    ),
                    selected = defaultUploadMode,
                    onSelect = viewModel::onDefaultUploadModeSelected,
                    fillWidth = true,
                    leadingIcons = mapOf(
                        UploadMode.ADD to Icons.Filled.Storage,
                        UploadMode.SYNC to Icons.Filled.Sync,
                    ),
                )
                Spacer(Modifier.height(Spacing.md))
                ToggleRow(
                    icon = Icons.Filled.Speed,
                    title = "High-Speed Transfer",
                    subtitle = "Up to 6 parallel uploads. Turn off on unstable routers.",
                    checked = highSpeedTransferEnabled,
                    onCheckedChange = viewModel::onHighSpeedTransferChanged,
                )
                Spacer(Modifier.height(Spacing.sm))
                ToggleRow(
                    icon = Icons.Filled.DeleteSweep,
                    title = "Confirm desktop deletes",
                    subtitle = "Ask before Sync removes files from the PC",
                    checked = confirmDestructiveSync,
                    onCheckedChange = viewModel::onConfirmDestructiveSyncChanged,
                )
                Spacer(Modifier.height(Spacing.sm))
                ToggleRow(
                    icon = Icons.Filled.Wifi,
                    title = "Wi-Fi only",
                    subtitle = "Pause queued transfers on metered networks",
                    checked = wifiOnlyTransfer,
                    onCheckedChange = viewModel::onWifiOnlyTransferChanged,
                )
                Spacer(Modifier.height(Spacing.sm))
                ToggleRow(
                    icon = Icons.Filled.BatteryChargingFull,
                    title = "Keep screen awake",
                    subtitle = "Prevent dimming while Activity is open during a transfer",
                    checked = keepScreenAwake,
                    onCheckedChange = viewModel::onKeepScreenAwakeChanged,
                )
                Spacer(Modifier.height(Spacing.sm))
                ToggleRow(
                    icon = Icons.Filled.Storage,
                    title = "Auto-Archive",
                    subtitle = "Archive transferred files after 30 days",
                    checked = autoArchiveEnabled,
                    onCheckedChange = viewModel::onAutoArchiveChanged,
                    enabled = false,
                    badge = "SOON",
                )
            }
        }

        item {
            SectionCard(title = "About") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PherryMark(size = 48.dp, cornerRadius = 14.dp)
                    Spacer(Modifier.width(Spacing.md))
                    Column {
                        Text(
                            text = "Pherry",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Version 1.0 · photo ferry",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.md))
                Text(
                    text = "Ferry photos and videos to your computer over your local network — no cables, no cloud.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { Spacer(Modifier.height(Spacing.xxl)) }
    }
}
