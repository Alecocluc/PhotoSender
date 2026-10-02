package com.appharbor.pherry

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.appharbor.pherry.data.preferences.ThemeMode
import com.appharbor.pherry.data.share.ShareIntakeBus
import com.appharbor.pherry.navigation.Screen
import com.appharbor.pherry.ui.activity.ActivityScreen
import com.appharbor.pherry.ui.components.ComputerChip
import com.appharbor.pherry.ui.components.Hairline
import com.appharbor.pherry.ui.components.LocalSnackbarHost
import com.appharbor.pherry.ui.components.PhIcon
import com.appharbor.pherry.ui.components.Ph
import com.appharbor.pherry.ui.components.PherrySnackbar
import com.appharbor.pherry.ui.components.PherryWordmark
import com.appharbor.pherry.ui.connect.ConnectSheet
import com.appharbor.pherry.ui.gallery.FolderDetailScreen
import com.appharbor.pherry.ui.gallery.GalleryScreen
import com.appharbor.pherry.ui.gallery.GalleryViewModel
import com.appharbor.pherry.ui.home.HomeScreen
import com.appharbor.pherry.ui.onboarding.OnboardingFlow
import com.appharbor.pherry.ui.settings.SettingsScreen
import com.appharbor.pherry.ui.share.ShareImportSheet
import com.appharbor.pherry.ui.share.ShareImportViewModel
import com.appharbor.pherry.ui.theme.PherryTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

private data class Destination(
    val route: String,
    val label: String,
    @DrawableRes val icon: Int,
    @DrawableRes val selectedIcon: Int,
)

private val Destinations = listOf(
    Destination(Screen.Home.route, "Home", Ph.House, Ph.HouseFill),
    Destination(Screen.Gallery.route, "Library", Ph.Images, Ph.ImagesFill),
    Destination(Screen.Activity.route, "Transfers", Ph.Transfers, Ph.TransfersFill),
    Destination(Screen.Settings.route, "Settings", Ph.Gear, Ph.GearFill),
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var shareIntakeBus: ShareIntakeBus

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only read the launch intent on a fresh start — a config-change recreation would otherwise
        // re-surface the same share. Warm shares come through onNewIntent.
        if (savedInstanceState == null) handleIncomingShare(intent)
        setContent {
            PherryApp(
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShare(intent)
    }

    /** Pull shared media out of an ACTION_SEND(_MULTIPLE) intent and hand it to the review sheet. */
    private fun handleIncomingShare(intent: Intent?) {
        if (intent == null) return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.let { listOf(it) }
                    .orEmpty()
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.filterNotNull()
                    .orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) shareIntakeBus.submit(uris)
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
fun PherryApp(
    onBeforeTransfer: () -> Unit = {},
    onThemeResolved: (Boolean) -> Unit = {},
) {
    val mainViewModel: MainViewModel = hiltViewModel()
    val themeMode by mainViewModel.themeMode.collectAsStateWithLifecycle()
    val dynamicColorEnabled by mainViewModel.dynamicColorEnabled.collectAsStateWithLifecycle()
    val connectionState by mainViewModel.connectionState.collectAsStateWithLifecycle()
    val serverName by mainViewModel.serverName.collectAsStateWithLifecycle()
    val rememberedComputer by mainViewModel.rememberedComputer.collectAsStateWithLifecycle()
    val onboardingCompleted by mainViewModel.onboardingCompleted.collectAsStateWithLifecycle()

    val shareViewModel: ShareImportViewModel = hiltViewModel()
    val pendingShare by shareViewModel.pendingUris.collectAsStateWithLifecycle()

    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    LaunchedEffect(darkTheme) { onThemeResolved(darkTheme) }

    PherryTheme(darkTheme = darkTheme, dynamicColor = dynamicColorEnabled) {
        when (onboardingCompleted) {
            null -> {
                // DataStore still loading: paint the ground so returning users never see onboarding flash.
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {}
                return@PherryTheme
            }
            false -> {
                OnboardingFlow(
                    onFinish = { mainViewModel.completeOnboarding() },
                )
                return@PherryTheme
            }
            else -> Unit
        }

        val snackbarHostState = remember { SnackbarHostState() }
        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = navBackStackEntry?.destination
        val topLevel = currentDestination?.route in Destinations.map { it.route }

        val openTab: (String) -> Unit = { route -> navController.openTab(route) }
        var showTransferHistory by rememberSaveable { mutableStateOf(false) }
        val openTransfers: () -> Unit = {
            showTransferHistory = false
            openTab(Screen.Activity.route)
        }
        val openHistory: () -> Unit = {
            showTransferHistory = true
            openTab(Screen.Activity.route)
        }
        LaunchedEffect(shareViewModel) { shareViewModel.sent.collect { openTransfers() } }

        val scope = rememberCoroutineScope()
        val connectSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        var showConnectSheet by remember { mutableStateOf(false) }
        // Settings' "Change computer" opens the sheet with "Pair a different computer" already open.
        var connectSheetPairOther by remember { mutableStateOf(false) }
        val openConnect: () -> Unit = {
            connectSheetPairOther = false
            showConnectSheet = true
        }
        val openChangeComputer: () -> Unit = {
            connectSheetPairOther = true
            showConnectSheet = true
        }

        CompositionLocalProvider(LocalSnackbarHost provides snackbarHostState) {
            if (showConnectSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showConnectSheet = false },
                    sheetState = connectSheetState,
                    containerColor = MaterialTheme.colorScheme.surface,
                    shape = com.appharbor.pherry.ui.theme.PherryShape.sheetTop,
                ) {
                    ConnectSheet(
                        onDismiss = {
                            scope.launch { connectSheetState.hide() }.invokeOnCompletion { showConnectSheet = false }
                        },
                        startExpanded = connectSheetPairOther,
                    )
                }
            }

            // Media shared into Pherry from another app. Hidden while the connect sheet is open so the
            // user can pair first; the request persists, so this re-appears (now connected) afterwards.
            val shareSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            if (pendingShare.isNotEmpty() && !showConnectSheet) {
                ModalBottomSheet(
                    onDismissRequest = { shareViewModel.cancel() },
                    sheetState = shareSheetState,
                    containerColor = MaterialTheme.colorScheme.surface,
                    shape = com.appharbor.pherry.ui.theme.PherryShape.sheetTop,
                ) {
                    ShareImportSheet(
                        onConnect = openConnect,
                        onBeforeTransfer = onBeforeTransfer,
                        onDismiss = { shareViewModel.cancel() },
                        viewModel = shareViewModel,
                    )
                }
            }

            Scaffold(
                containerColor = MaterialTheme.colorScheme.surface,
                // The bars carry their own vertical insets; full-screen routes (album, viewer) handle
                // theirs. Side insets (landscape 3-button nav, cutouts) pad every route here.
                contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                snackbarHost = { SnackbarHost(snackbarHostState) { PherrySnackbar(it) } },
                topBar = {
                    if (topLevel) {
                        TopAppBar(
                            title = { PherryWordmark() },
                            actions = {
                                ComputerChip(
                                    connectionState = connectionState,
                                    serverName = serverName,
                                    remembered = rememberedComputer,
                                    onClick = openConnect,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                scrolledContainerColor = MaterialTheme.colorScheme.surface,
                            ),
                        )
                    }
                },
                bottomBar = {
                    AnimatedVisibility(
                        visible = topLevel,
                        enter = slideInVertically { it } + fadeIn(),
                        exit = slideOutVertically { it } + fadeOut(),
                    ) {
                        PherryNavBar(currentDestination = currentDestination, onSelect = openTab)
                    }
                },
            ) { innerPadding ->
                // Consumed so a route's own navigationBarsPadding doesn't pad the same side twice.
                Box(Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)) {
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Home.route,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        composable(Screen.Home.route) {
                            HomeScreen(
                                onConnectClick = openConnect,
                                onOpenLibrary = { openTab(Screen.Gallery.route) },
                                onOpenTransfers = openTransfers,
                                onOpenHistory = openHistory,
                                onOpenSettings = { openTab(Screen.Settings.route) },
                                onBeforeTransfer = onBeforeTransfer,
                            )
                        }
                        composable(Screen.Gallery.route) { backStackEntry ->
                            val galleryViewModel: GalleryViewModel = hiltViewModel(backStackEntry)
                            GalleryScreen(
                                viewModel = galleryViewModel,
                                onFolderClick = { bucketName ->
                                    navController.navigate(Screen.FolderDetail.createRoute(bucketName))
                                },
                                onTransferClick = openTransfers,
                                onConnectClick = openConnect,
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
                            val galleryViewModel: GalleryViewModel = hiltViewModel(galleryEntry)
                            FolderDetailScreen(
                                bucketName = bucketName,
                                viewModel = galleryViewModel,
                                onBack = { navController.popBackStack() },
                                onBeforeTransfer = onBeforeTransfer,
                                onTransferClick = openTransfers,
                                onConnectClick = openConnect,
                                connectionState = connectionState,
                            )
                        }
                        composable(Screen.Activity.route) {
                            ActivityScreen(
                                showHistory = showTransferHistory,
                                onOpenLibrary = { openTab(Screen.Gallery.route) },
                                onConnectClick = openConnect,
                                onBeforeTransfer = onBeforeTransfer,
                            )
                        }
                        composable(Screen.Settings.route) {
                            SettingsScreen(onManageComputer = openChangeComputer)
                        }
                    }
                }
            }
        }
    }
}

private fun NavHostController.openTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Material navigation bar on paper; the selected destination sits in envelope yellow. */
@Composable
private fun PherryNavBar(
    currentDestination: NavDestination?,
    onSelect: (String) -> Unit,
) {
    val c = PherryTheme.colors
    Column {
        Hairline()
        NavigationBar(containerColor = c.paper, tonalElevation = 0.dp) {
            Destinations.forEach { item ->
                val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                NavigationBarItem(
                    selected = selected,
                    onClick = { onSelect(item.route) },
                    icon = { PhIcon(if (selected) item.selectedIcon else item.icon, contentDescription = null) },
                    label = { Text(item.label, style = MaterialTheme.typography.labelMedium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = c.onEnvelope,
                        selectedTextColor = c.ink,
                        indicatorColor = c.envelope,
                        unselectedIconColor = c.ink2,
                        unselectedTextColor = c.ink2,
                    ),
                )
            }
        }
    }
}

