package com.appharbor.photosender.navigation

sealed class Screen(val route: String) {
    data object Gallery : Screen("gallery")
    data object FolderDetail : Screen("gallery/{bucketName}") {
        fun createRoute(bucketName: String) = "gallery/$bucketName"
    }
    data object Activity : Screen("activity")
    data object Settings : Screen("settings")
}
