package com.appharbor.photosender.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.Brightness4
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appharbor.photosender.data.preferences.ThemeMode
import com.appharbor.photosender.ui.components.ScreenHeader
import com.appharbor.photosender.ui.components.SectionCard
import com.appharbor.photosender.ui.components.SegmentedToggle
import com.appharbor.photosender.ui.components.ToggleRow
import com.appharbor.photosender.ui.theme.Spacing

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val dynamicColorEnabled by viewModel.dynamicColorEnabled.collectAsStateWithLifecycle()
    val highSpeedTransferEnabled by viewModel.highSpeedTransferEnabled.collectAsStateWithLifecycle()
    val autoArchiveEnabled by viewModel.autoArchiveEnabled.collectAsStateWithLifecycle()

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
                ToggleRow(
                    icon = Icons.Filled.Speed,
                    title = "High-Speed Transfer",
                    subtitle = "Aggressive parallel uploads on stable Wi-Fi",
                    checked = highSpeedTransferEnabled,
                    onCheckedChange = viewModel::onHighSpeedTransferChanged,
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

        item { Spacer(Modifier.height(Spacing.xxl)) }
    }
}
