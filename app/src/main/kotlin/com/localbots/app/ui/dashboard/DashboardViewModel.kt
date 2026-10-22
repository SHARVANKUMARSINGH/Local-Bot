package com.localbots.app.ui.dashboard

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.localbots.app.data.model.Project
import com.localbots.app.data.repository.ProjectRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DashboardViewModel(
    private val projectRepository: ProjectRepository
) : ViewModel() {

    val projects: StateFlow<List<Project>> = projectRepository.projects
    val activeProject: StateFlow<Project?> = projectRepository.activeProject

    fun createProject(
        name: String,
        description: String,
        avatarUri: Uri?,
        referenceUris: List<Uri>
    ) {
        viewModelScope.launch {
            projectRepository.createProject(
                name = name,
                description = description,
                avatarUri = avatarUri?.toString(),
                referencePictures = referenceUris.map { it.toString() }
            )
        }
    }

    fun selectProject(project: Project) {
        projectRepository.setActiveProject(project)
    }
}
