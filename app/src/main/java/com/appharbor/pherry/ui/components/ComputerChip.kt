package com.appharbor.pherry.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.appharbor.pherry.data.model.ConnectionState
import com.appharbor.pherry.data.network.RememberedComputer
import com.appharbor.pherry.ui.theme.PherryShape
import com.appharbor.pherry.ui.theme.PherryTheme
import com.appharbor.pherry.ui.theme.Spacing

/**
 * Top-bar chip naming the paired computer, with its lamp. Opens the connect sheet. [remembered] is
 * the saved computer (null when none): a saved computer that isn't answering reads "offline", not
 * "not paired".
 */
@Composable
fun ComputerChip(
    connectionState: ConnectionState,
    serverName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    remembered: RememberedComputer? = null,
) {
    val c = PherryTheme.colors
    val (lamp, label, a11y) = when (connectionState) {
        ConnectionState.CONNECTED -> Triple(LampState.On, serverName.ifBlank { "Computer" }, "Connected to ${serverName.ifBlank { "your computer" }}. Manage connection")
        ConnectionState.CONNECTING -> Triple(LampState.Busy, "Connecting", "Connecting to your computer")
        ConnectionState.DISCONNECTED -> if (remembered != null) {
            val name = remembered.name.ifBlank { "Computer" }
            val spoken = remembered.name.ifBlank { "your computer" }
            Triple(
                LampState.Idle,
                "$name · offline",
                if (remembered.disconnectedByUser) "Disconnected from $spoken. Manage connection" else "Can't reach $spoken. Manage connection",
            )
        } else {
            Triple(LampState.Idle, "Not paired", "No computer paired. Pair a computer")
        }
    }
    Row(
        modifier = modifier
            .heightIn(min = Spacing.touch)
            .clip(PherryShape.button)
            .clickable(role = Role.Button, onClick = onClick)
            // One spoken label: the visible name and lamp would otherwise be read again after it.
            .clearAndSetSemantics {
                contentDescription = a11y
                role = Role.Button
            }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .border(1.dp, c.rule, PherryShape.button)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Lamp(lamp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = if (connectionState == ConnectionState.CONNECTED) PherryTheme.text.mono.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize) else MaterialTheme.typography.labelLarge,
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 150.dp),
            )
        }
    }
}
