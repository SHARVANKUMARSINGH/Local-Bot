package com.localbots.app.ui.vfs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localbots.app.data.model.Project
import com.localbots.app.data.model.VfsNode
import com.localbots.app.data.repository.ProjectRepository
import com.localbots.app.data.repository.VfsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class VfsUiState(
    val activeProject: Project? = null,
    val currentPath: String = "",
    val items: List<VfsNode> = emptyList(),
    val searchQuery: String = "",
    val selectedFileContent: String? = null,
    val selectedFileName: String? = null,
    val errorMessage: String? = null
)

class VfsViewModel(
    private val projectRepository: ProjectRepository,
    private val vfsRepository: VfsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(VfsUiState())
    val uiState: StateFlow<VfsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            projectRepository.activeProject.collect { project ->
                _uiState.value = _uiState.value.copy(activeProject = project, currentPath = "")
                if (project != null) {
                    refresh()
                }
            }
        }
    }

    fun refresh() {
        val project = _uiState.value.activeProject ?: return
        val currentPath = _uiState.value.currentPath
        val items = vfsRepository.listDirectory(project.workspacePath, currentPath)
        _uiState.value = _uiState.value.copy(items = items, errorMessage = null)
    }

    fun openDirectory(dirNode: VfsNode) {
        if (!dirNode.isDirectory) return
        _uiState.value = _uiState.value.copy(currentPath = dirNode.relativePath)
        refresh()
    }

    fun navigateUp() {
        val current = _uiState.value.currentPath
        if (current.isBlank()) return
        val parent = if (current.contains("/")) current.substringBeforeLast("/") else ""
        _uiState.value = _uiState.value.copy(currentPath = parent)
        refresh()
    }

    fun openFile(fileNode: VfsNode) {
        val project = _uiState.value.activeProject ?: return
        try {
            val content = vfsRepository.readFile(project.workspacePath, fileNode.relativePath)
            _uiState.value = _uiState.value.copy(
                selectedFileContent = content,
                selectedFileName = fileNode.name
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(errorMessage = "Cannot read file: ${e.message}")
        }
    }

    fun closeFilePreview() {
        _uiState.value = _uiState.value.copy(selectedFileContent = null, selectedFileName = null)
    }

    fun createFolder(folderName: String) {
        val project = _uiState.value.activeProject ?: return
        val path = if (_uiState.value.currentPath.isBlank()) folderName else "${_uiState.value.currentPath}/$folderName"
        vfsRepository.createFolder(project.workspacePath, path)
        refresh()
    }

    fun createFile(fileName: String, content: String = "") {
        val project = _uiState.value.activeProject ?: return
        val path = if (_uiState.value.currentPath.isBlank()) fileName else "${_uiState.value.currentPath}/$fileName"
        vfsRepository.pushText(project.workspacePath, path, content)
        refresh()
    }

    fun deleteItem(node: VfsNode) {
        val project = _uiState.value.activeProject ?: return
        vfsRepository.deleteItem(project.workspacePath, node.relativePath)
        refresh()
    }

    fun search(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        val project = _uiState.value.activeProject ?: return
        if (query.isBlank()) {
            refresh()
        } else {
            val results = vfsRepository.searchFiles(project.workspacePath, query)
            _uiState.value = _uiState.value.copy(items = results)
        }
    }
}
