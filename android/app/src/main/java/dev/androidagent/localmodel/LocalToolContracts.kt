package dev.androidagent.localmodel

import dev.androidagent.accessibility.PhoneCommandPolicy
import org.json.JSONObject
import java.util.UUID

internal sealed interface LocalToolValidation {
    data class Valid(val call: LocalToolCall) : LocalToolValidation
    data class Invalid(val error: String) : LocalToolValidation
}

/** Canonical argument validation for every tool reachable by the local model. */
internal object LocalToolContracts {
    private val knownTools = LocalToolSpecs.all.map { it.id }.toSet()
    private val noArgs = setOf("phone_observe", "phone_press_back", "phone_press_home", "phone_open_recents")
    private val nodeId = Regex("^n[1-9][0-9]*$")
    private val packageName = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+){1,15}$")

    fun validate(call: LocalToolCall, approvalTarget: Boolean = false): LocalToolValidation {
        if (call.name !in knownTools) return invalid("unknown tool ${call.name}")
        return runCatching {
            validateArgs(call.name, call.args, approvalTarget)
            LocalToolValidation.Valid(LocalToolCall(call.name, JSONObject(call.args.toString())))
        }.getOrElse { invalid(it.message ?: "invalid arguments") }
    }

    private fun validateArgs(name: String, args: JSONObject, approvalTarget: Boolean) {
        when (name) {
            in noArgs -> exactKeys(args)
            "phone_open_app" -> {
                exactKeys(args, "packageName", "appName")
                val packageValue = optionalString(args, "packageName", 200)
                val appValue = optionalString(args, "appName", 200)
                require((packageValue == null) != (appValue == null)) { "exactly one of packageName or appName is required" }
                if (packageValue != null) require(packageName.matches(packageValue)) { "packageName is invalid" }
            }
            "phone_tap_node", "phone_long_press_node" -> {
                exactKeys(args, "observationId", "nodeId", "approvalCapability")
                uuid(args, "observationId")
                require(nodeId.matches(requiredString(args, "nodeId", 32))) { "nodeId is invalid" }
                approval(args, approvalTarget)
            }
            "phone_tap_xy" -> {
                exactKeys(args, "x", "y", "approvalCapability")
                number(args, "x", 0.0, 100_000.0)
                number(args, "y", 0.0, 100_000.0)
                approval(args, approvalTarget)
            }
            "phone_tap_normalized" -> {
                exactKeys(args, "xPct", "yPct", "approvalCapability")
                number(args, "xPct", 0.0, 1.0)
                number(args, "yPct", 0.0, 1.0)
                approval(args, approvalTarget)
            }
            "phone_type_text" -> {
                exactKeys(args, "text", "approvalCapability")
                requiredString(args, "text", 10_000)
                approval(args, approvalTarget)
            }
            "phone_scroll" -> {
                exactKeys(args, "direction")
                require(requiredString(args, "direction", 5) in setOf("up", "down", "left", "right")) { "direction is invalid" }
            }
            "phone_swipe" -> {
                exactKeys(args, "startX", "startY", "endX", "endY", "durationMs")
                listOf("startX", "startY", "endX", "endY").forEach { number(args, it, 0.0, 100_000.0) }
                optionalInteger(args, "durationMs", 50, 5_000)
            }
            "phone_take_screenshot", "phone_submit_text" -> {
                exactKeys(args, "approvalCapability")
                approval(args, approvalTarget)
            }
            "phone_ask_user_confirmation" -> confirmation(args)
            "phone_wait" -> {
                exactKeys(args, "ms")
                integer(args, "ms", 0, 120_000)
            }
            "local_read_skill" -> {
                exactKeys(args, "name")
                require(requiredString(args, "name", 64) == LocalToolRegistry.ANDROID_CONTROL_SKILL_NAME) { "unknown packaged skill" }
            }
            "local_list_files" -> {
                exactKeys(args, "path")
                optionalPath(args, "path")
            }
            "local_read_file" -> {
                exactKeys(args, "path")
                requiredPath(args, "path")
            }
            "local_write_file" -> {
                exactKeys(args, "path", "text", "approvalCapability")
                requiredPath(args, "path")
                requiredString(args, "text", 80_000)
                approval(args, approvalTarget)
            }
            "local_search_files" -> {
                exactKeys(args, "query", "path")
                requiredString(args, "query", 500)
                optionalPath(args, "path")
            }
            "termux_command" -> {
                exactKeys(args, "command", "workdir", "timeoutMs", "approvalCapability")
                requiredString(args, "command", 20_000)
                optionalPath(args, "workdir")
                optionalInteger(args, "timeoutMs", 1_000, 300_000)
                approval(args, approvalTarget)
            }
        }
    }

    private fun confirmation(args: JSONObject) {
        exactKeys(args, "command", "args", "message", "preview")
        val target = requiredString(args, "command", 64)
        val targetArgs = args.optJSONObject("args") ?: throw IllegalArgumentException("args must be an object")
        optionalString(args, "message", 1_000)
        optionalString(args, "preview", 2_000)
        val targetTool = LocalToolSpecs.phoneCommandsByToolId.entries.firstOrNull { it.value == target }?.key ?: target
        val isSensitive = PhoneCommandPolicy.requiresApproval(target) || targetTool in setOf("local_write_file", "termux_command")
        require(isSensitive) { "$target is not an approval-requiring action" }
        when (val validation = validate(LocalToolCall(targetTool, targetArgs), approvalTarget = true)) {
            is LocalToolValidation.Invalid -> throw IllegalArgumentException("invalid approval target: ${validation.error}")
            is LocalToolValidation.Valid -> Unit
        }
    }

    private fun approval(args: JSONObject, approvalTarget: Boolean) {
        if (approvalTarget) {
            require(!args.has("approvalCapability")) { "approval target must not contain approvalCapability" }
        } else {
            requiredString(args, "approvalCapability", 256, minLength = 20)
        }
    }

    private fun exactKeys(args: JSONObject, vararg allowed: String) {
        val keys = args.keys().asSequence().toSet()
        val extra = keys - allowed.toSet()
        require(extra.isEmpty()) { "unknown argument fields: $extra" }
    }

    private fun requiredString(args: JSONObject, key: String, maxLength: Int, minLength: Int = 1): String {
        require(args.has(key) && !args.isNull(key)) { "$key is required" }
        val value = args.opt(key)
        require(value is String) { "$key must be a string" }
        require(value.length in minLength..maxLength) { "$key length must be $minLength..$maxLength" }
        require('\u0000' !in value) { "$key contains a null character" }
        return value
    }

    private fun optionalString(args: JSONObject, key: String, maxLength: Int): String? {
        if (!args.has(key)) return null
        return requiredString(args, key, maxLength)
    }

    private fun requiredPath(args: JSONObject, key: String): String = requiredString(args, key, 4_096)
    private fun optionalPath(args: JSONObject, key: String): String? = optionalString(args, key, 4_096)

    private fun uuid(args: JSONObject, key: String) {
        val value = requiredString(args, key, 64)
        require(runCatching { UUID.fromString(value) }.isSuccess) { "$key must be a UUID" }
    }

    private fun number(args: JSONObject, key: String, min: Double, max: Double): Double {
        require(args.has(key) && !args.isNull(key)) { "$key is required" }
        val raw = args.opt(key)
        require(raw is Number) { "$key must be a number" }
        val value = raw.toDouble()
        require(value.isFinite() && value in min..max) { "$key must be in $min..$max" }
        return value
    }

    private fun integer(args: JSONObject, key: String, min: Long, max: Long): Long {
        val value = number(args, key, min.toDouble(), max.toDouble())
        require(value % 1.0 == 0.0) { "$key must be an integer" }
        return value.toLong()
    }

    private fun optionalInteger(args: JSONObject, key: String, min: Long, max: Long): Long? =
        if (args.has(key)) integer(args, key, min, max) else null

    private fun invalid(error: String) = LocalToolValidation.Invalid(error)
}
