package dev.androidagent.localmodel

import org.json.JSONArray
import org.json.JSONObject

internal data class LocalToolSpec(
    val id: String,
    val description: String,
    val group: String,
    val phoneCommand: String? = null
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("label", id)
            .put("description", description)
            .put("source", "local")
            .put("group", group)
}

internal object LocalToolSpecs {
    val all: List<LocalToolSpec> = listOf(
        LocalToolSpec("phone_observe", "Observe the current Android screen.", "phone", "observe_screen"),
        LocalToolSpec("phone_open_app", "Open an Android app. Args: packageName for an exact package, or appName for a launcher label.", "phone", "open_app"),
        LocalToolSpec("phone_tap_node", "Tap an observed accessibility node by nodeId.", "phone", "tap_node"),
        LocalToolSpec("phone_tap_xy", "Tap an absolute screen coordinate in pixels. Args: x and y.", "phone", "tap_xy"),
        LocalToolSpec("phone_tap_normalized", "Tap a visual screen coordinate from a screenshot. Args: xPct and yPct as 0.0-1.0 fractions from the top-left corner.", "phone", "tap_normalized"),
        LocalToolSpec("phone_long_press_node", "Long-press an observed accessibility node by nodeId.", "phone", "long_press_node"),
        LocalToolSpec("phone_type_text", "Type text into the focused field.", "phone", "type_text"),
        LocalToolSpec("phone_scroll", "Scroll the active screen up, down, left, or right.", "phone", "scroll"),
        LocalToolSpec("phone_swipe", "Swipe between absolute screen coordinates in pixels.", "phone", "swipe"),
        LocalToolSpec("phone_press_back", "Press Android Back.", "phone", "press_back"),
        LocalToolSpec("phone_press_home", "Press Android Home.", "phone", "press_home"),
        LocalToolSpec("phone_open_recents", "Open Android Recents.", "phone", "open_recents"),
        LocalToolSpec("phone_take_screenshot", "Capture an Android screenshot. In local mode, the latest screenshot image is attached to the next model round when the selected model supports vision.", "phone", "take_screenshot"),
        LocalToolSpec("phone_submit_text", "Submit the focused search/text field using IME enter or a keyboard fallback tap.", "phone", "submit_text"),
        LocalToolSpec("phone_ask_user_confirmation", "Ask the user to confirm a sensitive action.", "phone", "ask_user_confirmation"),
        LocalToolSpec("phone_wait", "Wait for the requested number of milliseconds. Args: ms.", "phone", "wait"),
        LocalToolSpec("local_read_skill", "Read a packaged local skill file by name. Args: name. Use name android-control before Android phone-control tools.", "skill"),
        LocalToolSpec("local_list_files", "List files in the local app workspace.", "workspace"),
        LocalToolSpec("local_read_file", "Read a UTF-8 text file from the local app-private workspace. Args: path.", "workspace"),
        LocalToolSpec("local_write_file", "Write a UTF-8 text file in the local app-private workspace. Args: path and non-empty text. Do not use this for files the user wants to open in another phone app or browser.", "workspace"),
        LocalToolSpec("local_search_files", "Search UTF-8 files in the local app workspace.", "workspace"),
        LocalToolSpec("termux_command", "Run a shell command in Termux. Args: command, optional workdir/cwd, optional timeoutMs. Use this for browser-openable files and phone-accessible projects; choose the shell command yourself.", "developer")
    )

    val phoneCommandsByToolId: Map<String, String> =
        all.mapNotNull { spec -> spec.phoneCommand?.let { spec.id to it } }.toMap()

    fun descriptions(): JSONArray = JSONArray().also { array ->
        all.forEach { array.put(it.toJson()) }
    }
}
