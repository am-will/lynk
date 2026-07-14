package dev.androidagent.localmodel

import android.content.Context
import android.util.Base64
import dev.androidagent.AgentConfig
import dev.androidagent.accessibility.AccessibilityCommandExecutor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class LocalToolRegistry(
    private val context: Context,
    private val commandExecutor: AccessibilityCommandExecutor,
    private val configProvider: () -> AgentConfig
) {
    private val termuxRunner = TermuxCommandRunner(context.applicationContext)

    private val phoneTools = LocalToolSpecs.phoneCommandsByToolId

    fun toolDescriptions(runtimeProfile: LocalModelRuntimeProfile) =
        LocalToolSpecs.descriptions(runtimeProfile)

    internal fun toolDescriptions(
        runtimeProfile: LocalModelRuntimeProfile,
        access: LocalToolAccess
    ) = LocalToolSpecs.descriptions(runtimeProfile, access)

    suspend fun execute(call: LocalToolCall, requestOwner: String): JSONObject {
        val validated = when (val validation = LocalToolContracts.validate(call)) {
            is LocalToolValidation.Valid -> validation.call
            is LocalToolValidation.Invalid -> return JSONObject().put("ok", false).put("error", "Invalid tool call: ${validation.error}")
        }
        val phoneCommand = phoneTools[validated.name]
        return if (phoneCommand != null) {
            executePhone(phoneCommand, validated.args, requestOwner)
        } else when (validated.name) {
            "local_read_skill" -> readSkill(validated.args)
            "local_list_files" -> listFiles(validated.args)
            "local_read_file" -> readFile(validated.args)
            "local_write_file" -> writeFile(validated.args, requestOwner)
            "local_search_files" -> searchFiles(validated.args)
            "termux_command" -> termuxCommand(validated.args, requestOwner)
            else -> JSONObject().put("ok", false).put("error", "Unknown local tool: ${validated.name}")
        }
    }

    private suspend fun executePhone(command: String, args: JSONObject, requestOwner: String): JSONObject {
        val normalizedArgs = normalizePhoneArgs(command, args)
        val approvalCapability = normalizedArgs.optString("approvalCapability").takeIf { it.isNotBlank() }
        val commandArgs = JSONObject(normalizedArgs.toString()).apply { remove("approvalCapability") }
        val result = commandExecutor.executeSuspending(
            commandId = "local_cmd_${UUID.randomUUID()}",
            command = command,
            args = commandArgs,
            requestOwner = requestOwner,
            approvalCapability = approvalCapability
        )
        return JSONObject()
            .put("ok", result.ok)
            .put("observation", result.observation)
            .put("error", result.error)
            .put("approvalCapability", result.approvalCapability)
            .put("approvalExpiresAtMs", result.approvalExpiresAtMs)
            .put("approvedAction", result.approvedAction)
            .also { json ->
                result.screenshot?.let { json.put("screenshot", it) }
                if (result.screenshotBase64 != null) {
                    val path = saveScreenshot(result.screenshotBase64)
                    json.put("screenshotPath", path)
                    json.put("screenshotBase64", "<omitted:${result.screenshotBase64.length} chars>")
                }
            }
    }

    fun cancelApprovals(requestOwner: String) {
        commandExecutor.cancelApprovals(requestOwner)
    }

    fun cancelTermux(requestOwner: String): Int =
        termuxRunner.cancelOwner(requestOwner, TermuxCancellationReason.SESSION_STOPPED)

    fun close(): Int = termuxRunner.cancelAll(TermuxCancellationReason.SERVICE_DESTROYED)

    private fun normalizePhoneArgs(command: String, args: JSONObject): JSONObject {
        if (command == "open_app" && !args.has("packageName")) {
            val appName = args.optString("appName")
            if (looksLikePackageName(appName)) {
                return JSONObject(args.toString()).apply {
                    put("packageName", appName)
                    remove("appName")
                }
            }
        }
        if (command == "tap_normalized" && (!args.has("xPct") || !args.has("yPct"))) {
            val x = args.optDouble("x", Double.NaN)
            val y = args.optDouble("y", Double.NaN)
            if (!x.isNaN() && !y.isNaN()) {
                return JSONObject(args.toString()).apply {
                    put("xPct", if (x > 1.0) x / 100.0 else x)
                    put("yPct", if (y > 1.0) y / 100.0 else y)
                }
            }
        }
        return args
    }

    private fun looksLikePackageName(value: String): Boolean =
        value.count { it == '.' } >= 2 && value.all { it.isLetterOrDigit() || it == '.' || it == '_' }

    private fun readSkill(args: JSONObject): JSONObject {
        val name = args.optString("name").ifBlank { args.optString("skill") }.ifBlank { "android-control" }
        if (name != ANDROID_CONTROL_SKILL_NAME) {
            return JSONObject()
                .put("ok", false)
                .put("error", "Unknown packaged local skill: $name")
                .put("availableSkills", JSONArray(listOf(ANDROID_CONTROL_SKILL_NAME)))
        }
        val path = "skills/$ANDROID_CONTROL_SKILL_NAME/SKILL.md"
        val text = context.assets.open(path).bufferedReader().use { it.readText() }
        return JSONObject()
            .put("ok", true)
            .put("name", ANDROID_CONTROL_SKILL_NAME)
            .put("path", path)
            .put("text", text)
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

    private fun writeFile(args: JSONObject, requestOwner: String): JSONObject {
        val config = configProvider()
        if (!config.localDeveloperToolsEnabled) {
            return JSONObject().put("ok", false).put("error", "Local developer tools are disabled in Connection & Config.")
        }
        val actionArgs = args.withoutApprovalCapability()
        commandExecutor.authorizeLocalSideEffect(
            command = "local_write_file",
            args = actionArgs,
            requestOwner = requestOwner,
            approvalCapability = args.optString("approvalCapability").takeIf { it.isNotBlank() }
        )?.let { error -> return JSONObject().put("ok", false).put("error", error) }
        val text = actionArgs.getString("text")
        if (text.isBlank()) {
            return JSONObject()
                .put("ok", false)
                .put("error", "Refusing to create an empty file. Provide non-empty text content.")
        }
        val file = resolveWorkspacePath(actionArgs.getString("path"))
        file.parentFile?.mkdirs()
        file.writeText(text)
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

    private suspend fun termuxCommand(args: JSONObject, requestOwner: String): JSONObject {
        val config = configProvider()
        if (!config.localDeveloperToolsEnabled) {
            return JSONObject().put("ok", false).put("error", "Termux-backed tools are disabled in Connection & Config.")
        }
        val actionArgs = args.withoutApprovalCapability()
        commandExecutor.authorizeLocalSideEffect(
            command = "termux_command",
            args = actionArgs,
            requestOwner = requestOwner,
            approvalCapability = args.optString("approvalCapability").takeIf { it.isNotBlank() }
        )?.let { error -> return JSONObject().put("ok", false).put("error", error) }
        val command = actionArgs.getString("command")
        val workdir = actionArgs.optString("workdir")
            .ifBlank { "/data/data/com.termux/files/home" }
        val timeoutMs = actionArgs.optLong("timeoutMs", 60_000L).coerceIn(1_000L, 300_000L)
        return termuxRunner.run(command, workdir, timeoutMs, requestOwner)
    }

    private fun JSONObject.withoutApprovalCapability(): JSONObject =
        JSONObject(toString()).apply { remove("approvalCapability") }

    private fun workspaceRoot(): File = File(context.filesDir, "local-workspace").apply { mkdirs() }

    private fun saveScreenshot(base64: String): String {
        val dir = File(context.filesDir, "local-screenshots").apply { mkdirs() }
        val file = File(dir, "screenshot-${System.currentTimeMillis()}.png")
        file.writeBytes(Base64.decode(base64, Base64.DEFAULT))
        return file.absolutePath
    }

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

    companion object {
        const val ANDROID_CONTROL_SKILL_NAME = "android-control"
    }

}
