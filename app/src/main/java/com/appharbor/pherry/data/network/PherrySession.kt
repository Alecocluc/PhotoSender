package com.appharbor.pherry.data.network

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

data class ReceiverIdentity(val deviceId: String, val libraryId: String)
data class ReceiverConnection(val identity: ReceiverIdentity, val baseUrl: String, val credential: String)

/** An atomic snapshot prevents credentials being sent to another receiver during reconnect. */
@Singleton
class PherrySession @Inject constructor() {
    val connection = AtomicReference<ReceiverConnection?>(null)
    @Volatile var clientId: String = ""
    @Volatile var deviceName: String = "Android phone"
}
