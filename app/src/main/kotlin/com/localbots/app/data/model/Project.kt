package com.localbots.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Project(
    val id: String,
    val name: String,
    val description: String = "",
    val avatarUri: String? = null,
    val referencePictures: List<String> = emptyList(),
    val workspacePath: String,
    val createdAt: Long = System.currentTimeMillis(),
    val lastActiveAt: Long = System.currentTimeMillis()
)
