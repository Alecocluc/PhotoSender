package com.appharbor.pherry.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Gallery : Screen("gallery")
    data object FolderDetail : Screen("gallery/{bucketName}") {
        // Album names carry spaces and punctuation ("WhatsApp Images"); Navigation decodes the arg.
        fun createRoute(bucketName: String) = "gallery/${Uri.encode(bucketName)}"
    }
    data object Activity : Screen("activity")
    data object Settings : Screen("settings")
}
