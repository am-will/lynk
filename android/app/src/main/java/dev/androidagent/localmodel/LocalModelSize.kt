package dev.androidagent.localmodel

// Parses param count from the display name; file size isn't reliable across quant schemes.
internal object LocalModelSize {
    private val PARAM_COUNT = Regex("""(\d+(?:\.\d+)?)\s*[Bb](?:\b|-)""")

    // Below this, the tool-call JSON protocol degenerates into non-answers.
    private const val TINY_THRESHOLD_BILLIONS = 2.0

    fun isTiny(displayName: String): Boolean {
        val billions = PARAM_COUNT.find(displayName)?.groupValues?.get(1)?.toDoubleOrNull() ?: return false
        return billions < TINY_THRESHOLD_BILLIONS
    }
}
