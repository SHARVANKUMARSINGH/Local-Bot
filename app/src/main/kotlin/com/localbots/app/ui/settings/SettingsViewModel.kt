package com.localbots.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localbots.app.data.model.ApiKeyConfig
import com.localbots.app.data.repository.AppSettings
import com.localbots.app.data.repository.SettingsRepository
import com.localbots.app.data.vault.SecureKeyVault
import com.localbots.app.domain.agent.AntigravityCliWrapper
import com.localbots.app.theme.ThemeManager
import com.localbots.app.theme.ThemeMode
import com.localbots.app.theme.ThemeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    val appSettings: AppSettings = AppSettings(),
    val keySlots: List<ApiKeyConfig> = emptyList(),
    val isUpdatingCli: Boolean = false,
    val cliStatusMessage: String = "",
    val ottDialogVisible: Boolean = false,
    val ottInput: String = "",
    val ottLoading: Boolean = false,
    val ottError: String? = null,
    val themeState: ThemeState = ThemeState()
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val secureKeyVault: SecureKeyVault,
    private val cliWrapper: AntigravityCliWrapper
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.value = _uiState.value.copy(appSettings = settings)
            }
        }
        viewModelScope.launch {
            secureKeyVault.slotsState.collect { slots ->
                _uiState.value = _uiState.value.copy(keySlots = slots)
            }
        }
        viewModelScope.launch {
            ThemeManager.themeState.collect { ts ->
                _uiState.value = _uiState.value.copy(themeState = ts)
            }
        }
    }

    fun checkForCliUpdates() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isUpdatingCli = true, cliStatusMessage = "Checking repository...")
            val success = cliWrapper.installOrUpdateCli { progress, status ->
                _uiState.value = _uiState.value.copy(cliStatusMessage = status)
            }
            if (success) {
                cliWrapper.syncGeminiModels()
            }
            _uiState.value = _uiState.value.copy(isUpdatingCli = false)
        }
    }

    fun showOttDialog() {
        _uiState.value = _uiState.value.copy(ottDialogVisible = true, ottInput = "", ottError = null)
    }

    fun dismissOttDialog() {
        _uiState.value = _uiState.value.copy(ottDialogVisible = false)
    }

    fun onOttInputChanged(input: String) {
        _uiState.value = _uiState.value.copy(ottInput = input, ottError = null)
    }

    fun submitOttSignIn() {
        val token = _uiState.value.ottInput.trim()
        if (token.isBlank()) {
            _uiState.value = _uiState.value.copy(ottError = "Please enter a valid One-Time Token.")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(ottLoading = true, ottError = null)
            val result = cliWrapper.authenticateWithOtt(token)
            if (result.isSuccess) {
                cliWrapper.syncGeminiModels()
                _uiState.value = _uiState.value.copy(ottLoading = false, ottDialogVisible = false)
            } else {
                _uiState.value = _uiState.value.copy(
                    ottLoading = false,
                    ottError = result.exceptionOrNull()?.message ?: "Authentication failed."
                )
            }
        }
    }

    fun selectModel(model: String) {
        settingsRepository.setSelectedModel(model)
    }

    fun toggleSlot(slotNumber: Int, enabled: Boolean) {
        secureKeyVault.toggleSlotEnabled(slotNumber, enabled)
    }

    fun saveSlot(slot: ApiKeyConfig) {
        secureKeyVault.updateSlot(slot)
    }

    fun setThemeMode(mode: ThemeMode) {
        ThemeManager.setThemeMode(mode)
    }
}
