package com.localbots.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ApiKeyConfig(
    val slotNumber: Int, // 1 through 12
    val providerName: String,
    val apiKey: String = "",
    val baseUrl: String = "",
    val modelName: String = "",
    val isEnabled: Boolean = false,
    val isHealthy: Boolean = true,
    val totalRequests: Long = 0L,
    val lastUsedTimestamp: Long = 0L,
    val lastError: String? = null
) {
    val maskedKey: String
        get() = if (apiKey.length > 8) {
            "${apiKey.take(4)}••••••••${apiKey.takeLast(4)}"
        } else if (apiKey.isNotEmpty()) {
            "••••••••"
        } else {
            "Not Configured"
        }
}

object DefaultConnectors {
    val defaultSlots = listOf(
        ApiKeyConfig(1, "Google Gemini (Primary)", modelName = "gemini-3.8-flash", isEnabled = true),
        ApiKeyConfig(2, "Antigravity Cloud Sync", modelName = "gemini-3.8-flash", isEnabled = false),
        ApiKeyConfig(3, "Anthropic Claude", modelName = "claude-3-7-sonnet", isEnabled = false),
        ApiKeyConfig(4, "OpenAI GPT", modelName = "gpt-4o", isEnabled = false),
        ApiKeyConfig(5, "OpenRouter", modelName = "auto", isEnabled = false),
        ApiKeyConfig(6, "Groq Fast Inference", modelName = "llama-3.3-70b-versatile", isEnabled = false),
        ApiKeyConfig(7, "DeepSeek AI", modelName = "deepseek-chat", isEnabled = false),
        ApiKeyConfig(8, "Mistral AI", modelName = "mistral-large", isEnabled = false),
        ApiKeyConfig(9, "Together AI", modelName = "meta-llama/Llama-3-70b-chat-hf", isEnabled = false),
        ApiKeyConfig(10, "Perplexity AI", modelName = "sonar-pro", isEnabled = false),
        ApiKeyConfig(11, "Local Ollama Endpoint", baseUrl = "http://127.0.0.1:11434", modelName = "llama3", isEnabled = false),
        ApiKeyConfig(12, "Custom LLM Connector", isEnabled = false)
    )
}
