package com.appharbor.pherry.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appharbor.pherry.data.model.ConnectionState
import com.appharbor.pherry.data.network.ConnectionManager
import com.appharbor.pherry.data.network.DiscoveredDesktop
import com.appharbor.pherry.data.network.NsdDiscovery
import com.appharbor.pherry.data.network.parseConnectionTarget
import com.appharbor.pherry.data.preferences.AppPreferences
import com.appharbor.pherry.ui.transfer.reasonCopy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
    private val appPreferences: AppPreferences,
    private val nsdDiscovery: NsdDiscovery,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState
    val serverName: StateFlow<String> = connectionManager.serverName

    /** Live list of "_pherry._tcp" desktops found on the LAN while the sheet is open. */
    val nearbyDesktops: StateFlow<List<DiscoveredDesktop>> = nsdDiscovery.desktops

    private val _ipAddress = MutableStateFlow("")
    val ipAddress: StateFlow<String> = _ipAddress.asStateFlow()
    private val _pairingCode = MutableStateFlow("")
    val pairingCode = _pairingCode.asStateFlow()

    fun onPairingCodeChanged(code: String) {
        _pairingCode.value = code.filter { it.isLetterOrDigit() }.take(12)
        _ipError.value = null
    }

    private val _ipError = MutableStateFlow<String?>(null)
    val ipError: StateFlow<String?> = _ipError.asStateFlow()

    val recentDesktopTargets: StateFlow<List<String>> = appPreferences.recentDesktopTargets
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Whether auto-backup is on: the disconnect confirmation says whether it will reconnect by itself. */
    val autoBackupEnabled: StateFlow<Boolean> = appPreferences.autoBackupEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** The address the phone is linked to, or trying to reach, e.g. "192.168.1.42:3210". */
    val connectedEndpoint: StateFlow<String> = connectionManager.connectedEndpoint

    /**
     * The one pairing problem to show: a bad address or a failed scan first, then a failed
     * connection, reworded to say what to check.
     */
    val pairingProblem: StateFlow<String?> = combine(_ipError, connectionManager.connectionReason) { ipError, reason ->
        ipError ?: reasonCopy(reason, serverName.value.ifBlank { "your computer" })?.let { "${it.title}. ${it.detail}" }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        viewModelScope.launch {
            appPreferences.lastIpAddress.collect { savedIp ->
                if (_ipAddress.value.isEmpty() && savedIp.isNotEmpty()) {
                    _ipAddress.value = savedIp
                }
            }
        }
    }

    fun onIpChanged(ip: String) {
        if (_ipAddress.value != ip) _pairingCode.value = ""
        _ipAddress.value = ip
        _ipError.value = null
    }

    fun onRecentTargetSelected(target: String) {
        _pairingCode.value = ""
        _ipAddress.value = target
        onConnect()
    }

    fun startDiscovery() = nsdDiscovery.start()

    fun stopDiscovery() = nsdDiscovery.stop()

    fun onDiscoveredSelected(desktop: DiscoveredDesktop) {
        _ipAddress.value = desktop.endpoint
        onConnect()
    }

    fun onQrScanError(message: String?) {
        _ipError.value = message ?: "QR scan failed. Enter the desktop address manually."
    }

    fun onScannedPayload(payload: String?) {
        if (payload == null) return
        val parsed = parseConnectionTarget(payload)
        if (parsed == null) {
            _ipError.value = "That QR code is not a Pherry desktop address."
            return
        }
        _ipAddress.value = parsed.endpoint
        _pairingCode.value = ""
        _ipError.value = null
        viewModelScope.launch {
            appPreferences.rememberDesktopTarget(parsed.endpoint)
        }
        // Pass the raw payload so the pairing token in the QR is captured (the displayed address
        // only shows the endpoint).
        connectionManager.connect(payload)
    }

    fun onConnect() {
        val target = parseConnectionTarget(_ipAddress.value)
        if (target == null) {
            _ipError.value = "Enter a valid desktop address, for example 192.168.1.42:3210"
            return
        }
        val endpoint = target.endpoint
        _ipError.value = null
        viewModelScope.launch {
            appPreferences.rememberDesktopTarget(endpoint)
        }
        connectionManager.connect(_ipAddress.value, pairingCode = _pairingCode.value)
    }

    fun onDisconnect() {
        connectionManager.disconnect()
    }

}
