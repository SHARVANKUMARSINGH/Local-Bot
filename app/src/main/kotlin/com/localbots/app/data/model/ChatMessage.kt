package com.localbots.app.data.model

import kotlinx.serialization.Serializable

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

@Serializable
data class ChatMessage(
    val id: String,
    val projectId: String,
    val role: MessageRole,
    val content: String,
    val attachedImages: List<String> = emptyList(),
    val actions: List<AgentActionExecution> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false
)
