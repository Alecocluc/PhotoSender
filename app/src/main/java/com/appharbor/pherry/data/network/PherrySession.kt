package com.appharbor.pherry.data.network

import android.os.Build
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds per-session connection credentials that every outgoing request needs:
 *  - a friendly device name (so the desktop can show "from Pixel 7"), and
 *  - the pairing token for the currently-connected desktop (gates destructive ops on the PC).
 *
 * Read by [PherryHeaderInterceptor] and written by [ConnectionManager] as the connection changes,
 * which keeps the header plumbing out of every individual request builder.
 */
@Singleton
class PherrySession @Inject constructor() {

    /** e.g. "Google Pixel 7" — shown on the desktop's history/sources. */
    val deviceName: String = buildDeviceName()

    private val tokenRef = AtomicReference("")

    var token: String
        get() = tokenRef.get()
        set(value) = tokenRef.set(value.trim())

    private fun buildDeviceName(): String {
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        val combined = when {
            model.isEmpty() -> manufacturer
            manufacturer.isEmpty() -> model
            model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model"
        }.ifBlank { "Android device" }
        return combined.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}
