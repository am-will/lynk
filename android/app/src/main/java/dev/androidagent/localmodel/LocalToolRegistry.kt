package dev.androidagent.localmodel

import android.content.Context
import dev.androidagent.AgentConfig
import dev.androidagent.accessibility.AccessibilityCommandExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

class LocalToolRegistry(
    private val context: Context,
    private val commandExecutor: AccessibilityCommandExecutor,
    private val configProvider: () -> AgentConfig
) {
    private val phoneTools = mapOf(
        "phone_observe" to "observe_screen",
        "phone_open_app" to "open_app",
        "phone_tap_node" to "tap_node",
        "phone_tap_xy" to "tap_xy",
        "phone_tap_normalized" to "tap_normalized",
        "phone_long_press_node" to "long_press_node",
        "phone_type_text" to "type_text",
        "phone_scroll" to "scroll",
        "phone_swipe" to "swipe",
        "phone_press_back" to "press_back",
        "phone_press_home" to "press_home",
        "phone_open_recents" to "open_recents",
        "phone_take_screenshot" to "take_screenshot",
        "phone_ask_user_confirmation" to "ask_user_confirmation",
        "phone_wait" to "wait"
    )

    fun toolDescriptions(): JSONArray = JSONArray()
        .put(tool("phone_observe", "Observe the current Android screen.", "phone"))
        .put(tool("phone_open_app", "Open an Android app by packageName or appName.", "phone"))
        .put(tool("phone_tap_node", "Tap an observed accessibility node by nodeId.", "phone"))
        .put(tool("phone_type_text", "Type text into the focused field.", "phone"))
        .put(tool("phone_scroll", "Scroll the active screen up, down, left, or right.", "phone"))
        .put(tool("phone_take_screenshot", "Capture an Android screenshot if supported.", "phone"))
        .put(tool("phone_ask_user_confirmation", "Ask the user to confirm a sensitive action.", "phone"))
        .put(tool("local_list_files", "List files in the local app workspace.", "workspace"))
        .put(tool("local_read_file", "Read a UTF-8 text file from the local app workspace.", "workspace"))
        .put(tool("local_write_file", "Write a UTF-8 text file in the local app workspace.", "workspace"))
        .put(tool("local_search_files", "Search UTF-8 files in the local app workspace.", "workspace"))
        .put(tool("termux_command", "Run a developer command through a future Termux helper.", "developer"))

    suspend fun execute(call: LocalToolCall): JSONObject {
        val phoneCommand = phoneTools[call.name]
        if (phoneCommand != null) return executePhone(phoneCommand, call.args)
        return when (call.name) {
            "local_list_files" -> listFiles(call.args)
            "local_read_file" -> readFile(call.args)
            "local_write_file" -> writeFile(call.args)
            "local_search_files" -> searchFiles(call.args)
            "termux_command" -> termuxCommand(call.args)
            else -> JSONObject().put("ok", false).put("error", "Unknown local tool: ${call.name}")
        }
    }

    private suspend fun executePhone(command: String, args: JSONObject): JSONObject =
        suspendCancellableCoroutine { continuation ->
            commandExecutor.execute(command, args) { result ->
                val json = JSONObject()
                    .put("ok", result.ok)
                    .put("observation", result.observation)
                    .put("error", result.error)
                result.screenshot?.let { json.put("screenshot", it) }
                if (result.screenshotBase64 != null) {
                    json.put("screenshotBase64", "<omitted:${result.screenshotBase64.length} chars>")
                }
                continuation.resume(json)
            }
        }

    private fun listFiles(args: JSONObject): JSONObject {
        val dir = resolveWorkspacePath(args.optString("path", "."))
        if (!dir.exists()) return JSONObject().put("ok", true).put("files", JSONArray())
        if (!dir.isDirectory) return JSONObject().put("ok", false).put("error", "${args.optString("path")} is not a directory")
        val files = dir.listFiles()?.sortedBy { it.name.lowercase() }.orEmpty()
        return JSONObject().put("ok", true).put("files", JSONArray().also { array ->
            files.forEach { file ->
                array.put(JSONObject()
                    .put("name", file.name)
                    .put("path", workspaceRelativePath(file))
                    .put("directory", file.isDirectory)
                    .put("sizeBytes", if (file.isFile) file.length() else JSONObject.NULL))
            }
        })
    }

    private fun readFile(args: JSONObject): JSONObject {
        val file = resolveWorkspacePath(args.getString("path"))
        if (!file.isFile) return JSONObject().put("ok", false).put("error", "File not found: ${args.getString("path")}")
        return JSONObject().put("ok", true).put("path", workspaceRelativePath(file)).put("text", file.readText().take(80_000))
    }

    private fun writeFile(args: JSONObject): JSONObject {
        val config = configProvider()
        if (!config.localDeveloperToolsEnabled) {
            return JSONObject().put("ok", false).put("error", "Local developer tools are disabled in Connection & Config.")
        }
        val file = resolveWorkspacePath(args.getString("path"))
        file.parentFile?.mkdirs()
        file.writeText(args.optString("text"))
        return JSONObject().put("ok", true).put("path", workspaceRelativePath(file)).put("sizeBytes", file.length())
    }

    private fun searchFiles(args: JSONObject): JSONObject {
        val query = args.getString("query")
        val root = resolveWorkspacePath(args.optString("path", "."))
        if (!root.exists()) return JSONObject().put("ok", true).put("matches", JSONArray())
        val matches = JSONArray()
        root.walkTopDown()
            .filter { it.isFile && it.length() <= 1_000_000L }
            .take(500)
            .forEach { file ->
                val lines = runCatching { file.readLines() }.getOrNull() ?: return@forEach
                lines.forEachIndexed { index, line ->
                    if (line.contains(query, ignoreCase = true)) {
                        matches.put(JSONObject()
                            .put("path", workspaceRelativePath(file))
                            .put("line", index + 1)
                            .put("text", line.take(500)))
                    }
                }
            }
        return JSONObject().put("ok", true).put("matches", matches)
    }

    private fun termuxCommand(args: JSONObject): JSONObject {
        val config = configProvider()
        if (!config.localDeveloperToolsEnabled) {
            return JSONObject().put("ok", false).put("error", "Termux-backed tools are disabled in Connection & Config.")
        }
        return JSONObject()
            .put("ok", false)
            .put("command", args.optString("command"))
            .put("error", "Termux command execution requires a dedicated helper app and is not configured yet.")
    }

    private fun workspaceRoot(): File = File(context.filesDir, "local-workspace").apply { mkdirs() }

    private fun resolveWorkspacePath(path: String): File {
        val root = workspaceRoot().canonicalFile
        val file = File(root, path.ifBlank { "." }).canonicalFile
        require(file.path == root.path || file.path.startsWith(root.path + File.separator)) {
            "Path escapes local workspace: $path"
        }
        return file
    }

    private fun workspaceRelativePath(file: File): String {
        val root = workspaceRoot().canonicalFile
        return file.canonicalFile.relativeTo(root).path.ifBlank { "." }
    }

    private fun tool(id: String, description: String, group: String): JSONObject =
        JSONObject().put("id", id).put("label", id).put("description", description).put("source", "local").put("group", group)
}
