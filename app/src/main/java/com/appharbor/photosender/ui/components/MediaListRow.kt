package com.appharbor.photosender.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.appharbor.photosender.ui.theme.Spacing

/**
 * Unified list row: 44×44 leading icon/thumbnail box + title + subtitle(s) + optional trailing/below slots.
 * Replaces TransferItem, DuplicateSkipItem, and HistoryItem.
 */
@Composable
fun MediaListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    subtitle2: String = "",
    leadingBoxColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    leadingContent: @Composable BoxScope.() -> Unit = {},
    trailingContent: (@Composable () -> Unit)? = null,
    belowContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(leadingBoxColor),
            contentAlignment = Alignment.Center,
            content = leadingContent,
        )

        Spacer(Modifier.width(Spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (trailingContent != null) {
                    Spacer(Modifier.width(Spacing.sm))
                    trailingContent()
                }
            }
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (subtitle2.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle2,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (belowContent != null) {
                Spacer(Modifier.height(Spacing.sm))
                belowContent()
            }
        }
    }
}
