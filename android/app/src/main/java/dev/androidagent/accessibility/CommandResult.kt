package dev.androidagent.accessibility

import org.json.JSONObject

data class CommandResult(
    val ok: Boolean,
    val observation: JSONObject?,
    val error: String? = null,
    val screenshotBase64: String? = null,
    val screenshot: JSONObject? = null,
    val approvalCapability: String? = null,
    val approvalExpiresAtMs: Long? = null,
    val approvedAction: String? = null
)
