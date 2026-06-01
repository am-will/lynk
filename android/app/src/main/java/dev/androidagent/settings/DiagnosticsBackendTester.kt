package dev.androidagent.settings

import android.content.Context
import dev.androidagent.AgentConfig
import dev.androidagent.AgentConfigStore
import dev.androidagent.localmodel.LocalModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class DiagnosticsBackendId(val harnessId: String, val label: String) {
    OpenClaw("openclaw", "OpenClaw"),
    Hermes("hermes", "Hermes"),
    Codex("codex", "Codex"),
    OpenCode("opencode", "OpenCode"),
    Pi("pi", "Pi"),
    Local("local", "Local")
}

data class DiagnosticsBackendTestResult(
    val backend: DiagnosticsBackendId,
    val ok: Boolean,
    val level: DiagnosticsEventLevel,
    val title: String,
    val message: String
)

object DiagnosticsBackendTester {
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    suspend fun test(context: Context, backend: DiagnosticsBackendId): DiagnosticsBackendTestResult =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val config = AgentConfigStore.load(appContext)
            if (backend == DiagnosticsBackendId.Local) {
                testLocal(config)
            } else {
                testHost(config, backend)
            }
        }

    private fun testLocal(config: AgentConfig): DiagnosticsBackendTestResult {
        if (!config.experimentalLocalModelsEnabled) {
            return warning(DiagnosticsBackendId.Local, "Local LiteRT-LM is disabled in Models & Harness.")
        }
        if (!LocalModelStore.exists(config.localModelPath)) {
            return warning(DiagnosticsBackendId.Local, "Local LiteRT-LM is enabled, but no .litertlm model is imported.")
        }
        return success(DiagnosticsBackendId.Local, "Local LiteRT-LM is ready on ${config.localModelBackend.label}.")
    }

    private fun testHost(config: AgentConfig, backend: DiagnosticsBackendId): DiagnosticsBackendTestResult {
        if (!isHarnessEnabled(config, backend)) {
            return warning(backend, "${backend.label} is disabled in Models & Harness.")
        }
        val httpBase = deriveHttpBase(config.hostUrl) ?: return warning(
            backend,
            "Bridge URL is missing. Configure the host URL in Connection settings."
        )
        val token = config.token.trim()
        if (token.isEmpty()) {
            return warning(
                backend,
                "Bridge auth token is missing. Paste the PC PHONE_AGENT_TOKEN in Connection settings."
            )
        }

        return try {
            val request = Request.Builder()
                .url("$httpBase/api/harnesses/readiness")
                .header("Authorization", "Bearer $token")
                .build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (response.code == 404) {
                        return@use warning(
                            backend,
                            "Live model metadata has not loaded for ${backend.label} yet. Open Chat once, then test again."
                        )
                    }
                    return@use error(
                        backend,
                        if (response.code == 401) {
                            "Bridge rejected the saved token. Re-pair or update PHONE_AGENT_TOKEN."
                        } else {
                            "GET /api/harnesses/readiness responded ${response.code}."
                        }
                    )
                }
                val payload = try {
                    JSONObject(body)
                } catch (parseError: Throwable) {
                    return@use error(backend, "Bridge returned invalid harness readiness JSON: ${parseError.message}")
                }
                parseHarnessReadiness(backend, payload)
            }
        } catch (io: IOException) {
            warning(backend, "Live model metadata has not loaded for ${backend.label} yet. Bridge probe failed at $httpBase: ${io.message}")
        } catch (throwable: Throwable) {
            error(backend, throwable.message ?: "Backend test failed.")
        }
    }

    internal fun deriveHttpBase(hostUrl: String): String? {
        val trimmed = hostUrl.trim()
        if (trimmed.isEmpty()) return null
        val withHttpScheme = when {
            trimmed.startsWith("wss://", ignoreCase = true) -> "https://" + trimmed.substring(6)
            trimmed.startsWith("ws://", ignoreCase = true) -> "http://" + trimmed.substring(5)
            trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            else -> "http://$trimmed"
        }
        val pathStart = withHttpScheme.indexOf('/', withHttpScheme.indexOf("://") + 3)
        return if (pathStart < 0) withHttpScheme.trimEnd('/') else withHttpScheme.substring(0, pathStart).trimEnd('/')
    }

    internal fun parseHarnessReadiness(
        backend: DiagnosticsBackendId,
        payload: JSONObject
    ): DiagnosticsBackendTestResult {
        val harnesses = payload.optJSONObject("harnesses") ?: return error(
            backend,
            "Bridge response did not include backend readiness details."
        )
        val harness = harnesses.optJSONObject(backend.harnessId) ?: return warning(
            backend,
            "${backend.label} is not configured on the PC bridge."
        )
        if (harness.optBoolean("ok", false)) {
            val modelCount = harness.optInt("modelCount", 0)
            val suffix = if (modelCount > 0) " ($modelCount models available)" else ""
            return success(backend, "${backend.label} backend is ready$suffix.")
        }
        val detail = firstMeaningfulString(harness, listOf("error", "message", "reason", "detail"))
        if (!harness.optBoolean("configured", true)) {
            return warning(
                backend,
                detail ?: "${backend.label} is not configured on the PC bridge."
            )
        }
        if (harness.optInt("modelCount", 0) <= 0) {
            return warning(
                backend,
                detail ?: "${backend.label} is configured, but no live models are available yet."
            )
        }
        return error(
            backend,
            detail ?: "${backend.label} backend reported not ready."
        )
    }

    private fun isHarnessEnabled(config: AgentConfig, backend: DiagnosticsBackendId): Boolean =
        when (backend) {
            DiagnosticsBackendId.OpenClaw -> config.openClawHarnessEnabled
            DiagnosticsBackendId.Hermes -> config.hermesHarnessEnabled
            DiagnosticsBackendId.Codex -> config.codexHarnessEnabled
            DiagnosticsBackendId.OpenCode -> config.opencodeHarnessEnabled
            DiagnosticsBackendId.Pi -> config.piHarnessEnabled
            DiagnosticsBackendId.Local -> config.experimentalLocalModelsEnabled
        }

    private fun firstMeaningfulString(value: Any?, keys: List<String>): String? {
        return when (value) {
            is JSONObject -> {
                for (key in keys) {
                    val candidate = value.optString(key).trim()
                    if (candidate.isNotEmpty()) return candidate
                }
                val names = value.keys()
                while (names.hasNext()) {
                    val nested = firstMeaningfulString(value.opt(names.next()), keys)
                    if (nested != null) return nested
                }
                null
            }
            is String -> value.trim().takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    private fun success(backend: DiagnosticsBackendId, message: String): DiagnosticsBackendTestResult =
        DiagnosticsBackendTestResult(backend, ok = true, DiagnosticsEventLevel.Success, "${backend.label} Ready", message)

    private fun warning(backend: DiagnosticsBackendId, message: String): DiagnosticsBackendTestResult =
        DiagnosticsBackendTestResult(backend, ok = false, DiagnosticsEventLevel.Warning, "${backend.label} Needs Setup", message)

    private fun error(backend: DiagnosticsBackendId, message: String): DiagnosticsBackendTestResult =
        DiagnosticsBackendTestResult(backend, ok = false, DiagnosticsEventLevel.Error, "${backend.label} Test Failed", message)
}
