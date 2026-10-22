package com.localbots.app.data.repository

import android.content.Context
import com.localbots.app.data.model.ChatMessage
import com.localbots.app.data.model.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class ProjectRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val prefs = context.getSharedPreferences("localbots_projects", Context.MODE_PRIVATE)

    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    private val _activeProject = MutableStateFlow<Project?>(null)
    val activeProject: StateFlow<Project?> = _activeProject.asStateFlow()

    private val _chatMessages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val chatMessages: StateFlow<Map<String, List<ChatMessage>>> = _chatMessages.asStateFlow()

    init {
        loadProjects()
    }

    fun loadProjects() {
        val list = mutableListOf<Project>()
        val projectIds = prefs.getStringSet("project_ids", emptySet()) ?: emptySet()
        for (id in projectIds) {
            val pJson = prefs.getString("proj_$id", null) ?: continue
            try {
                list.add(json.decodeFromString<Project>(pJson))
            } catch (ignored: Exception) {}
        }

        val sorted = list.sortedByDescending { it.lastActiveAt }
        _projects.value = sorted
        if (_activeProject.value == null && sorted.isNotEmpty()) {
            _activeProject.value = sorted.first()
        }
    }

    fun createProject(
        name: String,
        description: String = "",
        avatarUri: String? = null,
        referencePictures: List<String> = emptyList()
    ): Project {
        val id = UUID.randomUUID().toString()
        val baseDir = File(context.filesDir, "vfs/$id")
        baseDir.mkdirs()

        // Create initial clean isolated workspace structure
        File(baseDir, "workspace").mkdirs()
        File(baseDir, "artifacts").mkdirs()
        File(baseDir, "workspace/README.md").writeText(
            "# $name Workspace\n\nCreated automatically for Local Bots Autonomous Agent.\n"
        )

        val project = Project(
            id = id,
            name = name.ifBlank { "Agent Project ${id.take(4)}" },
            description = description,
            avatarUri = avatarUri,
            referencePictures = referencePictures,
            workspacePath = baseDir.absolutePath,
            createdAt = System.currentTimeMillis(),
            lastActiveAt = System.currentTimeMillis()
        )

        // Save
        val currentIds = prefs.getStringSet("project_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
        currentIds.add(id)
        prefs.edit()
            .putStringSet("project_ids", currentIds)
            .putString("proj_$id", json.encodeToString(project))
            .apply()

        loadProjects()
        _activeProject.value = project
        return project
    }

    fun setActiveProject(project: Project) {
        _activeProject.value = project
        val updated = project.copy(lastActiveAt = System.currentTimeMillis())
        prefs.edit().putString("proj_${project.id}", json.encodeToString(updated)).apply()
        loadProjects()
    }

    fun getMessagesForProject(projectId: String): List<ChatMessage> {
        val cached = _chatMessages.value[projectId]
        if (cached != null) return cached

        val msgJson = prefs.getString("messages_$projectId", null) ?: return emptyList()
        return try {
            val list = json.decodeFromString<List<ChatMessage>>(msgJson)
            val updatedMap = _chatMessages.value.toMutableMap()
            updatedMap[projectId] = list
            _chatMessages.value = updatedMap
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addMessage(message: ChatMessage) {
        val list = getMessagesForProject(message.projectId).toMutableList()
        list.add(message)
        saveMessages(message.projectId, list)
    }

    fun updateMessage(message: ChatMessage) {
        val list = getMessagesForProject(message.projectId).toMutableList()
        val index = list.indexOfFirst { it.id == message.id }
        if (index != -1) {
            list[index] = message
            saveMessages(message.projectId, list)
        }
    }

    private fun saveMessages(projectId: String, messages: List<ChatMessage>) {
        val updatedMap = _chatMessages.value.toMutableMap()
        updatedMap[projectId] = messages
        _chatMessages.value = updatedMap

        prefs.edit().putString("messages_$projectId", json.encodeToString(messages)).apply()
    }
}
