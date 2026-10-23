package com.localbots.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    data object Setup : Screen("setup", "Setup", Icons.Rounded.Settings)
    data object Dashboard : Screen("dashboard", "Projects", Icons.Rounded.Dashboard)
    data object Chat : Screen("chat", "Chat", Icons.Rounded.Chat)
    data object Vfs : Screen("vfs", "VFS", Icons.Rounded.FolderOpen)
    data object Settings : Screen("settings", "Settings", Icons.Rounded.Settings)

    companion object {
        val bottomNavRoutes: Set<String> = setOf("dashboard", "chat", "vfs", "settings")

        fun getBottomNavItems(): List<Screen> {
            return listOf(Dashboard, Chat, Vfs, Settings)
        }
    }
}
