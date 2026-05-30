package com.appharbor.pherry.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appharbor.pherry.data.model.ConnectionState
import com.appharbor.pherry.data.network.ConnectionManager
import com.appharbor.pherry.data.network.DiscoveredDesktop
import com.appharbor.pherry.data.network.NsdDiscovery
import com.appharbor.pherry.data.network.parseConnectionTarget
import com.appharbor.pherry.data.preferences.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val connectionError: StateFlow<String?> = connectionManager.connectionError

    /** Live list of "_pherry._tcp" desktops found on the LAN while the sheet is open. */
    val nearbyDesktops: StateFlow<List<DiscoveredDesktop>> = nsdDiscovery.desktops

    private val _ipAddress = MutableStateFlow("")
    val ipAddress: StateFlow<String> = _ipAddress.asStateFlow()

    private val _ipError = MutableStateFlow<String?>(null)
    val ipError: StateFlow<String?> = _ipError.asStateFlow()

    val recentDesktopTargets: StateFlow<List<String>> = appPreferences.recentDesktopTargets
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
        _ipAddress.value = ip
        _ipError.value = null
    }

    fun onRecentTargetSelected(target: String) {
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
        connectionManager.connect(endpoint)
    }

    fun onDisconnect() {
        connectionManager.disconnect()
    }

}
