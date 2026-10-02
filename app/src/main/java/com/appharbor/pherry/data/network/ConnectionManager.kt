package com.appharbor.pherry.data.network

import com.appharbor.pherry.data.model.ConnectionState
import com.appharbor.pherry.data.preferences.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The computer this phone last paired with, kept while it isn't answering. [name] may be blank (not
 * learned yet). [disconnectedByUser] is true when the link is down because the user tapped Disconnect,
 * not because the computer stopped answering.
 */
data class RememberedComputer(val name: String, val address: String, val disconnectedByUser: Boolean = false)

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

    private val _disconnectedByUser = MutableStateFlow(false)
    /** True after the user taps Disconnect, until they connect again. */
    val disconnectedByUser: StateFlow<Boolean> = _disconnectedByUser.asStateFlow()

    /** Set when the user explicitly disconnects, so a dropped heartbeat doesn't auto-reconnect. */
    private var userDisconnected: Boolean
        get() = _disconnectedByUser.value
        set(value) { _disconnectedByUser.value = value }

    private val _canDelete = MutableStateFlow(false)
    /**
     * Whether this connection carries the desktop's pairing token. Without it (paired from the Wi-Fi
     * list or by typing the address) the desktop refuses Sync deletions.
     */
    val canDelete: StateFlow<Boolean> = _canDelete.asStateFlow()

    /**
     * The saved computer, or null when none is saved. Disconnect keeps it (the next start reconnects),
     * so the UI can say "can't reach ALEX-PC" instead of "not paired" while it's asleep or off.
     */
    val rememberedComputer: StateFlow<RememberedComputer?> = combine(
        appPreferences.lastIpAddress,
        appPreferences.lastServerName,
        _disconnectedByUser,
    ) { address, name, byUser ->
        if (address.isBlank()) null else RememberedComputer(name = name, address = address, disconnectedByUser = byUser)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private fun setToken(token: String) {
        session.token = token
        _canDelete.value = session.token.isNotBlank()
    }

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
            // The health check is token-less, so probe first to learn the desktop's stable id, then
            // resolve the pairing token by that id. This keeps delete rights working after the PC's
            // IP changes — a token stored under the old endpoint would otherwise be missed.
            val health = performHealthCheck(target, silent)
            if (health != null) {
                setToken(resolveToken(target, health.deviceId))
                appPreferences.saveLastServer(target.endpoint, health.serverName)
                _connectionState.value = ConnectionState.CONNECTED
                startMonitor(target)
            } else {
                setToken("")
                _connectionState.value = ConnectionState.DISCONNECTED
                _connectedIp.value = ""
                _connectedEndpoint.value = ""
            }
        }
    }

    /**
     * Pick the pairing token for this connection and bind it to the desktop's stable [deviceId] so
     * it survives the PC's IP changing. Priority: a token from the (QR) payload, then one already
     * stored for this id, then a legacy token keyed by the endpoint — which is migrated onto the id
     * and the stale endpoint entry dropped. Old desktops that report no id fall back to endpoint keying.
     */
    private suspend fun resolveToken(target: ConnectionTarget, deviceId: String): String {
        if (target.token.isNotBlank()) {
            appPreferences.rememberDesktopToken(deviceId.ifBlank { target.endpoint }, target.token)
            return target.token
        }
        if (deviceId.isBlank()) return appPreferences.tokenForEndpoint(target.endpoint)

        appPreferences.tokenForDevice(deviceId).takeIf { it.isNotBlank() }?.let { return it }

        val legacy = appPreferences.tokenForEndpoint(target.endpoint)
        if (legacy.isNotBlank()) {
            appPreferences.rememberDesktopToken(deviceId, legacy)
            appPreferences.forgetDesktopToken(target.endpoint)
        }
        return legacy
    }

    fun disconnect() {
        userDisconnected = true
        monitorJob?.cancel()
        monitorJob = null
        setToken("")
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

                if (performHealthCheck(target, silent = true) != null) {
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

    /** Successful /health response: the desktop's display name and its stable device id (may be blank). */
    private data class HealthResult(val serverName: String, val deviceId: String)

    private fun performHealthCheck(target: ConnectionTarget, silent: Boolean): HealthResult? {
        return try {
            val request = Request.Builder()
                .url("${target.baseUrl}/health")
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = runCatching { JSONObject(body) }.getOrNull()
                    val name = json?.optString("serverName", "Desktop")?.takeIf { it.isNotBlank() } ?: "Desktop"
                    val deviceId = json?.optString("deviceId", "")?.trim().orEmpty()
                    _serverName.value = name
                    HealthResult(name, deviceId)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            if (!silent) {
                _connectionError.value = "Could not reach server: ${e.localizedMessage ?: "unknown error"}"
            }
            null
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
        val health = performHealthCheck(target, silent = true) ?: return false
        setToken(resolveToken(target, health.deviceId))
        appPreferences.saveLastServer(target.endpoint, health.serverName)
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
