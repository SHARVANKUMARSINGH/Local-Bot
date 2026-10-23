package com.localbots.app.data.repository

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AppSettings(
    val isSetupCompleted: Boolean = false,
    val isCliInstalled: Boolean = false,
    val cliVersion: String = "v1.4.2",
    val isLoggedInGoogle: Boolean = false,
    val googleUserEmail: String? = null,
    val isCliAuthenticated: Boolean = false,
    val cliAuthToken: String? = null,
    val selectedModel: String = "Gemini 3.8 Flash",
    val availableModels: List<String> = listOf(
        "Gemini 3.8 Flash",
        "Gemini 3.8 Pro",
        "Gemini 3.5 Flash",
        "Gemini 3.5 Pro",
        "Gemini 2.5 Flash",
        "Gemini 2.0 Flash"
    ),
    val lastUpdateCheck: Long = 0L
)

class SettingsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val prefs = context.getSharedPreferences("localbots_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun loadSettings(): AppSettings {
        val str = prefs.getString("settings_data", null)
        if (str != null) {
            try {
                return json.decodeFromString<AppSettings>(str)
            } catch (ignored: Exception) {}
        }
        return AppSettings()
    }

    private fun saveSettings(appSettings: AppSettings) {
        _settings.value = appSettings
        try {
            prefs.edit().putString("settings_data", json.encodeToString(appSettings)).apply()
        } catch (e: Exception) {
            android.util.Log.e("SettingsRepository", "Failed to save settings to SharedPreferences", e)
        }
    }

    fun setSetupCompleted(completed: Boolean) {
        saveSettings(_settings.value.copy(isSetupCompleted = completed))
    }

    fun setCliInstalled(installed: Boolean, version: String = "v1.4.2") {
        saveSettings(
            _settings.value.copy(
                isCliInstalled = installed,
                cliVersion = version,
                lastUpdateCheck = System.currentTimeMillis()
            )
        )
    }

    fun setGoogleAuth(isLoggedIn: Boolean, email: String?) {
        setCliAuth(isLoggedIn, email, null)
    }

    fun setCliAuth(authenticated: Boolean, email: String?, token: String? = null) {
        saveSettings(
            _settings.value.copy(
                isLoggedInGoogle = authenticated,
                googleUserEmail = email,
                isCliAuthenticated = authenticated,
                cliAuthToken = token
            )
        )
    }

    fun setSelectedModel(model: String) {
        saveSettings(_settings.value.copy(selectedModel = model))
    }

    fun updateAvailableModels(models: List<String>) {
        val merged = (listOf("Gemini 3.8 Flash") + models).distinct()
        saveSettings(_settings.value.copy(availableModels = merged))
    }
}
