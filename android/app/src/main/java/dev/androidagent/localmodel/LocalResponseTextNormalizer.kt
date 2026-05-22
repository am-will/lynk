package dev.androidagent.localmodel

object LocalResponseTextNormalizer {
    fun normalize(text: String): String {
        return text.trim()
            .replace(Regex("""(?i)^TASK_COMPLETE\s*:?\s*"""), "")
            .replace(Regex("""(?i)^BLOCKED\s*:?\s*"""), "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("""(?<=[a-z\)])\.(?=[A-Z])"""), ". ")
            .replace(Regex("""(?<=[.!?])\s*(#{1,6}\s+)"""), "\n\n$1")
            .replace(Regex("""(?m)^(#{1,6}[^\n]*?)\*(?=[A-Z])"""), "$1\n\n* ")
            .replace(Regex("""(?<=[A-Za-z\)])\s*(?=\d+\.[A-Z])"""), "\n")
            .replace(Regex("""(?<=[.!?])\s*(?=\d+\.[A-Z])"""), "\n")
            .replace(Regex("""(?m)^(\d+\.)(?=\S)"""), "$1 ")
            .replace(Regex("""(?<=[.!?])\s*\*(?=[A-Z])"""), "\n\n* ")
            .replace(Regex("""(?m)^\*\s*(?=\S)"""), "* ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
            .ifBlank { "Done." }
    }
}
