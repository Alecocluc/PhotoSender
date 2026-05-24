package com.appharbor.photosender

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHorizontalCircle
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHorizontalCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
import com.appharbor.photosender.data.model.ConnectionState
import com.appharbor.photosender.data.preferences.ThemeMode
import com.appharbor.photosender.navigation.Screen
import com.appharbor.photosender.ui.activity.ActivityScreen
import com.appharbor.photosender.ui.components.ConnectionStatusChip
import com.appharbor.photosender.ui.connect.ConnectSheet
import com.appharbor.photosender.ui.gallery.FolderDetailScreen
import com.appharbor.photosender.ui.gallery.GalleryScreen
import com.appharbor.photosender.ui.settings.SettingsScreen
import com.appharbor.photosender.ui.theme.LocalExtendedColors
import com.appharbor.photosender.ui.theme.Manrope
import com.appharbor.photosender.ui.theme.PhotoSenderTheme
import com.appharbor.photosender.ui.theme.Spacing
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

data class BottomNavItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhotoSenderApp(
                onBeforeTransfer = ::maybeRequestNotificationPermission,
                onThemeResolved = { isDark ->
                    val style = if (isDark) {
                        SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                    }
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
            )
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoSenderApp(
    onBeforeTransfer: () -> Unit = {},
    onThemeResolved: (Boolean) -> Unit = {},
) {
    val mainViewModel: MainViewModel = hiltViewModel()
    val themeMode by mainViewModel.themeMode.collectAsStateWithLifecycle()
    val dynamicColorEnabled by mainViewModel.dynamicColorEnabled.collectAsStateWithLifecycle()
    val connectionState by mainViewModel.connectionState.collectAsStateWithLifecycle()
    val serverName by mainViewModel.serverName.collectAsStateWithLifecycle()

    val isSystemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    LaunchedEffect(darkTheme) { onThemeResolved(darkTheme) }

    PhotoSenderTheme(darkTheme = darkTheme, dynamicColor = dynamicColorEnabled) {
        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = navBackStackEntry?.destination

        val bottomNavItems = remember {
            listOf(
                BottomNavItem(Screen.Gallery.route, "Gallery", Icons.Filled.PhotoLibrary, Icons.Outlined.PhotoLibrary),
                BottomNavItem(Screen.Activity.route, "Activity", Icons.Filled.SwapHorizontalCircle, Icons.Outlined.SwapHorizontalCircle),
                BottomNavItem(Screen.Settings.route, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings),
            )
        }

        val showBottomBar = currentDestination?.route in bottomNavItems.map { it.route }
        val showTopBar = currentDestination?.route != Screen.FolderDetail.route

        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        var showConnectSheet by remember { mutableStateOf(false) }

        if (showConnectSheet) {
            ModalBottomSheet(
                onDismissRequest = { showConnectSheet = false },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                ConnectSheet(
                    onDismiss = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            showConnectSheet = false
                        }
                    }
                )
            }
        }

        val extendedColors = LocalExtendedColors.current
        val gradientBrush = extendedColors.buttonGradient

        Scaffold(
            topBar = {
                if (!showTopBar) return@Scaffold
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.PhotoLibrary,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(22.dp)
                                    .graphicsLayer(alpha = 0.99f)
                                    .drawWithContent {
                                        drawContent()
                                        drawRect(brush = gradientBrush, blendMode = BlendMode.SrcAtop)
                                    }
                            )
                            Spacer(Modifier.width(Spacing.sm))
                            Text(
                                text = "PhotoSender",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontFamily = Manrope,
                                    fontWeight = FontWeight.Bold,
                                    brush = gradientBrush,
                                ),
                            )
                        }
                    },
                    actions = {
                        ConnectionStatusChip(
                            connectionState = connectionState,
                            serverName = serverName,
                            onClick = { showConnectSheet = true },
                            modifier = Modifier.padding(end = Spacing.sm),
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                    windowInsets = TopAppBarDefaults.windowInsets,
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
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
            NavHost(
                navController = navController,
                startDestination = Screen.Gallery.route,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(Screen.Gallery.route) { backStackEntry ->
                    val galleryViewModel: com.appharbor.photosender.ui.gallery.GalleryViewModel =
                        hiltViewModel(backStackEntry)
                    GalleryScreen(
                        viewModel = galleryViewModel,
                        onFolderClick = { bucketName ->
                            navController.navigate(Screen.FolderDetail.createRoute(bucketName))
                        },
                        onTransferClick = {
                            navController.navigate(Screen.Activity.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onConnectClick = { showConnectSheet = true },
                        onBeforeTransfer = onBeforeTransfer,
                        connectionState = connectionState,
                    )
                }
                composable(
                    route = Screen.FolderDetail.route,
                    arguments = listOf(navArgument("bucketName") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val bucketName = backStackEntry.arguments?.getString("bucketName") ?: ""
                    val galleryEntry = remember(backStackEntry) {
                        navController.getBackStackEntry(Screen.Gallery.route)
                    }
                    val galleryViewModel: com.appharbor.photosender.ui.gallery.GalleryViewModel =
                        hiltViewModel(galleryEntry)
                    FolderDetailScreen(
                        bucketName = bucketName,
                        viewModel = galleryViewModel,
                        onBack = { navController.popBackStack() },
                        onBeforeTransfer = onBeforeTransfer,
                        onTransferClick = {
                            navController.navigate(Screen.Activity.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
                composable(Screen.Activity.route) {
                    ActivityScreen()
                }
                composable(Screen.Settings.route) {
                    SettingsScreen()
                }
            }
            } // Box
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
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.90f),
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.sm, vertical = Spacing.sm - 2.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val navGradient = LocalExtendedColors.current.buttonGradient
                items.forEach { item ->
                    val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                    val bg = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.14f)
                    } else Color.Transparent

                    val gradientMod = if (selected) {
                        Modifier
                            .graphicsLayer(alpha = 0.99f)
                            .drawWithContent {
                                drawContent()
                                drawRect(brush = navGradient, blendMode = BlendMode.SrcAtop)
                            }
                    } else Modifier

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(bg)
                            .selectable(
                                selected = selected,
                                role = Role.Tab,
                                onClick = { onItemSelected(item.route) },
                            )
                            .padding(vertical = Spacing.sm)
                            .then(gradientMod),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                            contentDescription = item.label,
                            tint = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
