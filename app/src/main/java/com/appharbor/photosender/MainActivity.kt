package com.appharbor.photosender

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SwapHorizontalCircle
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.SwapHorizontalCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.appharbor.photosender.navigation.Screen
import com.appharbor.photosender.ui.connect.ConnectScreen
import com.appharbor.photosender.ui.gallery.FolderDetailScreen
import com.appharbor.photosender.ui.gallery.GalleryScreen
import com.appharbor.photosender.ui.history.HistoryScreen
import com.appharbor.photosender.ui.theme.Manrope
import com.appharbor.photosender.ui.theme.PhotoSenderTheme
import com.appharbor.photosender.ui.settings.SettingsScreen
import com.appharbor.photosender.ui.transfer.TransferScreen
import com.appharbor.photosender.data.preferences.ThemeMode
import dagger.hilt.android.AndroidEntryPoint

data class BottomNavItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhotoSenderApp()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoSenderApp() {
    val mainViewModel: MainViewModel = hiltViewModel()
    val themeMode by mainViewModel.themeMode.collectAsStateWithLifecycle()
    val dynamicColorEnabled by mainViewModel.dynamicColorEnabled.collectAsStateWithLifecycle()
    val isSystemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    PhotoSenderTheme(
        darkTheme = darkTheme,
        dynamicColor = dynamicColorEnabled,
    ) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val bottomNavItems = remember {
        listOf(
            BottomNavItem(Screen.Connect.route, "Connect", Icons.Filled.Sensors, Icons.Outlined.Sensors),
            BottomNavItem(Screen.Gallery.route, "Gallery", Icons.Filled.PhotoLibrary, Icons.Outlined.PhotoLibrary),
            BottomNavItem(Screen.Transfer.route, "Transfer", Icons.Filled.SwapHorizontalCircle, Icons.Outlined.SwapHorizontalCircle),
            BottomNavItem(Screen.History.route, "History", Icons.Filled.History, Icons.Outlined.History),
            BottomNavItem(Screen.Settings.route, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
        )
    }

    val showBottomBar = currentDestination?.route in bottomNavItems.map { it.route }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Sensors,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "PhotoSender",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = Manrope,
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                StitchBottomNav(
                    items = bottomNavItems,
                    currentDestination = currentDestination,
                    onItemSelected = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Connect.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable(Screen.Connect.route) {
                ConnectScreen()
            }
            composable(Screen.Gallery.route) {
                GalleryScreen(
                    onFolderClick = { bucketName ->
                        navController.navigate(Screen.FolderDetail.createRoute(bucketName))
                    },
                    onTransferClick = {
                        navController.navigate(Screen.Transfer.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
            composable(
                route = Screen.FolderDetail.route,
                arguments = listOf(navArgument("bucketName") { type = NavType.StringType })
            ) { backStackEntry ->
                val bucketName = backStackEntry.arguments?.getString("bucketName") ?: ""
                FolderDetailScreen(
                    bucketName = bucketName,
                    onBack = { navController.popBackStack() },
                    onTransferClick = {
                        navController.navigate(Screen.Transfer.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
            composable(Screen.Transfer.route) {
                TransferScreen()
            }
            composable(Screen.History.route) {
                HistoryScreen()
            }
            composable(Screen.Settings.route) {
                SettingsScreen()
            }
        }
    }
    }
}

@Composable
private fun StitchBottomNav(
    items: List<BottomNavItem>,
    currentDestination: androidx.navigation.NavDestination?,
    onItemSelected: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.86f),
            tonalElevation = 0.dp,
            shadowElevation = 14.dp,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEach { item ->
                    val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                    val bg = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    } else {
                        Color.Transparent
                    }
                    val fg = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(bg)
                            .clickable { onItemSelected(item.route) }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                            contentDescription = item.label,
                            tint = fg,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = fg,
                        )
                    }
                }
            }
        }
    }
}