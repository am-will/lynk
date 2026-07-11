package dev.androidagent.localmodel

internal object LocalPhoneCommandOwner {
    fun id(sessionKey: String, runId: String): String = "local:$sessionKey:$runId"
}
