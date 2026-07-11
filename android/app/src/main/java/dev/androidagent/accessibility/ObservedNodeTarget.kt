package dev.androidagent.accessibility

import org.json.JSONObject
import java.util.UUID

internal data class ObservedNodeTarget(val observationId: String, val nodeId: String) {
    companion object {
        private val nodeIdPattern = Regex("^n[1-9][0-9]*$")

        fun parse(args: JSONObject): ObservedNodeTarget {
            val observationId = args.optString("observationId")
            require(runCatching { UUID.fromString(observationId) }.isSuccess) {
                "observationId must be the UUID returned by observe_screen"
            }
            val nodeId = args.optString("nodeId")
            require(nodeIdPattern.matches(nodeId)) { "nodeId must be an observed ordinal such as n1" }
            return ObservedNodeTarget(observationId, nodeId)
        }
    }
}
