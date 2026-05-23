package dev.androidagent.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsBackendTesterTest {
    @Test
    fun deriveHttpBaseConvertsWebSocketUrlToBridgeRoot() {
        assertEquals(
            "http://192.168.1.10:8788",
            DiagnosticsBackendTester.deriveHttpBase("ws://192.168.1.10:8788/phone")
        )
        assertEquals(
            "https://bridge.example.com",
            DiagnosticsBackendTester.deriveHttpBase("wss://bridge.example.com/phone")
        )
    }

    @Test
    fun parseHarnessHealthReportsReadyHarness() {
        val result = DiagnosticsBackendTester.parseHarnessHealth(
            DiagnosticsBackendId.Codex,
            JSONObject("""{"harnesses":{"codex":{"ok":true,"active":false}}}""")
        )

        assertTrue(result.ok)
        assertEquals(DiagnosticsEventLevel.Success, result.level)
        assertEquals("Codex backend is ready.", result.message)
    }

    @Test
    fun parseHarnessHealthReturnsConcreteFailure() {
        val result = DiagnosticsBackendTester.parseHarnessHealth(
            DiagnosticsBackendId.Hermes,
            JSONObject("""{"harnesses":{"hermes":{"ok":false,"error":"missing HERMES_API_KEY"}}}""")
        )

        assertFalse(result.ok)
        assertEquals(DiagnosticsEventLevel.Error, result.level)
        assertEquals("missing HERMES_API_KEY", result.message)
    }

    @Test
    fun parseHarnessHealthExplainsMissingHarness() {
        val result = DiagnosticsBackendTester.parseHarnessHealth(
            DiagnosticsBackendId.Hermes,
            JSONObject("""{"harnesses":{"openclaw":{"ok":true}}}""")
        )

        assertFalse(result.ok)
        assertEquals(DiagnosticsEventLevel.Warning, result.level)
        assertEquals("Hermes is not configured on the PC bridge.", result.message)
    }
}
