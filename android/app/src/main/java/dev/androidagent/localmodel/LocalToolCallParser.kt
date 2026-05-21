package dev.androidagent.localmodel

import org.json.JSONArray
import org.json.JSONObject

data class LocalToolCall(
    val name: String,
    val args: JSONObject = JSONObject()
)

object LocalToolCallParser {
    fun parse(text: String): List<LocalToolCall> {
        parseTemplateToolCalls(text)?.let { return it }
        val json = extractJson(text.trim()) ?: return emptyList()
        val root = parseObject(json) ?: return regexFallback(json)
        root.optJSONArray("toolCalls")?.let { return parseArray(it) }
        root.optJSONArray("tool_calls")?.let { return parseArray(it) }
        val name = root.optString("tool", root.optString("name")).takeIf { it.isNotBlank() } ?: return emptyList()
        return listOf(LocalToolCall(name = name, args = root.optJSONObject("args") ?: root.optJSONObject("arguments") ?: JSONObject()))
    }

    private fun parseObject(json: String): JSONObject? {
        runCatching { JSONObject(json) }.getOrNull()?.let { return it }

        // Gemma local models sometimes emit `"args:{}}` or `"arguments:{...}`
        // instead of `"args":{}`. Repair that narrow case before giving up.
        val repaired = json
            .replace(Regex("\"(args|arguments):"), "\"$1\":")
            .replace(Regex("'([^']+)'\\s*:"), "\"$1\":")
            .replace('\'', '"')
        return runCatching { JSONObject(repaired) }.getOrNull()
    }

    private fun regexFallback(json: String): List<LocalToolCall> {
        val name = Regex("\"(?:tool|name)\"\\s*:\\s*\"([^\"]+)\"")
            .find(json)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        return listOf(LocalToolCall(name = name, args = JSONObject()))
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

    private fun parseTemplateToolCalls(text: String): List<LocalToolCall>? {
        val matches = Regex(
            """<\|tool_call>\s*call:([A-Za-z0-9_]+)\s*\{(.*?)\}\s*<tool_call\|>""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        ).findAll(text).toList()
        if (matches.isEmpty()) return null
        return matches.mapNotNull { match ->
            val name = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            LocalToolCall(name = name, args = parseTemplateArgs(match.groupValues.getOrNull(2).orEmpty()))
        }
    }

    private fun parseTemplateArgs(args: String): JSONObject {
        val trimmed = args.trim()
        if (trimmed.isBlank()) return JSONObject()
        val jsonLike = trimmed
            .replace("""<|"|>""", "\"")
            .replace(Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*:"""), "\"$1\":")
        return runCatching { JSONObject("{$jsonLike}") }.getOrElse { JSONObject() }
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
