package com.appharbor.pherry.ui.permissions

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat

/**
 * Tells a "don't ask again" denial apart from a dismissed dialog.
 *
 * Android reports both the same way: denied, with no rationale to show. Dismissing the very first
 * dialog (Back, or a tap outside) is not a denial, and the system dialog will still appear next
 * time, so treating it as blocked would wrongly swap "Allow" for "Open app settings".
 *
 * Call [beforeLaunch] right before `launcher.launch(...)`, and [blockedAfterDenial] in the result
 * callback when the permission was not granted. A denial counts as blocked only when the rationale
 * was showing before and is gone now (a second, explicit denial), the permission was already asked
 * for before, or the result came back too fast for any dialog to have been shown.
 */
class PermissionAsk internal constructor(
    private val context: Context,
    private val permissions: List<String>,
) {
    private var rationaleBefore = false
    private var launchedAt = 0L

    fun beforeLaunch() {
        rationaleBefore = context.findActivity()?.let { anyRationale(it) } ?: false
        launchedAt = SystemClock.elapsedRealtime()
    }

    fun blockedAfterDenial(): Boolean {
        val activity = context.findActivity() ?: return false
        val askedBefore = prefs().getBoolean(key(), false)
        prefs().edit().putBoolean(key(), true).apply()
        if (anyRationale(activity)) return false
        val noDialog = launchedAt > 0 && SystemClock.elapsedRealtime() - launchedAt < NO_DIALOG_MS
        return rationaleBefore || askedBefore || noDialog
    }

    private fun anyRationale(activity: Activity): Boolean =
        permissions.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key() = permissions.sorted().joinToString(",")

    private companion object {
        const val PREFS = "pherry_permission_asks"
        // A denial this quick means Android answered without showing a dialog.
        const val NO_DIALOG_MS = 250L
    }
}

@Composable
fun rememberPermissionAsk(vararg permissions: String): PermissionAsk {
    val context = LocalContext.current
    val list = permissions.toList()
    return remember(context, list) { PermissionAsk(context, list) }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
