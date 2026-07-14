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
    fun hidesThinkingUntilClosingTagAndStreamsVisibleText() {
        assertEquals("", LocalResponseTextNormalizer.visibleStreamingText("<think>planning"))
        assertEquals("Hel", LocalResponseTextNormalizer.visibleStreamingText("<think>planning</think>\nHel"))
        assertEquals("Hello", LocalResponseTextNormalizer.visibleStreamingText("Hello"))
        assertEquals("Hello", LocalResponseTextNormalizer.normalize("<think>planning</think>\nHello"))
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
            Here is a breakdown of what makes it so interesting:###1. The Quantum VacuumIn classical physics, a vacuum is empty space. "zero-point energy."###2. The Role of BoundariesWhen you place plates close together.*Outside the plates: Any wavelength.*Between the plates: Only fitting wavelengths. Key Takeaways:**

            It's a real force:** It has been experimentally verified.*It's incredibly weak: The force is extremely small.

            Applications and Future ResearchWhile the force itself is too weak, understanding it is crucial for:**

            Nanotechnology:** It influences nanoscale systems.*Quantum Computing: It is relevant to superconducting circuits.
            """.trimIndent()
        )

        assertTrue(normalized.contains("interesting:\n\n### 1. The Quantum Vacuum\n\nIn classical physics"))
        assertTrue(normalized.contains("energy.\"\n\n### 2. The Role of Boundaries\n\nWhen you place"))
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

    @Test
    fun repairsGluedNumberedKesslerListFromRenderedHistory() {
        val normalized = LocalResponseTextNormalizer.normalize(
            """
            Kessler Syndrome, also known as the Kessler-Snyder Syndrome, is a term used in the field of astrophysics and planetary science to describe a specific type of catastrophic event involving the debris field of a destroyed celestial body, such as an asteroid or comet. Here is a detailed explanation:

            What is Kessler Syndrome?In its most common context, Kessler Syndrome refers to a cascading chain reaction of collisions in a crowded orbital environment, such as Earth's low Earth orbit (LEO).1.*

            * The Initial Event:** A large object enters orbit.2.**The Collision:** This object collides with another object in orbit.3.**Debris Generation:** The collision shatters both objects.4.**The Cascade:** Smaller pieces travel at high velocities.5.**The Feedback Loop:** This process repeats exponentially.

            ### ImplicationsThe primary concern with Kessler Syndrome is orbital congestion.

            ### The "Kessler-Snyder" Context (Astrophysics)While the orbital debris context is the most common usage.

            ### SummaryIn short, Kessler Syndrome is a runaway chain reaction.
            """.trimIndent()
        )

        assertTrue(normalized.contains("Kessler Syndrome? In its most common context"))
        assertTrue(normalized.contains("\n\n1. **The Initial Event:** A large object enters orbit."))
        assertTrue(normalized.contains("\n\n2. **The Collision:** This object collides"))
        assertTrue(normalized.contains("\n\n3. **Debris Generation:** The collision shatters"))
        assertTrue(normalized.contains("\n\n4. **The Cascade:** Smaller pieces"))
        assertTrue(normalized.contains("\n\n5. **The Feedback Loop:** This process repeats"))
        assertTrue(normalized.contains("### Implications\n\nThe primary concern"))
        assertTrue(normalized.contains("### The \"Kessler-Snyder\" Context (Astrophysics)\n\nWhile"))
        assertTrue(normalized.contains("### Summary\n\nIn short"))
        assertFalse(normalized.contains("LEO).1."))
        assertFalse(normalized.contains("orbit.2."))
    }
}
