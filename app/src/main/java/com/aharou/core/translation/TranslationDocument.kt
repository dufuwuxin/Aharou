package com.aharou.core.translation

internal data class TranslationPart(val text: String, val translate: Boolean)

internal object TranslationDocument {
    private val protected = Regex(
        "]\\([^\\r\\n)]*\\)|https?://[^\\s<>]+|[\\w.+-]+@[\\w.-]+\\.[A-Za-z]+|" +
            "\\{\\{[^}]*}}|\\$\\{[^}]*}|\\{[\\w.:-]+}|\\$[A-Za-z_]\\w*|%[0-9$]*[sdif]|<[^>\\r\\n]+>|" +
            "(?:[~./]|[A-Za-z]:[\\\\/])[^\\s]+|\\b[\\w.-]+(?:[/\\\\][\\w.-]+)+|" +
            "\\b[\\w-]+\\.[A-Za-z][\\w.-]*\\b|--[A-Za-z][\\w-]*|" +
            "\\b[A-Za-z]\\w*[_][\\w]+\\b|\\b[a-z]+[A-Z]\\w*\\b"
    )
    private val backticks = Regex("`+")
    private val english = Regex("[A-Za-z]+(?:[ '’\u002d]+[A-Za-z]+)*")

    fun parts(text: String): List<TranslationPart> {
        val result = mutableListOf<TranslationPart>()
        var fence: Char? = null
        var fenceLength = 0
        for (line in Regex("[^\\r\\n]*(?:\\r\\n|\\r|\\n|$)").findAll(text)) {
            val value = line.value
            if (value.isEmpty()) continue
            val trimmed = value.trimStart()
            val marker = trimmed.firstOrNull()
            val run = if (marker == '`' || marker == '~') trimmed.takeWhile { it == marker }.length else 0
            if (fence != null) {
                result += TranslationPart(value, false)
                if (marker == fence && run >= fenceLength && trimmed.drop(run).isBlank()) fence = null
                continue
            }
            if (run >= 3) {
                fence = marker
                fenceLength = run
                result += TranslationPart(value, false)
                continue
            }
            if (value.startsWith("    ") || value.startsWith('\t')) {
                result += TranslationPart(value, false)
                continue
            }
            var offset = 0
            while (offset < value.length) {
                val open = backticks.find(value, offset)
                if (open == null) {
                    inline(value.substring(offset), result)
                    break
                }
                inline(value.substring(offset, open.range.first), result)
                val close = backticks.findAll(value, open.range.last + 1).firstOrNull { it.value.length == open.value.length }
                val end = close?.range?.last?.plus(1) ?: value.length
                result += TranslationPart(value.substring(open.range.first, end), false)
                offset = end
            }
        }
        return result
    }

    private fun inline(value: String, result: MutableList<TranslationPart>) {
        var offset = 0
        for (match in protected.findAll(value)) {
            prose(value.substring(offset, match.range.first), result)
            result += TranslationPart(match.value, false)
            offset = match.range.last + 1
        }
        prose(value.substring(offset), result)
    }

    private fun prose(value: String, result: MutableList<TranslationPart>) {
        var offset = 0
        for (match in english.findAll(value)) {
            if (match.range.first > offset) result += TranslationPart(value.substring(offset, match.range.first), false)
            match.value.splitToSequence(' ').chunked(64).forEachIndexed { index, words ->
                if (index > 0) result += TranslationPart(" ", false)
                val chunk = words.joinToString(" ")
                result += TranslationPart(chunk, chunk.count { it.isLetter() } >= 2)
            }
            offset = match.range.last + 1
        }
        if (offset < value.length) result += TranslationPart(value.substring(offset), false)
    }
}
