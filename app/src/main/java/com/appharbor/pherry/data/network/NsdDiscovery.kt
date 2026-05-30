package com.appharbor.pherry.data.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton

/** A Pherry desktop found on the local network via mDNS / DNS-SD. */
data class DiscoveredDesktop(
    val name: String,
    val host: String,
    val port: Int,
) {
    val endpoint: String get() = "$host:$port"
}

/**
 * Zero-config discovery of "_pherry._tcp" receivers using Android's [NsdManager]. Start it while the
 * Connect sheet is open and stop on dismiss; results stream through [desktops]. Survives the PC's IP
 * changing (DHCP) because the phone always rediscovers the current address.
 */
@Singleton
class NsdDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val nsdManager: NsdManager? =
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private val _desktops = MutableStateFlow<List<DiscoveredDesktop>>(emptyList())
    val desktops: StateFlow<List<DiscoveredDesktop>> = _desktops.asStateFlow()

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // resolveService can only run one resolve at a time on older APIs, so serialize them.
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val lock = Any()

    @Synchronized
    fun start() {
        val manager = nsdManager ?: return
        if (discoveryListener != null) return
        acquireMulticastLock()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                runCatching { manager.stopServiceDiscovery(this) }
            }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                enqueueResolve(serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName ?: return
                _desktops.update { list -> list.filterNot { it.name == name } }
            }
        }
        discoveryListener = listener
        runCatching {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { discoveryListener = null }
    }

    @Synchronized
    fun stop() {
        val manager = nsdManager
        discoveryListener?.let { listener ->
            runCatching { manager?.stopServiceDiscovery(listener) }
        }
        discoveryListener = null
        synchronized(lock) {
            resolveQueue.clear()
            resolving = false
        }
        _desktops.value = emptyList()
        releaseMulticastLock()
    }

    private fun enqueueResolve(serviceInfo: NsdServiceInfo) {
        synchronized(lock) {
            resolveQueue.addLast(serviceInfo)
            if (!resolving) resolveNext()
        }
    }

    private fun resolveNext() {
        val manager = nsdManager ?: return
        synchronized(lock) {
            val next = resolveQueue.pollFirst()
            if (next == null) {
                resolving = false
                return
            }
            resolving = true
            @Suppress("DEPRECATION")
            manager.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    onResolveDone()
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.hostAddressString()
                    val port = serviceInfo.port
                    if (host != null && port in 1..65535) {
                        val name = serviceInfo.serviceName?.removePrefix("Pherry on ")?.trim()
                            ?.ifBlank { serviceInfo.serviceName } ?: "Desktop"
                        val desktop = DiscoveredDesktop(name = name, host = host, port = port)
                        _desktops.update { list ->
                            (list.filterNot { it.endpoint == desktop.endpoint } + desktop)
                                .sortedBy { it.name.lowercase() }
                        }
                    }
                    onResolveDone()
                }
            })
        }
    }

    private fun onResolveDone() {
        synchronized(lock) { resolveNext() }
    }

    private fun acquireMulticastLock() {
        runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("pherry-nsd")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseMulticastLock() {
        runCatching { if (multicastLock?.isHeld == true) multicastLock?.release() }
        multicastLock = null
    }

    @Suppress("DEPRECATION")
    private fun NsdServiceInfo.hostAddressString(): String? {
        // hostAddresses (API 34+) is preferred; fall back to the deprecated host on older devices.
        if (Build.VERSION.SDK_INT >= 34) {
            val addr = hostAddresses.firstOrNull { it.hostAddress?.contains('.') == true }
                ?: hostAddresses.firstOrNull()
            return addr?.hostAddress
        }
        return host?.hostAddress
    }

    private companion object {
        const val SERVICE_TYPE = "_pherry._tcp."
    }
}
