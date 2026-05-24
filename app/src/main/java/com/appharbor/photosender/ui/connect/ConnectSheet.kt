package com.appharbor.photosender.ui.connect

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appharbor.photosender.data.model.ConnectionState
import com.appharbor.photosender.ui.components.GradientButton
import com.appharbor.photosender.ui.theme.Spacing

@Composable
fun ConnectSheet(
    onDismiss: () -> Unit,
    viewModel: ConnectViewModel = hiltViewModel(),
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val ipAddress by viewModel.ipAddress.collectAsStateWithLifecycle()
    val ipError by viewModel.ipError.collectAsStateWithLifecycle()
    val serverName by viewModel.serverName.collectAsStateWithLifecycle()
    val connectionError by viewModel.connectionError.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(start = Spacing.screen, end = Spacing.screen, bottom = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Drag handle visual affordance
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 4.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        )

        Spacer(Modifier.height(Spacing.lg))

        // Compact status row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            PulsingStatusDot(connectionState)
            Spacer(Modifier.width(Spacing.sm))
            Column {
                Text(
                    text = when (connectionState) {
                        ConnectionState.CONNECTED -> "Connected"
                        ConnectionState.CONNECTING -> "Connecting…"
                        ConnectionState.DISCONNECTED -> "Not connected"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (connectionState == ConnectionState.CONNECTED && serverName.isNotEmpty()) {
                    Text(
                        text = serverName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(Spacing.lg))

        // IP input
        OutlinedTextField(
            value = ipAddress,
            onValueChange = viewModel::onIpChanged,
            label = { Text("Desktop IP address") },
            placeholder = {
                Text(
                    "192.168.1.15",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                    viewModel.onConnect()
                }
            ),
            isError = ipError != null,
            supportingText = when {
                ipError != null -> {
                    { Text(ipError!!, color = MaterialTheme.colorScheme.error) }
                }
                connectionError != null -> {
                    { Text(connectionError!!, color = MaterialTheme.colorScheme.error) }
                }
                else -> null
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            ),
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(Spacing.md))

        // Connect / Disconnect
        if (connectionState == ConnectionState.CONNECTED) {
            TextButton(
                onClick = {
                    viewModel.onDisconnect()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Disconnect", color = MaterialTheme.colorScheme.error)
            }
        } else {
            GradientButton(
                onClick = {
                    focusManager.clearFocus()
                    viewModel.onConnect()
                },
                enabled = connectionState != ConnectionState.CONNECTING,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Filled.Bolt,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = Color.White,
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = if (connectionState == ConnectionState.CONNECTING) "Connecting…" else "Connect",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
            }
        }

        Spacer(Modifier.height(Spacing.lg))

        // "Where to find it" hint
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.13f))
                .padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Wifi,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(36.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                    .padding(8.dp),
            )
            Spacer(Modifier.width(Spacing.md))
            Column {
                Text(
                    text = "Same network required",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Open PhotoSender Desktop on your PC — the IP address appears in the bottom-left corner.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Device diagram
        Spacer(Modifier.height(Spacing.md))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Computer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
            }
            HorizontalDivider(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.sm),
                thickness = 1.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PhoneAndroid,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

@Composable
private fun PulsingStatusDot(state: ConnectionState) {
    val color by animateColorAsState(
        targetValue = when (state) {
            ConnectionState.CONNECTED -> MaterialTheme.colorScheme.tertiary
            ConnectionState.CONNECTING -> MaterialTheme.colorScheme.secondary
            ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.error
        },
        label = "dot_color",
    )
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (state == ConnectionState.CONNECTING) 1.35f else 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "dot_scale",
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (state == ConnectionState.CONNECTING) 0.5f else 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "dot_alpha",
    )
    Box(
        modifier = Modifier
            .size(12.dp)
            .scale(scale)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color),
    )
}
