package dev.androidagent.localmodel

import org.json.JSONArray
import org.json.JSONObject

data class LocalToolCall(
    val name: String,
    val args: JSONObject = JSONObject()
)

sealed interface LocalModelOutput {
    data class AssistantText(val text: String) : LocalModelOutput
    data class ToolControl(val call: LocalToolCall) : LocalModelOutput
    data class InvalidControl(val error: String) : LocalModelOutput
}

/**
 * The LiteRT API exposes one text stream and no native structured-output channel. Treat only one
 * exact, whole-response control frame as executable; every unframed response remains display text.
 */
object LocalToolCallParser {
    const val OPEN = "<|lynk_control|>"
    const val CLOSE = "<|/lynk_control|>"
    private const val VERSION = 1L
    private const val TYPE = "tool_call"
    private const val MAX_FRAME_CHARS = 65_536
    private val toolName = Regex("^[a-z][a-z0-9_]{0,63}$")
    private val rootKeys = setOf("version", "type", "tool", "args")

    fun parse(text: String): LocalModelOutput {
        val trimmed = text.trim()
        val containsMarker = trimmed.contains(OPEN) || trimmed.contains(CLOSE)
        if (!containsMarker) return LocalModelOutput.AssistantText(text)
        if (!trimmed.startsWith(OPEN) || !trimmed.endsWith(CLOSE)) {
            return LocalModelOutput.InvalidControl("Control frame must occupy the entire model response")
        }
        if (trimmed.countOccurrences(OPEN) != 1 || trimmed.countOccurrences(CLOSE) != 1) {
            return LocalModelOutput.InvalidControl("Exactly one control frame is allowed")
        }
        val payload = trimmed.removePrefix(OPEN).removeSuffix(CLOSE)
        if (payload.isBlank()) return LocalModelOutput.InvalidControl("Control frame payload is empty")
        if (payload.length > MAX_FRAME_CHARS) return LocalModelOutput.InvalidControl("Control frame exceeds $MAX_FRAME_CHARS characters")

        val root = try {
            StrictJson.parseObject(payload)
        } catch (error: StrictJsonException) {
            return LocalModelOutput.InvalidControl("Invalid control JSON: ${error.message}")
        }
        if (root.keys != rootKeys) {
            val missing = rootKeys - root.keys
            val extra = root.keys - rootKeys
            return LocalModelOutput.InvalidControl("Control fields mismatch; missing=$missing extra=$extra")
        }
        if (root["version"] != VERSION) return LocalModelOutput.InvalidControl("Unsupported control version")
        if (root["type"] != TYPE) return LocalModelOutput.InvalidControl("Unsupported control type")
        val name = root["tool"] as? String
            ?: return LocalModelOutput.InvalidControl("Tool name must be a string")
        if (!toolName.matches(name)) return LocalModelOutput.InvalidControl("Tool name is invalid")
        val args = root["args"] as? Map<*, *>
            ?: return LocalModelOutput.InvalidControl("Tool args must be an object")
        return when (val validation = LocalToolContracts.validate(LocalToolCall(name, args.toJsonObject()))) {
            is LocalToolValidation.Valid -> LocalModelOutput.ToolControl(validation.call)
            is LocalToolValidation.Invalid -> LocalModelOutput.InvalidControl("Invalid tool call: ${validation.error}")
        }
    }

    private fun String.countOccurrences(value: String): Int {
        var count = 0
        var start = 0
        while (true) {
            val found = indexOf(value, start)
            if (found < 0) return count
            count += 1
            start = found + value.length
        }
    }
}

private class StrictJsonException(message: String) : IllegalArgumentException(message)

private object StrictJson {
    fun parseObject(text: String): Map<String, Any?> {
        val parser = Parser(text)
        val value = parser.value(0)
        parser.whitespace()
        if (!parser.done()) throw StrictJsonException("Trailing material at offset ${parser.offset()}")
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?> ?: throw StrictJsonException("Root must be an object")
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun offset(): Int = index
        fun done(): Boolean = index == text.length

        fun whitespace() {
            while (index < text.length && text[index] in charArrayOf(' ', '\t', '\r', '\n')) index += 1
        }

        fun value(depth: Int): Any? {
            if (depth > 20) throw StrictJsonException("Nesting exceeds 20 levels")
            whitespace()
            if (index >= text.length) throw StrictJsonException("Unexpected end at offset $index")
            return when (text[index]) {
                '{' -> objectValue(depth + 1)
                '[' -> arrayValue(depth + 1)
                '"' -> stringValue()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                '-', in '0'..'9' -> numberValue()
                else -> throw StrictJsonException("Unexpected '${text[index]}' at offset $index")
            }
        }

        private fun objectValue(depth: Int): Map<String, Any?> {
            expect('{')
            whitespace()
            val result = linkedMapOf<String, Any?>()
            if (take('}')) return result
            while (true) {
                whitespace()
                if (index >= text.length || text[index] != '"') throw StrictJsonException("Object key must be quoted at offset $index")
                val key = stringValue()
                if (result.containsKey(key)) throw StrictJsonException("Duplicate key ${JSONObject.quote(key)}")
                whitespace()
                expect(':')
                result[key] = value(depth)
                whitespace()
                if (take('}')) return result
                expect(',')
            }
        }

        private fun arrayValue(depth: Int): List<Any?> {
            expect('[')
            whitespace()
            val result = mutableListOf<Any?>()
            if (take(']')) return result
            while (true) {
                result += value(depth)
                if (result.size > 256) throw StrictJsonException("Array exceeds 256 items")
                whitespace()
                if (take(']')) return result
                expect(',')
            }
        }

        private fun stringValue(): String {
            expect('"')
            val result = StringBuilder()
            while (index < text.length) {
                val char = text[index++]
                when {
                    char == '"' -> return result.toString()
                    char == '\\' -> {
                        if (index >= text.length) throw StrictJsonException("Truncated escape")
                        when (val escaped = text[index++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000c')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> result.append(unicodeEscape())
                            else -> throw StrictJsonException("Invalid escape \\$escaped")
                        }
                    }
                    char.code < 0x20 -> throw StrictJsonException("Unescaped control character")
                    else -> result.append(char)
                }
                if (result.length > 32_768) throw StrictJsonException("String exceeds 32768 characters")
            }
            throw StrictJsonException("Unterminated string")
        }

        private fun unicodeEscape(): Char {
            if (index + 4 > text.length) throw StrictJsonException("Truncated unicode escape")
            val digits = text.substring(index, index + 4)
            index += 4
            return digits.toIntOrNull(16)?.toChar() ?: throw StrictJsonException("Invalid unicode escape")
        }

        private fun numberValue(): Number {
            val start = index
            take('-')
            if (take('0')) {
                if (index < text.length && text[index].isDigit()) throw StrictJsonException("Leading zero in number")
            } else {
                digits(required = true)
            }
            var decimal = false
            if (take('.')) {
                decimal = true
                digits(required = true)
            }
            if (index < text.length && text[index] in charArrayOf('e', 'E')) {
                decimal = true
                index += 1
                if (index < text.length && text[index] in charArrayOf('+', '-')) index += 1
                digits(required = true)
            }
            val raw = text.substring(start, index)
            if (!decimal) return raw.toLongOrNull() ?: throw StrictJsonException("Integer is out of range")
            val value = raw.toDoubleOrNull() ?: throw StrictJsonException("Number is invalid")
            if (!value.isFinite()) throw StrictJsonException("Number must be finite")
            return value
        }

        private fun digits(required: Boolean) {
            val start = index
            while (index < text.length && text[index].isDigit()) index += 1
            if (required && index == start) throw StrictJsonException("Expected digit at offset $index")
        }

        private fun <T> literal(expected: String, value: T): T {
            if (!text.startsWith(expected, index)) throw StrictJsonException("Expected $expected at offset $index")
            index += expected.length
            return value
        }

        private fun expect(expected: Char) {
            if (!take(expected)) throw StrictJsonException("Expected '$expected' at offset $index")
        }

        private fun take(expected: Char): Boolean {
            if (index >= text.length || text[index] != expected) return false
            index += 1
            return true
        }
    }
}

private fun Map<*, *>.toJsonObject(): JSONObject = JSONObject().also { json ->
    for ((rawKey, value) in this) {
        val key = rawKey as? String ?: throw StrictJsonException("Object key must be a string")
        json.put(key, value.toJsonValue())
    }
}

private fun Any?.toJsonValue(): Any = when (this) {
    null -> JSONObject.NULL
    is Map<*, *> -> toJsonObject()
    is List<*> -> JSONArray().also { array -> forEach { array.put(it.toJsonValue()) } }
    else -> this
}
