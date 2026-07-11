package dev.androidagent.accessibility

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

internal data class PhoneActionDescriptor(
    val command: String,
    val normalizedArgs: String,
    val digest: String,
    val summary: String
) {
    companion object {
        fun create(command: String, args: JSONObject): PhoneActionDescriptor {
            val normalizedArgs = CanonicalJson.encode(args)
            val digest = sha256("$command\n$normalizedArgs")
            return PhoneActionDescriptor(command, normalizedArgs, digest, PhoneActionSummary.describe(command, args))
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

internal data class ApprovalCapability(
    val token: String,
    val ownerId: String,
    val action: PhoneActionDescriptor,
    val observationId: String?,
    val expiresAtMs: Long
)

internal sealed interface ApprovalValidation {
    data class Approved(val capability: ApprovalCapability) : ApprovalValidation
    data object Missing : ApprovalValidation
    data object Unknown : ApprovalValidation
    data object Expired : ApprovalValidation
    data object Replayed : ApprovalValidation
    data object Cancelled : ApprovalValidation
    data object WrongOwner : ApprovalValidation
    data object WrongAction : ApprovalValidation
    data object ChangedObservation : ApprovalValidation
}

internal fun ApprovalValidation.denialMessage(actionSummary: String): String? = when (this) {
    is ApprovalValidation.Approved -> null
    ApprovalValidation.Missing -> "authorization_required: request user approval for $actionSummary"
    ApprovalValidation.Unknown -> "authorization_invalid: approval capability is unknown"
    ApprovalValidation.Expired -> "authorization_expired: request user approval again"
    ApprovalValidation.Replayed -> "authorization_replayed: approval capabilities are single-use"
    ApprovalValidation.Cancelled -> "authorization_cancelled: the approval is no longer active"
    ApprovalValidation.WrongOwner -> "authorization_wrong_owner: approval belongs to another request owner"
    ApprovalValidation.WrongAction -> "authorization_wrong_action: command or arguments differ from the approved action"
    ApprovalValidation.ChangedObservation -> "authorization_context_changed: the observed screen changed after approval"
}

internal class ApprovalCapabilityStore(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val tokenGenerator: () -> String = ::secureToken,
    private val ttlMs: Long = DEFAULT_TTL_MS
) {
    private val active = linkedMapOf<String, ApprovalCapability>()
    private val terminal = linkedMapOf<String, TerminalState>()

    @Synchronized
    fun issue(ownerId: String, action: PhoneActionDescriptor, observationId: String?): ApprovalCapability {
        require(ownerId.isNotBlank()) { "Approval owner is required" }
        require(PhoneCommandPolicy.requiresApproval(action.command)) { "${action.command} does not require approval" }
        cleanup()
        val capability = ApprovalCapability(
            token = tokenGenerator(),
            ownerId = ownerId,
            action = action,
            observationId = observationId,
            expiresAtMs = nowMs() + ttlMs
        )
        active[capability.token] = capability
        return capability
    }

    @Synchronized
    fun validateAndConsume(
        token: String?,
        ownerId: String,
        action: PhoneActionDescriptor,
        observationId: String?
    ): ApprovalValidation {
        if (token.isNullOrBlank()) return ApprovalValidation.Missing
        when (terminal[token]) {
            TerminalState.Consumed -> return ApprovalValidation.Replayed
            TerminalState.Expired -> return ApprovalValidation.Expired
            TerminalState.Cancelled -> return ApprovalValidation.Cancelled
            null -> Unit
        }
        val capability = active[token] ?: return ApprovalValidation.Unknown
        if (capability.expiresAtMs <= nowMs()) {
            active.remove(token)
            remember(token, TerminalState.Expired)
            return ApprovalValidation.Expired
        }
        if (capability.ownerId != ownerId) return ApprovalValidation.WrongOwner
        if (capability.action.digest != action.digest) return ApprovalValidation.WrongAction
        if (capability.observationId != null && capability.observationId != observationId) {
            active.remove(token)
            remember(token, TerminalState.Cancelled)
            return ApprovalValidation.ChangedObservation
        }
        active.remove(token)
        remember(token, TerminalState.Consumed)
        return ApprovalValidation.Approved(capability)
    }

    @Synchronized
    fun cancelOwner(ownerId: String) {
        val tokens = active.values.filter { it.ownerId == ownerId }.map { it.token }
        tokens.forEach { token ->
            active.remove(token)
            remember(token, TerminalState.Cancelled)
        }
    }

    @Synchronized
    fun cancelOwnerPrefix(prefix: String) {
        val tokens = active.values.filter { it.ownerId.startsWith(prefix) }.map { it.token }
        tokens.forEach { token ->
            active.remove(token)
            remember(token, TerminalState.Cancelled)
        }
    }

    @Synchronized
    fun clear() {
        active.clear()
        terminal.clear()
    }

    private fun cleanup() {
        val now = nowMs()
        active.values.filter { it.expiresAtMs <= now }.map { it.token }.forEach { token ->
            active.remove(token)
            remember(token, TerminalState.Expired)
        }
    }

    private fun remember(token: String, state: TerminalState) {
        terminal[token] = state
        while (terminal.size > MAX_TERMINAL_TOKENS) terminal.remove(terminal.keys.first())
    }

    private enum class TerminalState { Consumed, Expired, Cancelled }

    companion object {
        const val DEFAULT_TTL_MS = 60_000L
        private const val MAX_TERMINAL_TOKENS = 256

        private fun secureToken(): String = ByteArray(32).also(SecureRandom()::nextBytes)
            .let { Base64.encodeToString(it, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE) }
    }
}

internal object PhoneActionSummary {
    fun describe(command: String, args: JSONObject): String = when (command) {
        "tap_node" -> "Tap observed node ${args.optString("nodeId", "<missing>")}" 
        "tap_xy" -> "Tap screen coordinates (${args.opt("x")}, ${args.opt("y")})"
        "tap_normalized" -> "Tap screen position (${args.opt("xPct")}, ${args.opt("yPct")})"
        "long_press_node" -> "Long-press observed node ${args.optString("nodeId", "<missing>")}" 
        "type_text" -> "Type into the focused field: ${JSONObject.quote(args.optString("text"))}"
        "submit_text" -> "Submit the focused text field"
        "take_screenshot" -> "Capture the current screen"
        else -> "Run $command with ${CanonicalJson.encode(args)}"
    }
}

private object CanonicalJson {
    fun encode(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(prefix = "{", postfix = "}") { key ->
            "${JSONObject.quote(key)}:${encode(value.opt(key))}"
        }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { index -> encode(value.opt(index)) }
        is String -> JSONObject.quote(value)
        is Boolean, is Number -> value.toString()
        else -> JSONObject.quote(value.toString())
    }
}
