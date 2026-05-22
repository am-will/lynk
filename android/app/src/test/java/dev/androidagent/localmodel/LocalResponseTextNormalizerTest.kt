package dev.androidagent.localmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalResponseTextNormalizerTest {
    @Test
    fun separatesGluedSentencesHeadingsAndNumberedLists() {
        val normalized = LocalResponseTextNormalizer.normalize(
            "Vacuum energy.In classical physics, space fluctuates.### How it Works1.Vacuum fluctuations happen.2.Boundaries restrict modes."
        )

        assertTrue(normalized.contains("energy. In classical"))
        assertTrue(normalized.contains("\n\n### How it Works\n1. Vacuum"))
        assertTrue(normalized.contains("\n2. Boundaries"))
        assertFalse(normalized.contains("Works1."))
    }

    @Test
    fun repairsGluedBulletMarkdown() {
        val normalized = LocalResponseTextNormalizer.normalize(
            "### Key Takeaways*It's a Force: measurable.*It's Weak: tiny."
        )

        assertEquals(
            """
            ### Key Takeaways

            * It's a Force: measurable.

            * It's Weak: tiny.
            """.trimIndent(),
            normalized
        )
    }

    @Test
    fun removesLocalCompletionPrefixes() {
        assertEquals("All set.", LocalResponseTextNormalizer.normalize("TASK_COMPLETE: All set."))
        assertEquals("Need permission.", LocalResponseTextNormalizer.normalize("BLOCKED Need permission."))
    }

    @Test
    fun repairsLiteRtCasimirFormattingFromRenderedHistory() {
        val normalized = LocalResponseTextNormalizer.normalize(
            """
            Here is a breakdown of what makes it so interesting:###1. The Quantum VacuumIn classical physics, a vacuum is empty space.###2. The Role of BoundariesWhen you place plates close together.*Outside the plates: Any wavelength.*Between the plates: Only fitting wavelengths. Key Takeaways:**

            It's a real force:** It has been experimentally verified.*It's incredibly weak: The force is extremely small.

            Applications and Future ResearchWhile the force itself is too weak, understanding it is crucial for:**

            Nanotechnology:** It influences nanoscale systems.*Quantum Computing: It is relevant to superconducting circuits.
            """.trimIndent()
        )

        assertTrue(normalized.contains("interesting:\n\n### 1. The Quantum Vacuum\n\nIn classical physics"))
        assertTrue(normalized.contains("### 2. The Role of Boundaries\n\nWhen you place"))
        assertTrue(normalized.contains("\n\n* Outside the plates: Any wavelength."))
        assertTrue(normalized.contains("\n\n* Between the plates: Only fitting wavelengths."))
        assertTrue(normalized.contains("### Key Takeaways"))
        assertTrue(normalized.contains("**It's a real force:** It has been experimentally verified."))
        assertTrue(normalized.contains("\n\n* It's incredibly weak: The force is extremely small."))
        assertTrue(normalized.contains("understanding it is crucial for:"))
        assertTrue(normalized.contains("**Nanotechnology:** It influences nanoscale systems."))
        assertTrue(normalized.contains("\n\n* Quantum Computing: It is relevant"))
    }
}
