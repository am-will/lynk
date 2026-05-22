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
}
