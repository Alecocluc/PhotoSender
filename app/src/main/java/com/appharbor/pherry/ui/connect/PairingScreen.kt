package com.appharbor.pherry.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appharbor.pherry.data.model.ConnectionState
import com.appharbor.pherry.ui.components.PherryWordmark
import com.appharbor.pherry.ui.components.PrimaryButton
import com.appharbor.pherry.ui.theme.Spacing
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

@Composable
fun PairingScreen(
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConnectViewModel = hiltViewModel(),
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val serverName by viewModel.serverName.collectAsStateWithLifecycle()
    val ipAddress by viewModel.ipAddress.collectAsStateWithLifecycle()
    val ipError by viewModel.ipError.collectAsStateWithLifecycle()
    val connectionError by viewModel.connectionError.collectAsStateWithLifecycle()
    val recentTargets by viewModel.recentDesktopTargets.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    fun launchQrScanner() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { barcode -> viewModel.onScannedPayload(barcode.rawValue) }
            .addOnCanceledListener { }
            .addOnFailureListener { e -> viewModel.onQrScanError(e.localizedMessage) }
    }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PherryWordmark()
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onSkip) {
                    Text("Skip")
                }
            }

            Spacer(Modifier.height(Spacing.xxxl))

            Text(
                text = "Pair your desktop",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = "Open Pherry Desktop on your computer, then scan the QR code shown on the Receiver screen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(Spacing.xl))

            PairingPanel(
                connectionState = connectionState,
                serverName = serverName,
                onScanQr = {
                    focusManager.clearFocus()
                    launchQrScanner()
                },
            )

            if (connectionState == ConnectionState.CONNECTED) {
                Spacer(Modifier.height(Spacing.lg))
                PrimaryButton(onClick = onContinue) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.sm))
                    Text("Continue to Library", fontWeight = FontWeight.SemiBold)
                }
            } else {
                Spacer(Modifier.height(Spacing.lg))
                val addressError = ipError ?: connectionError
                OutlinedTextField(
                    value = ipAddress,
                    onValueChange = viewModel::onIpChanged,
                    label = { Text("Desktop address") },
                    placeholder = { Text("192.168.1.15:3210") },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Link,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            viewModel.onConnect()
                        }
                    ),
                    isError = addressError != null,
                    supportingText = addressError?.let { message ->
                        { Text(message, color = MaterialTheme.colorScheme.error) }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (recentTargets.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.md))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text(
                            text = "Recent desktops",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        recentTargets.take(2).forEach { target ->
                            AssistChip(
                                onClick = { viewModel.onRecentTargetSelected(target) },
                                label = { Text(target) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.Computer,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(Spacing.md))
                PrimaryButton(
                    onClick = {
                        focusManager.clearFocus()
                        viewModel.onConnect()
                    },
                    enabled = connectionState != ConnectionState.CONNECTING,
                ) {
                    Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        text = if (connectionState == ConnectionState.CONNECTING) "Connecting..." else "Connect desktop",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}

@Composable
fun PairingPanel(
    connectionState: ConnectionState,
    serverName: String,
    onScanQr: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PairingQrScanner(
            connectionState = connectionState,
            onScanQr = onScanQr,
        )
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = when (connectionState) {
                ConnectionState.CONNECTED -> "Paired with ${serverName.ifBlank { "Pherry Desktop" }}"
                ConnectionState.CONNECTING -> "Connecting..."
                ConnectionState.DISCONNECTED -> "Point at the desktop QR code"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = when (connectionState) {
                ConnectionState.CONNECTED -> "You can start ferrying media now."
                ConnectionState.CONNECTING -> "Checking the receiver on your local network."
                ConnectionState.DISCONNECTED -> "The QR contains the local address of Pherry Desktop."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun PairingQrScanner(
    connectionState: ConnectionState,
    onScanQr: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connected = connectionState == ConnectionState.CONNECTED
    Box(
        modifier = modifier
            .size(176.dp)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(
                if (connected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(118.dp)
                .clip(CircleShape)
                .background(
                    if (connected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.primaryContainer,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (connected) Icons.Filled.CheckCircle else Icons.Filled.QrCodeScanner,
                contentDescription = null,
                tint = if (connected) Color.White else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(54.dp),
            )
        }
        if (!connected) {
            TextButton(
                onClick = onScanQr,
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Text("Scan QR")
            }
        }
    }
}
