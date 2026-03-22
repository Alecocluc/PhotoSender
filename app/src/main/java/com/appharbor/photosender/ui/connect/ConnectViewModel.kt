package com.appharbor.photosender.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appharbor.photosender.data.model.ConnectionState
import com.appharbor.photosender.data.network.ConnectionManager
import com.appharbor.photosender.data.preferences.AppPreferences
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
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState
    val serverName: StateFlow<String> = connectionManager.serverName
    val connectionError: StateFlow<String?> = connectionManager.connectionError

    private val _ipAddress = MutableStateFlow("")
    val ipAddress: StateFlow<String> = _ipAddress.asStateFlow()

    private val _ipError = MutableStateFlow<String?>(null)
    val ipError: StateFlow<String?> = _ipError.asStateFlow()

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

    fun onConnect() {
        val ip = _ipAddress.value.trim()
        if (!isValidIp(ip)) {
            _ipError.value = "Enter a valid IP address (e.g. 192.168.1.42)"
            return
        }
        _ipError.value = null
        viewModelScope.launch {
            appPreferences.saveLastIpAddress(ip)
        }
        connectionManager.connect(ip)
    }

    fun onDisconnect() {
        connectionManager.disconnect()
    }

    private fun isValidIp(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { part ->
            val num = part.toIntOrNull() ?: return false
            num in 0..255
        }
    }
}
