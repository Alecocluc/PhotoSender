package com.appharbor.photosender.data.network

import com.appharbor.photosender.data.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionManager @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _serverName = MutableStateFlow("")
    val serverName: StateFlow<String> = _serverName.asStateFlow()

    private val _connectedIp = MutableStateFlow("")
    val connectedIp: StateFlow<String> = _connectedIp.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    private var heartbeatJob: Job? = null

    fun connect(ip: String) {
        _connectionState.value = ConnectionState.CONNECTING
        _connectionError.value = null
        _connectedIp.value = ip
        scope.launch {
            val success = performHealthCheck(ip)
            if (success) {
                _connectionState.value = ConnectionState.CONNECTED
                startHeartbeat(ip)
            } else {
                _connectionState.value = ConnectionState.DISCONNECTED
                _connectedIp.value = ""
            }
        }
    }

    fun disconnect() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedIp.value = ""
        _serverName.value = ""
    }

    private fun startHeartbeat(ip: String) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                delay(5000)
                val ok = performHealthCheck(ip)
                if (!ok) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _connectedIp.value = ""
                    _serverName.value = ""
                    break
                }
            }
        }
    }

    private fun performHealthCheck(ip: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("http://$ip:3210/health")
                .get()
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                try {
                    val json = JSONObject(body)
                    _serverName.value = json.optString("serverName", "Desktop")
                } catch (_: Exception) {
                    _serverName.value = "Desktop"
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            _connectionError.value = "Could not reach server: ${e.localizedMessage ?: "unknown error"}"
            false
        }
    }

    fun getBaseUrl(): String = "http://${_connectedIp.value}:3210"
}
