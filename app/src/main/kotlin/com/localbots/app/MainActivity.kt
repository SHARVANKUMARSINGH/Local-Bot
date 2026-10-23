package com.localbots.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.localbots.app.theme.LocalBotsTheme
import com.localbots.app.ui.chat.ChatScreen
import com.localbots.app.ui.chat.ChatViewModel
import com.localbots.app.ui.dashboard.DashboardScreen
import com.localbots.app.ui.dashboard.DashboardViewModel
import com.localbots.app.ui.navigation.BottomNavBar
import com.localbots.app.ui.navigation.Screen
import com.localbots.app.ui.settings.SettingsScreen
import com.localbots.app.ui.settings.SettingsViewModel
import com.localbots.app.ui.setup.SetupScreen
import com.localbots.app.ui.setup.SetupViewModel
import com.localbots.app.ui.vfs.VfsExplorerScreen
import com.localbots.app.ui.vfs.VfsViewModel

class MainActivity : ComponentActivity() {

    private val app: LocalBotsApp
        get() = application as LocalBotsApp

    private val setupViewModel: SetupViewModel by lazy {
        SetupViewModel(applicationContext, app.settingsRepository)
    }

    private val dashboardViewModel: DashboardViewModel by lazy {
        DashboardViewModel(app.projectRepository)
    }

    private val chatViewModel: ChatViewModel by lazy {
        ChatViewModel(app.projectRepository, app.settingsRepository, app.agentOrchestrator)
    }

    private val vfsViewModel: VfsViewModel by lazy {
        VfsViewModel(app.projectRepository, app.vfsRepository)
    }

    private val settingsViewModel: SettingsViewModel by lazy {
        SettingsViewModel(app.settingsRepository, app.secureKeyVault, app.cliWrapper)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setContent {
                LocalBotsTheme {
                    val navController = rememberNavController()
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = navBackStackEntry?.destination?.route ?: Screen.Dashboard.route

                    // Stable start destination computed once on mount to prevent navigation graph crashes
                    val startDestination = remember {
                        if (app.settingsRepository.settings.value.isSetupCompleted) {
                            Screen.Dashboard.route
                        } else {
                            Screen.Setup.route
                        }
                    }

                    val showBottomNav = currentRoute in Screen.bottomNavItems.map { it.route }

                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        bottomBar = {
                            if (showBottomNav) {
                                BottomNavBar(
                                    currentRoute = currentRoute,
                                    onNavigate = { targetRoute ->
                                        try {
                                            navController.navigate(targetRoute) {
                                                popUpTo(Screen.Dashboard.route) {
                                                    saveState = true
                                                }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        } catch (e: Exception) {
                                            Log.e("MainActivity", "Navigation failure to $targetRoute", e)
                                        }
                                    }
                                )
                            }
                        }
                    ) { innerPadding ->
                        var hasNavigatedAwayFromSetup by remember { mutableStateOf(false) }

                        NavHost(
                            navController = navController,
                            startDestination = startDestination,
                            modifier = Modifier.padding(innerPadding)
                        ) {
                            composable(Screen.Setup.route) {
                                SetupScreen(
                                    viewModel = setupViewModel,
                                    onSetupComplete = {
                                        if (!hasNavigatedAwayFromSetup) {
                                            hasNavigatedAwayFromSetup = true
                                            try {
                                                navController.navigate(Screen.Dashboard.route) {
                                                    popUpTo(Screen.Setup.route) { inclusive = true }
                                                    launchSingleTop = true
                                                }
                                            } catch (t: Throwable) {
                                                Log.e("MainActivity", "Setup navigation error", t)
                                                try {
                                                    navController.navigate(Screen.Dashboard.route)
                                                } catch (ignored: Throwable) {}
                                            }
                                        }
                                    }
                                )
                            }

                            composable(Screen.Dashboard.route) {
                                DashboardScreen(
                                    viewModel = dashboardViewModel,
                                    onProjectSelected = {
                                        try {
                                            navController.navigate(Screen.Chat.route)
                                        } catch (e: Exception) {
                                            Log.e("MainActivity", "Chat navigation error", e)
                                        }
                                    }
                                )
                            }

                            composable(Screen.Chat.route) {
                                ChatScreen(
                                    viewModel = chatViewModel,
                                    onNavigateToVfs = {
                                        try {
                                            navController.navigate(Screen.Vfs.route)
                                        } catch (e: Exception) {
                                            Log.e("MainActivity", "VFS navigation error", e)
                                        }
                                    }
                                )
                            }

                            composable(Screen.Vfs.route) {
                                VfsExplorerScreen(
                                    viewModel = vfsViewModel
                                )
                            }

                            composable(Screen.Settings.route) {
                                SettingsScreen(
                                    viewModel = settingsViewModel
                                )
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e("MainActivity", "Fatal error during setContent", t)
        }
    }
}
