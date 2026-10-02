package com.appharbor.pherry.data.network

import com.appharbor.pherry.data.model.ConnectionState
import com.appharbor.pherry.data.preferences.AppPreferences
import com.appharbor.pherry.data.upload.TransferReason
import com.appharbor.pherry.data.upload.transferReason
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class RememberedComputer(val name: String, val address: String, val disconnectedByUser: Boolean = false)

@Singleton
class ConnectionManager @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val appPreferences: AppPreferences,
    private val session: PherrySession,
    private val discovery: NsdDiscovery,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectMutex = Mutex()
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()
    private val _serverName = MutableStateFlow("")
    val serverName = _serverName.asStateFlow()
    private val _connectedEndpoint = MutableStateFlow("")
    val connectedEndpoint = _connectedEndpoint.asStateFlow()
    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError = _connectionError.asStateFlow()
    private val _connectionReason = MutableStateFlow(TransferReason.NONE)
    val connectionReason = _connectionReason.asStateFlow()
    private val _disconnectedByUser = MutableStateFlow(false)
    val disconnectedByUser = _disconnectedByUser.asStateFlow()
    private val _receiverIdentity = MutableStateFlow<ReceiverIdentity?>(null)
    val receiverIdentity = _receiverIdentity.asStateFlow()
    private var connectJob: Job? = null
    private var monitorJob: Job? = null

    val rememberedComputer = combine(appPreferences.lastIpAddress, appPreferences.lastServerName, _disconnectedByUser) { address, name, byUser ->
        if (address.isBlank()) null else RememberedComputer(name, address, byUser)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch {
            session.clientId = appPreferences.clientId()
            val id = appPreferences.receiverId.first()
            val library = appPreferences.libraryId.first()
            if (id.isNotBlank() && _receiverIdentity.value == null) _receiverIdentity.value = ReceiverIdentity(id, library)
        }
        scope.launch { appPreferences.deviceName.collect { session.deviceName = it } }
    }

    fun connect(targetInput: String) = connect(targetInput, "")
    fun connect(targetInput: String, pairingCode: String) {
        val target = parseConnectionTarget(targetInput)
        if (target == null) { _connectionError.value = "Enter a valid computer address"; return }
        connectJob?.cancel()
        monitorJob?.cancel()
        _disconnectedByUser.value = false
        _connectionState.value = ConnectionState.CONNECTING
        _connectionError.value = null
        _connectionReason.value = TransferReason.NONE
        connectJob = scope.launch {
            try {
                connectMutex.withLock { establish(target, pairingCode.ifBlank { target.token }) }
                startMonitor()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { markDisconnected(e.message, e.transferReason()) }
        }
    }

    fun autoReconnect() {
        _disconnectedByUser.value = false
        if (_connectionState.value == ConnectionState.CONNECTED) return
        scope.launch {
            val address = appPreferences.lastIpAddress.first()
            if (address.isNotBlank()) connect(address)
        }
    }

    fun disconnect() {
        _disconnectedByUser.value = true
        connectJob?.cancel()
        monitorJob?.cancel()
        markDisconnected(null)
    }

    private fun markDisconnected(message: String?, reason: TransferReason = TransferReason.NONE) {
        session.connection.set(null)
        _connectedEndpoint.value = ""
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectionError.value = message
        _connectionReason.value = reason
    }

    private suspend fun health(target: ConnectionTarget): JSONObject {
        val request = Request.Builder().url(target.baseUrl + "/health").build()
        return okHttpClient.newCall(request).awaitResponse().use {
            if (!it.isSuccessful) throw IOException("The receiver is not answering")
            JSONObject(it.body?.string().orEmpty()).also { json ->
                if (json.optInt("apiVersion") < 2 || json.optString("deviceId").isBlank() || json.optString("libraryId").isBlank()) {
                    throw TransferHttpException(426, "Update Pherry Desktop before pairing this phone", "PROTOCOL_UPDATE_REQUIRED")
                }
            }
        }
    }

    private suspend fun establish(target: ConnectionTarget, code: String = "", expected: ReceiverIdentity? = null): ReceiverConnection {
        val info = health(target)
        val identity = ReceiverIdentity(info.getString("deviceId"), info.getString("libraryId"))
        if (expected != null && identity.deviceId != expected.deviceId) throw IOException("This discovered address is a different receiver")
        if (expected != null && identity.libraryId != expected.libraryId) throw TransferHttpException(409, "This computer changed its destination folder", "DESTINATION_CHANGED")
        val credential = if (code.isNotBlank()) {
            // Freeze enrollment proof before the request: a lost first response must not strand
            // the stable client identity behind a credential the phone never received.
            val enrollmentCredential = EnrollmentCredentials.reuseOrCreate(appPreferences.tokenForDevice(identity.deviceId))
            appPreferences.rememberDesktopToken(identity.deviceId, enrollmentCredential)
            val payload = JSONObject().put("pairingCode", code).put("clientId", appPreferences.clientId())
                .put("deviceName", appPreferences.deviceName.first())
                .put("credential", enrollmentCredential)
            val request = Request.Builder().url(target.baseUrl + "/pair")
                .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
            okHttpClient.newCall(request).awaitResponse().use {
                val json = runCatching { JSONObject(it.body?.string().orEmpty()) }.getOrElse { JSONObject() }
                if (!it.isSuccessful) throw TransferHttpException(it.code, json.optString("error", "Pairing code was not accepted"), json.optString("code"))
                if (json.optString("deviceId") != identity.deviceId || json.optString("libraryId") != identity.libraryId) throw IOException("Receiver changed during pairing")
                json.getString("credential").also { token -> appPreferences.rememberDesktopToken(identity.deviceId, token) }
            }
        } else appPreferences.tokenForDevice(identity.deviceId)
        if (credential.isBlank()) throw TransferHttpException(401, "Enter this computer's pairing code or scan its ticket", "PAIRING_REQUIRED")
        val connection = ReceiverConnection(identity, target.baseUrl, credential)
        // Verify enrollment before showing Connected.
        TransferApi(okHttpClient, connection).json("/v2/preflight", "POST", JSONObject().put("totalBytes", 0).put("totalFiles", 0))
        currentCoroutineContext().ensureActive()
        session.clientId = appPreferences.clientId()
        session.connection.set(connection)
        _receiverIdentity.value = identity
        _connectedEndpoint.value = target.endpoint
        _serverName.value = info.optString("serverName", "Computer")
        _connectionState.value = ConnectionState.CONNECTED
        _connectionError.value = null
        _connectionReason.value = TransferReason.NONE
        appPreferences.rememberReceiver(identity.deviceId, identity.libraryId, target.endpoint, _serverName.value)
        return connection
    }

    /** Resolve by stable identity; never transmit a persisted credential to an unchecked old IP. */
    suspend fun connectionFor(identity: ReceiverIdentity): ReceiverConnection? = connectMutex.withLock {
        if (_disconnectedByUser.value) return@withLock null
        val endpoints = linkedSetOf<String>()
        session.connection.get()?.takeIf { it.identity == identity }?.let { endpoints.add(it.baseUrl.removePrefix("http://")) }
        appPreferences.endpointForReceiver(identity.deviceId).takeIf(String::isNotBlank)?.let(endpoints::add)
        endpoints.addAll(discovery.desktops.value.map { it.endpoint })
        var actionable: TransferHttpException? = null
        for (endpoint in endpoints) {
            val target = parseConnectionTarget(endpoint) ?: continue
            try { return@withLock establish(target, expected = identity) }
            catch (e: CancellationException) { throw e }
            catch (e: TransferHttpException) { if (!e.retryable) actionable = e }
            catch (_: Exception) { }
        }
        // DHCP may have moved the receiver since the previous session. Discovery is only a hint;
        // every resulting endpoint must still pass the identity check above.
        val ownedDiscovery = !discovery.isDiscovering
        if (ownedDiscovery) discovery.start()
        try {
            delay(1500)
            for (desktop in discovery.desktops.value) {
                if (desktop.endpoint in endpoints) continue
                try { return@withLock establish(ConnectionTarget(desktop.host, desktop.port), expected = identity) }
                catch (e: CancellationException) { throw e }
                catch (e: TransferHttpException) { if (!e.retryable) actionable = e }
                catch (_: Exception) { }
            }
        } finally { if (ownedDiscovery) discovery.stop() }
        actionable?.let { markDisconnected(it.message, it.transferReason()); throw it }
        markDisconnected("Waiting for your paired computer", TransferReason.COMPUTER_UNAVAILABLE)
        null
    }

    suspend fun selectedIdentity(): ReceiverIdentity? = _receiverIdentity.value ?: run {
        val id = appPreferences.receiverId.first()
        if (id.isBlank()) null else ReceiverIdentity(id, appPreferences.libraryId.first())
    }

    suspend fun ensureConnectedToLast(): Boolean {
        val identity = selectedIdentity() ?: return false
        return try { connectionFor(identity) != null } catch (_: IOException) { false }
    }

    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive && !_disconnectedByUser.value) {
                delay(10_000)
                val connection = session.connection.get() ?: break
                val target = parseConnectionTarget(connection.baseUrl) ?: break
                try {
                    val info = health(target)
                    if (info.optString("deviceId") != connection.identity.deviceId || info.optString("libraryId") != connection.identity.libraryId) {
                        markDisconnected("The receiver or destination folder changed. Connect again to review it", TransferReason.DESTINATION_CHANGED)
                        break
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { markDisconnected(e.message, e.transferReason()); break }
            }
        }
    }
}
