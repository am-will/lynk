package dev.androidagent.localmodel.gguf

internal data class GgufModelKey(
    val path: String,
    val contextTokens: Int,
    val backendKey: String,
    val gpuLayers: Int
)
