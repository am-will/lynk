package dev.androidagent.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatModelCatalogDevinRegressionTest {
    @Test
    fun devinPrefixesDriveHarnessSelectionWithoutWireHarnessMetadata() {
        assertEquals("devin", ChatModelCatalog.harnessForModel("devin:default"))
        assertEquals("devin", ChatModelCatalog.harnessFromSessionKey("devin:opaque-session-id"))
        assertEquals("devin", ChatModelCatalog.normalizeHarnessId(" DeViN "))
    }

    @Test
    fun unqualifiedIncomingModelIsNamespacedForActiveDevinHarness() {
        assertEquals(
            "devin:default",
            ChatModelCatalog.selectedModelForActiveHarness(
                current = "codex:gpt-5.3-codex",
                incoming = " default ",
                activeHarnessId = "devin"
            )
        )
        assertEquals(
            "devin:anthropic/claude-sonnet-4-5",
            ChatModelCatalog.normalizeModelForHarness(
                model = "devin:anthropic/claude-sonnet-4-5",
                harnessId = "devin"
            )
        )
    }
}
