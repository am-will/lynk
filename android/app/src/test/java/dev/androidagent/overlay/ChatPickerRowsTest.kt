package dev.androidagent.overlay

import dev.androidagent.R
import dev.androidagent.chat.ChatHarnessModelGroup
import dev.androidagent.chat.ChatState
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatPickerRowsTest {
    @Test
    fun everyHarnessRowUsesItsBrandLogoWithoutTint() {
        val rows = ChatPickerRows.harnessRows(
            state = ChatState(),
            groups = listOf(
                ChatHarnessModelGroup("openclaw", "OpenClaw", emptyList()),
                ChatHarnessModelGroup("hermes", "Hermes", emptyList()),
                ChatHarnessModelGroup("codex", "Codex", emptyList()),
                ChatHarnessModelGroup("opencode", "OpenCode", emptyList()),
                ChatHarnessModelGroup("pi", "Pi", emptyList()),
                ChatHarnessModelGroup("devin", "Devin", emptyList())
            ),
            activeHarnessId = "devin",
            onSelectHarness = {}
        )

        assertEquals(
            listOf(
                R.drawable.openclaw_bubble_logo,
                R.drawable.hermes_nous_logo,
                R.drawable.codex_bubble_logo,
                R.drawable.opencode_logo_plate,
                R.drawable.pi_agent_logo_plate,
                R.drawable.devin_wiskers
            ),
            rows.map { it.iconRes }
        )
        assertEquals(List(rows.size) { false }, rows.map { it.tintIcon })
    }

    @Test
    fun activeDevinHarnessMenuRowUsesOfficialLogo() {
        val row = ChatPickerRows.harnessMenuRow(
            state = ChatState(),
            currentHarnessId = "devin",
            currentHarnessLabel = "Devin",
            onSelect = {}
        )

        assertEquals(R.drawable.devin_wiskers, row.iconRes)
        assertEquals(false, row.tintIcon)
    }
}
