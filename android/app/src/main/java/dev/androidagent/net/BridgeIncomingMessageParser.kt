package dev.androidagent.net

import org.json.JSONObject

internal object BridgeIncomingMessageParser {
    fun parse(text: String): Result<JSONObject> = runCatching { JSONObject(text) }
}
