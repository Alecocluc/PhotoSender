package com.appharbor.pherry.ui.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** The read-media permissions Pherry needs to browse the library, split by API level. */
fun requiredMediaPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

enum class MediaAccess { NONE, SELECTED, FULL }

fun mediaAccess(context: Context): MediaAccess {
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return if (granted(Manifest.permission.READ_EXTERNAL_STORAGE)) MediaAccess.FULL else MediaAccess.NONE
    }
    val images = granted(Manifest.permission.READ_MEDIA_IMAGES)
    val videos = granted(Manifest.permission.READ_MEDIA_VIDEO)
    if (images && videos) return MediaAccess.FULL
    if (images || videos || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))) return MediaAccess.SELECTED
    return MediaAccess.NONE
}

fun hasMediaPermission(context: Context): Boolean = mediaAccess(context) != MediaAccess.NONE
fun hasFullMediaPermission(context: Context): Boolean = mediaAccess(context) == MediaAccess.FULL
