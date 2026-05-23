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
    fun parseHarnessReadinessReportsReadyHarness() {
        val result = DiagnosticsBackendTester.parseHarnessReadiness(
            DiagnosticsBackendId.Codex,
            JSONObject("""{"harnesses":{"codex":{"ok":true,"configured":true,"modelCount":5}}}""")
        )

        assertTrue(result.ok)
        assertEquals(DiagnosticsEventLevel.Success, result.level)
        assertEquals("Codex backend is ready (5 models available).", result.message)
    }

    @Test
    fun parseHarnessReadinessReturnsConcreteFailure() {
        val result = DiagnosticsBackendTester.parseHarnessReadiness(
            DiagnosticsBackendId.Hermes,
            JSONObject("""{"harnesses":{"hermes":{"ok":false,"configured":false,"message":"Hermes is not configured on the PC bridge."}}}""")
        )

        assertFalse(result.ok)
        assertEquals(DiagnosticsEventLevel.Error, result.level)
        assertEquals("Hermes is not configured on the PC bridge.", result.message)
    }

    @Test
    fun parseHarnessReadinessExplainsMissingHarness() {
        val result = DiagnosticsBackendTester.parseHarnessReadiness(
            DiagnosticsBackendId.Hermes,
            JSONObject("""{"harnesses":{"openclaw":{"ok":true}}}""")
        )

        assertFalse(result.ok)
        assertEquals(DiagnosticsEventLevel.Warning, result.level)
        assertEquals("Hermes is not configured on the PC bridge.", result.message)
    }
}
