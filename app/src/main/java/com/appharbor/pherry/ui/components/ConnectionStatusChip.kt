package com.appharbor.pherry.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appharbor.pherry.data.model.ConnectionState
import com.appharbor.pherry.ui.theme.Spacing

@Composable
fun ConnectionStatusChip(
    connectionState: ConnectionState,
    serverName: String = "",
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dotColor = when (connectionState) {
        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.tertiary
        ConnectionState.CONNECTING -> MaterialTheme.colorScheme.primary
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.error
    }
    val label = when (connectionState) {
        ConnectionState.CONNECTED -> serverName.takeIf { it.isNotBlank() } ?: "Linked"
        ConnectionState.CONNECTING -> "Connecting..."
        ConnectionState.DISCONNECTED -> "Connect"
    }
    val chipBackground = when (connectionState) {
        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primaryContainer
        ConnectionState.CONNECTING -> MaterialTheme.colorScheme.primaryContainer
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
    }
    val contentColor = when (connectionState) {
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }

    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.extraLarge)
            .background(chipBackground)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm - 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(Spacing.sm - 2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = contentColor,
        )
    }
}
