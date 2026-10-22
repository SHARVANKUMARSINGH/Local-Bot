package com.localbots.app.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar

enum class ThemeMode {
    SCHEDULED_GOOGLE_PALETTE, // Day before 19:50, Night at 19:50+
    SYSTEM_DYNAMIC,           // Android 12+ Material You Dynamic Color
    FORCE_DAY,
    FORCE_NIGHT
}

data class ThemeState(
    val themeMode: ThemeMode = ThemeMode.SCHEDULED_GOOGLE_PALETTE,
    val isNightMode: Boolean = false,
    val useDynamicColor: Boolean = false,
    val currentFormattedTime: String = ""
)

object ThemeManager {
    private const val PREFS_NAME = "localbots_theme_prefs"
    private const val KEY_THEME_MODE = "key_theme_mode"
    
    // Scheduled trigger: 7:50 PM = 19 hours, 50 minutes
    const val NIGHT_TRIGGER_HOUR = 19
    const val NIGHT_TRIGGER_MINUTE = 50
    const val DAY_TRIGGER_HOUR = 6
    const val DAY_TRIGGER_MINUTE = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _themeState = MutableStateFlow(ThemeState())
    val themeState: StateFlow<ThemeState> = _themeState.asStateFlow()

    private var sharedPreferences: SharedPreferences? = null

    fun initialize(context: Context) {
        sharedPreferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedModeOrdinal = sharedPreferences?.getInt(KEY_THEME_MODE, ThemeMode.SCHEDULED_GOOGLE_PALETTE.ordinal)
            ?: ThemeMode.SCHEDULED_GOOGLE_PALETTE.ordinal
        val savedMode = ThemeMode.values().getOrElse(savedModeOrdinal) { ThemeMode.SCHEDULED_GOOGLE_PALETTE }

        updateThemeState(savedMode)
        startLocalTimeTicker()
    }

    fun setThemeMode(mode: ThemeMode) {
        sharedPreferences?.edit()?.putInt(KEY_THEME_MODE, mode.ordinal)?.apply()
        updateThemeState(mode)
    }

    fun onTimeTick() {
        val currentMode = _themeState.value.themeMode
        if (currentMode == ThemeMode.SCHEDULED_GOOGLE_PALETTE) {
            updateThemeState(currentMode)
        }
    }

    private fun startLocalTimeTicker() {
        scope.launch {
            while (true) {
                val currentMode = _themeState.value.themeMode
                if (currentMode == ThemeMode.SCHEDULED_GOOGLE_PALETTE) {
                    updateThemeState(currentMode)
                }
                delay(30_000) // Recheck every 30 seconds
            }
        }
    }

    private fun updateThemeState(mode: ThemeMode) {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val formattedTime = String.format("%02d:%02d", hour, minute)

        val isScheduledNight = when {
            hour > NIGHT_TRIGGER_HOUR -> true
            hour == NIGHT_TRIGGER_HOUR && minute >= NIGHT_TRIGGER_MINUTE -> true
            hour < DAY_TRIGGER_HOUR -> true
            else -> false
        }

        val (isNight, useDynamic) = when (mode) {
            ThemeMode.SCHEDULED_GOOGLE_PALETTE -> Pair(isScheduledNight, false)
            ThemeMode.SYSTEM_DYNAMIC -> Pair(false, true) // delegates to system dynamic
            ThemeMode.FORCE_DAY -> Pair(false, false)
            ThemeMode.FORCE_NIGHT -> Pair(true, false)
        }

        _themeState.value = ThemeState(
            themeMode = mode,
            isNightMode = isNight,
            useDynamicColor = useDynamic,
            currentFormattedTime = formattedTime
        )
    }
}
