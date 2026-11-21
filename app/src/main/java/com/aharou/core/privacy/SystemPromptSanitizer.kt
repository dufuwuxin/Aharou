package com.aharou.core.privacy

/** Explicitly scoped to system/developer text by its callers. */
object SystemPromptSanitizer {
    fun sanitize(text: String): String = text.filterNot {
        it == '\u200B' || it == '\u200C' || it == '\u200D' || it == '\u2060' || it == '\uFEFF'
    }

    private val thinkTag = Regex("</?think>")

    /** Incomplete tags remain untouched; native reasoning/signature fields are never passed here. */
    fun stripAssistantThink(text: String): String {
        var depth = 0
        var start = 0
        var cursor = 0
        var result: StringBuilder? = null
        for (match in thinkTag.findAll(text)) {
            if (match.value == "<think>") {
                if (depth++ == 0) start = match.range.first
            } else if (depth > 0 && --depth == 0) {
                val output = result ?: StringBuilder().also { result = it }
                output.append(text, cursor, start)
                cursor = match.range.last + 1
            }
        }
        return result?.append(text, cursor, text.length)?.toString() ?: text
    }
}
