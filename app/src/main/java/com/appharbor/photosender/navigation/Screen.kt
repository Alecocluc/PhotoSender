package com.appharbor.photosender.navigation

sealed class Screen(val route: String) {
    data object Connect : Screen("connect")
    data object Gallery : Screen("gallery")
    data object FolderDetail : Screen("gallery/{bucketName}") {
        fun createRoute(bucketName: String) = "gallery/$bucketName"
    }
    data object Transfer : Screen("transfer")
    data object History : Screen("history")
}
