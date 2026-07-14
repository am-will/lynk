package dev.androidagent.localmodel.gguf

/** Stable failure categories crossing the JNI boundary. */
enum class GgufFailureCategory(val wireCode: String) {
    VulkanBackend("vulkan_backend"),
    Model("model"),
    Context("context"),
    Prompt("prompt"),
    Cancellation("cancellation"),
    Session("session"),
    Decode("decode"),
    Configuration("configuration");

    companion object {
        fun fromWireCode(code: String): GgufFailureCategory =
            entries.firstOrNull { it.wireCode == code } ?: Configuration
    }
}

/**
 * A native GGUF failure whose category is explicit rather than inferred from
 * human-readable text. The string constructor is intentionally JNI-friendly.
 */
class GgufNativeException(
    val failureCode: String,
    message: String
) : RuntimeException(message) {
    val category: GgufFailureCategory = GgufFailureCategory.fromWireCode(failureCode)
}
