package com.localbots.app.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Google Day Palette (Before 7:50 PM / 19:50)
private val GoogleDayColorScheme = lightColorScheme(
    primary = GoogleBlue,
    onPrimary = Color.White,
    primaryContainer = DayPrimaryContainer,
    onPrimaryContainer = DayOnPrimaryContainer,
    secondary = GoogleBlueDark,
    onSecondary = Color.White,
    secondaryContainer = DaySurfaceVariant,
    onSecondaryContainer = DayOnSurfaceVariant,
    tertiary = GoogleGreen,
    onTertiary = Color.White,
    background = DayBackground,
    onBackground = DayOnBackground,
    surface = DaySurface,
    onSurface = DayOnSurface,
    surfaceVariant = DaySurfaceVariant,
    onSurfaceVariant = DayOnSurfaceVariant,
    outline = DayOutline,
    outlineVariant = DayOutlineVariant,
    error = GoogleRed,
    onError = Color.White
)

// Google Night Palette (Triggers automatically at exactly 7:50 PM / 19:50)
private val GoogleNightColorScheme = darkColorScheme(
    primary = GoogleBlueDark,
    onPrimary = Color(0xFF041E49),
    primaryContainer = NightPrimaryContainer,
    onPrimaryContainer = NightOnPrimaryContainer,
    secondary = GoogleBlue,
    onSecondary = Color.White,
    secondaryContainer = NightSurfaceVariant,
    onSecondaryContainer = NightOnSurfaceVariant,
    tertiary = GoogleGreen,
    onTertiary = Color.White,
    background = NightBackground,
    onBackground = NightOnBackground,
    surface = NightSurface,
    onSurface = NightOnSurface,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = NightOnSurfaceVariant,
    outline = NightOutline,
    outlineVariant = NightOutlineVariant,
    error = GoogleRed,
    onError = Color.White
)

@Composable
fun LocalBotsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val themeState by ThemeManager.themeState.collectAsState()
    val context = LocalContext.current

    val colorScheme: ColorScheme = when {
        themeState.useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        themeState.themeMode == ThemeMode.SCHEDULED_GOOGLE_PALETTE -> {
            if (themeState.isNightMode) GoogleNightColorScheme else GoogleDayColorScheme
        }
        themeState.themeMode == ThemeMode.FORCE_NIGHT -> GoogleNightColorScheme
        themeState.themeMode == ThemeMode.FORCE_DAY -> GoogleDayColorScheme
        else -> {
            if (darkTheme) GoogleNightColorScheme else GoogleDayColorScheme
        }
    }

    val isDark = when (themeState.themeMode) {
        ThemeMode.SCHEDULED_GOOGLE_PALETTE -> themeState.isNightMode
        ThemeMode.FORCE_NIGHT -> true
        ThemeMode.FORCE_DAY -> false
        ThemeMode.SYSTEM_DYNAMIC -> darkTheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = colorScheme.background.toArgb()
                window.navigationBarColor = colorScheme.surface.toArgb()
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !isDark
                insetsController.isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
