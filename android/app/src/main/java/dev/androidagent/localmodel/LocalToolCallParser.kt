package dev.androidagent.localmodel

import org.json.JSONArray
import org.json.JSONObject

data class LocalToolCall(
    val name: String,
    val args: JSONObject = JSONObject()
)

object LocalToolCallParser {
    fun parse(text: String): List<LocalToolCall> {
        val json = extractJson(text.trim()) ?: return emptyList()
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        root.optJSONArray("toolCalls")?.let { return parseArray(it) }
        root.optJSONArray("tool_calls")?.let { return parseArray(it) }
        val name = root.optString("tool", root.optString("name")).takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf(LocalToolCall(name = name, args = root.optJSONObject("args") ?: root.optJSONObject("arguments") ?: JSONObject()))
    }

    private fun parseArray(array: JSONArray): List<LocalToolCall> {
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("tool", item.optString("name")).takeIf { it.isNotBlank() } ?: continue
                add(LocalToolCall(name = name, args = item.optJSONObject("args") ?: item.optJSONObject("arguments") ?: JSONObject()))
            }
        }
    }

    private fun extractJson(text: String): String? {
        if (text.startsWith("```")) {
            val body = text.lines()
                .drop(1)
                .dropLastWhile { it.trim().startsWith("```") || it.isBlank() }
                .joinToString("\n")
                .trim()
            if (body.startsWith("{") && body.endsWith("}")) return body
        }
        if (text.startsWith("{") && text.endsWith("}")) return text
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else null
    }
}
