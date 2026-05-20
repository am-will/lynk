package dev.androidagent.localmodel

import dev.androidagent.AgentConfig

data class LocalModelRequest(
    val prompt: String,
    val systemPrompt: String,
    val config: AgentConfig
)

interface LocalModelRuntime {
    suspend fun generate(request: LocalModelRequest, onDelta: suspend (String) -> Unit): String
    fun close()
}
