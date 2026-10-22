package com.localbots.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as LocalBotsApp
        val setupViewModel = SetupViewModel(applicationContext, app.settingsRepository)
        val dashboardViewModel = DashboardViewModel(app.projectRepository)
        val chatViewModel = ChatViewModel(app.projectRepository, app.settingsRepository, app.agentOrchestrator)
        val vfsViewModel = VfsViewModel(app.projectRepository, app.vfsRepository)
        val settingsViewModel = SettingsViewModel(app.settingsRepository, app.secureKeyVault, app.cliWrapper)

        setContent {
            LocalBotsTheme {
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route ?: Screen.Dashboard.route

                val settings by app.settingsRepository.settings.collectAsState()
                val startDestination = if (settings.isSetupCompleted) Screen.Dashboard.route else Screen.Setup.route

                val showBottomNav = currentRoute in Screen.bottomNavItems.map { it.route }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        if (showBottomNav) {
                            BottomNavBar(
                                currentRoute = currentRoute,
                                onNavigate = { targetRoute ->
                                    navController.navigate(targetRoute) {
                                        popUpTo(Screen.Dashboard.route) {
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
                        startDestination = startDestination,
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable(Screen.Setup.route) {
                            SetupScreen(
                                viewModel = setupViewModel,
                                onSetupComplete = {
                                    navController.navigate(Screen.Dashboard.route) {
                                        popUpTo(Screen.Setup.route) { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable(Screen.Dashboard.route) {
                            DashboardScreen(
                                viewModel = dashboardViewModel,
                                onProjectSelected = {
                                    navController.navigate(Screen.Chat.route)
                                }
                            )
                        }

                        composable(Screen.Chat.route) {
                            ChatScreen(
                                viewModel = chatViewModel,
                                onNavigateToVfs = {
                                    navController.navigate(Screen.Vfs.route)
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
    }
}
