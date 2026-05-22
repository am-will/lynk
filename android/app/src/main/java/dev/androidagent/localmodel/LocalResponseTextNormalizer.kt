package dev.androidagent.localmodel

object LocalResponseTextNormalizer {
    fun normalize(text: String): String {
        return text.trim()
            .replace(Regex("""(?i)^TASK_COMPLETE\s*:?\s*"""), "")
            .replace(Regex("""(?i)^BLOCKED\s*:?\s*"""), "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("""(?<=[a-z\)])\.(?=[A-Z])"""), ". ")
            .replace(Regex("""([.!?:]["”']?)\s*(#{1,6})(?!#)\s*"""), "$1\n\n$2 ")
            .replace(Regex("""(?m)^(#{1,6}\s+\d+\.\s+[A-Z][A-Za-z ]+?)(?=(In|When|Because|While|The|This|It)\b)"""), "$1\n\n")
            .replace(Regex("""(?m)^([A-Z][A-Za-z ]+?)(?=(In|When|Because|While|The|This|It)\b)"""), "$1\n\n")
            .replace(Regex("""(?<=[.!?])\s+([A-Z][A-Za-z ]+):\*\*"""), "\n\n### $1")
            .replace(Regex("""(?m)^([A-Z][A-Za-z ]+):\*\*\s*$"""), "### $1")
            .replace(Regex("""(?m):\*\*\s*$"""), ":")
            .replace(Regex("""(?m)^(\s*)([^*\n][^:\n]{2,80}:)\*\*(?=\s)"""), "$1**$2**")
            .replace(Regex(""":\n(?=\*\*[A-Z])"""), ":\n\n")
            .replace(Regex("""(?m)^(#{1,6}[^\n]*?)\*(?=[A-Z])"""), "$1\n\n* ")
            .replace(Regex("""(?<=[A-Za-z\)])\s*(?=\d+\.[A-Z])"""), "\n")
            .replace(Regex("""(?<=[.!?])\s*(?=\d+\.[A-Z])"""), "\n")
            .replace(Regex("""(?m)^(\d+\.)(?=\S)"""), "$1 ")
            .replace(Regex("""([.!?:]["”']?)\s*(?<!\*)\*(?!\*)(?=[A-Z])"""), "$1\n\n* ")
            .replace(Regex("""(?m)^\*(?!\*)\s*(?=\S)"""), "* ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
            .ifBlank { "Done." }
    }
}
