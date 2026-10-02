package com.appharbor.pherry.ui.permissions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Android 17 (API 37) made reaching other devices on the Wi-Fi a runtime permission in the "Nearby
 * devices" group. Without it Pherry can't talk to Pherry Desktop at all, so pairing and the Home
 * envelope ask for it first. Older Android versions grant local network access implicitly.
 */
const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

private const val LOCAL_NETWORK_API = 37

fun localNetworkPermissionRequired(): Boolean = Build.VERSION.SDK_INT >= LOCAL_NETWORK_API

fun hasLocalNetworkPermission(context: Context): Boolean =
    !localNetworkPermissionRequired() ||
        ContextCompat.checkSelfPermission(context, ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

/** Live local-network permission state plus the two ways to fix it. */
@Stable
class LocalNetworkAccess internal constructor(
    granted: Boolean,
    blocked: Boolean,
    private val onRequest: () -> Unit,
    private val onOpenSettings: () -> Unit,
) {
    var granted by mutableStateOf(granted)
        internal set

    /** Denied with "don't ask again": only the app's system settings can grant it now. */
    var blocked by mutableStateOf(blocked)
        internal set

    fun request() = onRequest()
    fun openSettings() = onOpenSettings()
}

/**
 * Tracks the local-network permission across resumes (the user may grant it in system settings) and
 * calls [onGranted] the moment it becomes available, so callers can reconnect right away.
 */
@Composable
fun rememberLocalNetworkAccess(onGranted: () -> Unit = {}): LocalNetworkAccess {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var blockedSaved by rememberSaveable { mutableStateOf(false) }
    lateinit var access: LocalNetworkAccess
    val ask = rememberPermissionAsk(ACCESS_LOCAL_NETWORK)

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        access.granted = ok
        // Only a real "don't ask again" swaps Allow for app settings; a dismissed dialog asks again.
        blockedSaved = !ok && ask.blockedAfterDenial()
        access.blocked = blockedSaved
        if (ok) onGranted()
    }

    access = remember(context) {
        LocalNetworkAccess(
            granted = hasLocalNetworkPermission(context),
            blocked = blockedSaved,
            onRequest = {
                if (localNetworkPermissionRequired()) {
                    ask.beforeLaunch()
                    launcher.launch(ACCESS_LOCAL_NETWORK)
                }
            },
            onOpenSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
        )
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = hasLocalNetworkPermission(context)
                val newlyGranted = now && !access.granted
                access.granted = now
                if (now) {
                    access.blocked = false
                    blockedSaved = false
                }
                if (newlyGranted) onGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return access
}
