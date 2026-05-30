package com.appharbor.pherry.data.network

import com.appharbor.pherry.data.model.ConnectionState
import com.appharbor.pherry.data.preferences.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectionManager @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val appPreferences: AppPreferences,
    private val session: PherrySession,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _serverName = MutableStateFlow("")
    val serverName: StateFlow<String> = _serverName.asStateFlow()

    private val _connectedIp = MutableStateFlow("")
    val connectedIp: StateFlow<String> = _connectedIp.asStateFlow()

    private val _connectedEndpoint = MutableStateFlow("")
    val connectedEndpoint: StateFlow<String> = _connectedEndpoint.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    private var monitorJob: Job? = null
    /** Set when the user explicitly disconnects, so a dropped heartbeat doesn't auto-reconnect. */
    @Volatile private var userDisconnected = false

    fun connect(targetInput: String) = connectInternal(targetInput, silent = false)

    /**
     * Try to silently restore the last desktop on app launch. No error is surfaced if it fails —
     * the desktop may just not be running yet — so the UI simply stays "Not connected".
     */
    fun autoReconnect() {
        if (_connectionState.value != ConnectionState.DISCONNECTED) return
        scope.launch {
            val last = appPreferences.lastIpAddress.first()
            if (last.isNotBlank()) connectInternal(last, silent = true)
        }
    }

    private fun connectInternal(targetInput: String, silent: Boolean) {
        val target = parseConnectionTarget(targetInput)
        if (target == null) {
            if (!silent) {
                _connectionError.value = "Enter a valid desktop address"
                _connectionState.value = ConnectionState.DISCONNECTED
            }
            return
        }
        userDisconnected = false
        _connectionState.value = ConnectionState.CONNECTING
        _connectionError.value = null
        _connectedIp.value = target.host
        _connectedEndpoint.value = target.endpoint

        scope.launch {
            // Resolve a token: prefer one carried in the (QR) payload, otherwise a previously
            // stored one for this endpoint. Persist QR tokens so future reconnects keep delete rights.
            val token = target.token.ifBlank { appPreferences.tokenForEndpoint(target.endpoint) }
            session.token = token
            if (target.token.isNotBlank()) {
                appPreferences.rememberDesktopToken(target.endpoint, target.token)
            }

            if (performHealthCheck(target, silent)) {
                _connectionState.value = ConnectionState.CONNECTED
                startMonitor(target)
            } else {
                _connectionState.value = ConnectionState.DISCONNECTED
                _connectedIp.value = ""
                _connectedEndpoint.value = ""
            }
        }
    }

    fun disconnect() {
        userDisconnected = true
        monitorJob?.cancel()
        monitorJob = null
        session.token = ""
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedIp.value = ""
        _connectedEndpoint.value = ""
        _serverName.value = ""
    }

    /**
     * Heartbeat + self-healing. While connected we poll every [HEARTBEAT_MS]. On a dropped beat we
     * flip to CONNECTING and retry with exponential backoff instead of giving up — so a WiFi blip or
     * the desktop briefly sleeping reconnects on its own. After [MAX_FAILURES] consecutive misses we
     * finally fall back to DISCONNECTED.
     */
    private fun startMonitor(target: ConnectionTarget) {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            var backoff = INITIAL_BACKOFF_MS
            var failures = 0
            while (true) {
                val interval = if (_connectionState.value == ConnectionState.CONNECTED) HEARTBEAT_MS else backoff
                delay(interval)
                if (userDisconnected) break

                if (performHealthCheck(target, silent = true)) {
                    failures = 0
                    backoff = INITIAL_BACKOFF_MS
                    _connectionError.value = null
                    if (_connectionState.value != ConnectionState.CONNECTED) {
                        _connectionState.value = ConnectionState.CONNECTED
                        _connectedIp.value = target.host
                        _connectedEndpoint.value = target.endpoint
                    }
                } else {
                    failures++
                    if (failures >= MAX_FAILURES) {
                        _connectionState.value = ConnectionState.DISCONNECTED
                        _connectedIp.value = ""
                        _connectedEndpoint.value = ""
                        _serverName.value = ""
                        break
                    }
                    // Reconnecting: keep the endpoint so uploads in flight can still resolve it.
                    _connectionState.value = ConnectionState.CONNECTING
                    backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
        }
    }

    private fun performHealthCheck(target: ConnectionTarget, silent: Boolean): Boolean {
        return try {
            val request = Request.Builder()
                .url("${target.baseUrl}/health")
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    _serverName.value = try {
                        JSONObject(body).optString("serverName", "Desktop")
                    } catch (_: Exception) {
                        "Desktop"
                    }
                    true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            if (!silent) {
                _connectionError.value = "Could not reach server: ${e.localizedMessage ?: "unknown error"}"
            }
            false
        }
    }

    /**
     * Used by the background auto-backup worker: make sure we're connected to the last desktop
     * (setting the pairing token) so queued uploads can resolve a server. Returns whether the
     * desktop is reachable right now.
     */
    suspend fun ensureConnectedToLast(): Boolean {
        if (_connectionState.value == ConnectionState.CONNECTED && _connectedEndpoint.value.isNotBlank()) {
            return true
        }
        val last = appPreferences.lastIpAddress.first()
        val target = parseConnectionTarget(last) ?: return false
        session.token = appPreferences.tokenForEndpoint(target.endpoint)
        if (!performHealthCheck(target, silent = true)) return false
        userDisconnected = false
        _connectedIp.value = target.host
        _connectedEndpoint.value = target.endpoint
        _connectionState.value = ConnectionState.CONNECTED
        _connectionError.value = null
        startMonitor(target)
        return true
    }

    fun getBaseUrl(): String = baseUrlForConnectionTarget(_connectedEndpoint.value) ?: ""

    fun getConnectedEndpoint(): String = _connectedEndpoint.value

    fun baseUrlForTarget(target: String): String? = baseUrlForConnectionTarget(target)

    private companion object {
        const val HEARTBEAT_MS = 5000L
        const val INITIAL_BACKOFF_MS = 2000L
        const val MAX_BACKOFF_MS = 30000L
        const val MAX_FAILURES = 12
    }
}
