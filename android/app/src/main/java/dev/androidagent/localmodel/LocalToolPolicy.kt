package dev.androidagent.localmodel

import org.json.JSONObject

internal object LocalToolPolicy {
    fun shouldAllowTools(userText: String): Boolean {
        val text = userText.lowercase()
        val actionKeywords = listOf(
            "phone", "screen", "screenshot", "observe", "tap", "click", "press", "swipe", "scroll",
            "type into", "open app", "launch", "settings", "youtube", "home button", "back button",
            "camera", "browser", "termux", "terminal", "shell", "command", "execute",
            "file", "folder", "directory", "workspace", "project", "index.html", "html", "css", "javascript"
        )
        return actionKeywords.any { text.contains(it) }
    }

    fun shouldLoadAndroidControlSkill(userText: String): Boolean {
        val text = userText.lowercase()
        val phoneSignals = listOf(
            "phone", "screen", "screenshot", "observe", "tap", "click", "press", "swipe", "scroll",
            "type into", "open app", "launch", "settings", "youtube", "camera", "home button", "back button",
            "notification", "recents", "android"
        )
        val nonPhoneSignals = listOf("termux", "terminal", "shell", "command", "file", "folder", "directory", "project", "html", "css", "javascript")
        return phoneSignals.any { text.contains(it) } && nonPhoneSignals.none { text.contains(it) }
    }

    fun isPhoneTool(name: String): Boolean =
        LocalToolSpecs.phoneCommandsByToolId.containsKey(name)

    fun shouldRejectCommandRequest(userText: String, response: String): Boolean {
        val user = userText.lowercase()
        val answer = response.lowercase()
        val askedForExecutableWork = listOf("termux", "terminal", "shell", "command", "file", "folder", "directory", "project", "html", "css", "javascript")
            .any { user.contains(it) }
        val isAskingUserForCommand = listOf("provide the command", "specific command", "exact command", "tell me the command", "what command")
            .any { answer.contains(it) }
        return askedForExecutableWork && isAskingUserForCommand
    }

    fun termuxCommandText(args: JSONObject): String =
        args.optString("command")
            .ifBlank { args.optString("cmd") }
            .ifBlank { args.optString("script") }
}
