package com.localbots.app.ui.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localbots.app.data.model.ChatMessage
import com.localbots.app.data.model.Project
import com.localbots.app.data.repository.ProjectRepository
import com.localbots.app.data.repository.SettingsRepository
import com.localbots.app.domain.agent.AgentOrchestrator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val attachedImages: List<Uri> = emptyList(),
    val isAgentBusy: Boolean = false,
    val agentStatusText: String = "",
    val activeProject: Project? = null,
    val selectedModel: String = "gemini-2.0-flash"
)

class ChatViewModel(
    private val projectRepository: ProjectRepository,
    private val settingsRepository: SettingsRepository,
    private val orchestrator: AgentOrchestrator
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            projectRepository.activeProject.collect { proj ->
                _uiState.value = _uiState.value.copy(activeProject = proj)
                if (proj != null) {
                    loadMessages(proj.id)
                }
            }
        }
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.value = _uiState.value.copy(selectedModel = settings.selectedModel)
            }
        }
    }

    private fun loadMessages(projectId: String) {
        val msgs = projectRepository.getMessagesForProject(projectId)
        _uiState.value = _uiState.value.copy(messages = msgs)
    }

    fun onInputTextChanged(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    fun addAttachedImages(uris: List<Uri>) {
        val current = _uiState.value.attachedImages.toMutableList()
        current.addAll(uris)
        _uiState.value = _uiState.value.copy(attachedImages = current)
    }

    fun removeAttachment(uri: Uri) {
        val current = _uiState.value.attachedImages.toMutableList()
        current.remove(uri)
        _uiState.value = _uiState.value.copy(attachedImages = current)
    }

    fun sendMessage() {
        val project = _uiState.value.activeProject ?: return
        val text = _uiState.value.inputText.trim()
        val images = _uiState.value.attachedImages.toList()

        if (text.isBlank() && images.isEmpty()) return

        // Clear input bar
        _uiState.value = _uiState.value.copy(
            inputText = "",
            attachedImages = emptyList(),
            isAgentBusy = true,
            agentStatusText = "Connecting with ${settingsRepository.settings.value.selectedModel}..."
        )

        viewModelScope.launch {
            try {
                orchestrator.processUserPrompt(
                    project = project,
                    userPrompt = text,
                    attachedImages = images.map { it.toString() }
                ) { status ->
                    _uiState.value = _uiState.value.copy(agentStatusText = status)
                }
            } catch (e: Exception) {
                // error handled
            } finally {
                loadMessages(project.id)
                _uiState.value = _uiState.value.copy(
                    isAgentBusy = false,
                    agentStatusText = ""
                )
            }
        }
    }
}
