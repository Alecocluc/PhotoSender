package com.appharbor.photosender.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appharbor.photosender.ui.theme.LocalExtendedColors
import com.appharbor.photosender.ui.theme.Spacing

/**
 * @param fillWidth  true = full-width pill container (ModeToggle style);
 *                   false = loose chip row (filter/theme chips).
 * @param leadingIcons  optional per-value icons drawn before the label.
 */
@Composable
fun <T> SegmentedToggle(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false,
    leadingIcons: Map<T, ImageVector> = emptyMap(),
) {
    val extColors = LocalExtendedColors.current

    if (fillWidth) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.extraLarge)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            options.forEach { (value, label) ->
                val isSelected = selected == value
                val icon = leadingIcons[value]
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.large)
                        .then(
                            if (isSelected) Modifier.background(extColors.buttonGradient)
                            else Modifier
                        )
                        .clickable { onSelect(value) }
                        .padding(vertical = Spacing.md),
                    contentAlignment = Alignment.Center,
                ) {
                    if (icon != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                icon,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.width(Spacing.sm - 2.dp))
                            SegmentLabel(label, isSelected)
                        }
                    } else {
                        SegmentLabel(label, isSelected)
                    }
                }
            }
        }
    } else {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            options.forEach { (value, label) ->
                val isSelected = selected == value
                val icon = leadingIcons[value]
                Box(
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.extraLarge)
                        .then(
                            if (isSelected) Modifier.background(extColors.buttonGradient)
                            else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        )
                        .clickable { onSelect(value) }
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    contentAlignment = Alignment.Center,
                ) {
                    if (icon != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                icon,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.width(Spacing.sm - 2.dp))
                            SegmentLabel(label, isSelected)
                        }
                    } else {
                        SegmentLabel(label, isSelected)
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentLabel(label: String, selected: Boolean) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
    )
}
