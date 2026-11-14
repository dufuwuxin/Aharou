package com.aharou.core.translation

internal data class TranslationPart(val text: String, val translate: Boolean)

internal object TranslationDocument {
    private val protected = Regex(
        "\\]\\([^\\r\\n)]*\\)|https?://[^\\s<>]+|[\\w.+-]+@[\\w.-]+\\.[A-Za-z]+|" +
            "\\{\\{[^}]*\\}\\}|\\$\\{[^}]*\\}|\\{[\\w.:-]+\\}|\\$[A-Za-z_]\\w*|%[0-9$]*[sdif]|<[^>\\r\\n]+>|" +
            "(?:[~./]|[A-Za-z]:[\\\\/])[^\\s]+|\\b[\\w.-]+(?:[/\\\\][\\w.-]+)+|" +
            "\\b[\\w-]+\\.[A-Za-z][\\w.-]*\\b|--[A-Za-z][\\w-]*|" +
            "\\b[A-Za-z]\\w*[_][\\w]+\\b|\\b[a-z]+[A-Z]\\w*\\b"
    )
    private val machine = Regex("""\{"[^\r\n]*?\}|\["[^\r\n]*?\]|\bsk-[A-Za-z0-9_-]+|\b[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]+""")
    private val backticks = Regex("`+")
    private val chinese = Regex("[\u3400-\u9fff]+(?:[ ，。！？；：、…“”‘’（）0-9-]*[\u3400-\u9fff]+)*")
    private val sensitive = Regex("(?i)(?:authorization|bearer|password|api[_-]?key|token|secret|signature)\\s*[:= ]|-----BEGIN [A-Z ]*PRIVATE KEY-----")
    private val english = Regex("[A-Za-z]+(?:[ '’\u002d]+[A-Za-z]+)*")

    fun parts(text: String, target: String = "zh"): List<TranslationPart> {
        require(target == "zh" || target == "en")
        if (isStructuredTranslationPayload(text)) return listOf(TranslationPart(text, false))
        val result = mutableListOf<TranslationPart>()
        val jsonRegions = jsonRegions(text)
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
            if (jsonRegions.any { region -> line.range.first <= region.last && line.range.last >= region.first } || value.startsWith("    ") || value.startsWith('\t') || sensitive.containsMatchIn(value) ||
                trimmed.startsWith("{\"") || trimmed.startsWith("\"") || trimmed.startsWith("[\"")) {
                result += TranslationPart(value, false)
                continue
            }
            var offset = 0
            while (offset < value.length) {
                val open = backticks.find(value, offset)
                if (open == null) {
                    inline(value.substring(offset), result, target)
                    break
                }
                inline(value.substring(offset, open.range.first), result, target)
                val close = backticks.findAll(value, open.range.last + 1).firstOrNull { it.value.length == open.value.length }
                val end = close?.range?.last?.plus(1) ?: value.length
                result += TranslationPart(value.substring(open.range.first, end), false)
                offset = end
            }
        }
        return result
    }

    private fun jsonRegions(text: String): List<IntRange> {
        val regions = mutableListOf<IntRange>()
        var at = 0
        while (at < text.length) {
            val open = text[at]
            if (open != '{' && open != '[') { at++; continue }
            if (open == '{' && (text.getOrNull(at - 1) == '{' || text.getOrNull(at + 1) == '{')) { at++; continue }
            var nextAt = at + 1
            while (nextAt < text.length && text[nextAt].isWhitespace()) nextAt++
            val next = text.getOrNull(nextAt)
            val candidate = if (open == '{') next == '"' || next == '}' else next != null && (next in "\"{[tf n]-0123456789" && next != ' ')
            if (!candidate) { at++; continue }
            val stack = ArrayDeque<Char>()
            var quoted = false
            var escaped = false
            var end = at
            while (end < text.length) {
                val char = text[end]
                if (quoted) {
                    if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
                } else when (char) {
                    '"' -> quoted = true
                    '{', '[' -> stack.addLast(char)
                    '}', ']' -> {
                        if (stack.isEmpty() || (char == '}' && stack.last() != '{') || (char == ']' && stack.last() != '[')) break
                        stack.removeLast()
                        if (stack.isEmpty()) { end++; break }
                    }
                }
                end++
            }
            if (end > at && (stack.isNotEmpty() || runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text.substring(at, end)) }.isSuccess)) {
                regions += at until end
                at = end
            } else at++
        }
        return regions
    }

    private fun inline(value: String, result: MutableList<TranslationPart>, target: String) {
        var offset = 0
        for (match in Regex("${machine.pattern}|${protected.pattern}").findAll(value)) {
            prose(value.substring(offset, match.range.first), result, target)
            result += TranslationPart(match.value, false)
            offset = match.range.last + 1
        }
        prose(value.substring(offset), result, target)
    }

    private fun prose(value: String, result: MutableList<TranslationPart>, target: String) {
        var offset = 0
        for (match in (if (target == "en") chinese else english).findAll(value)) {
            if (match.range.first > offset) result += TranslationPart(value.substring(offset, match.range.first), false)
            match.value.splitToSequence(' ').chunked(64).forEachIndexed { index, words ->
                if (index > 0) result += TranslationPart(" ", false)
                val chunk = words.joinToString(" ")
                chunk.chunked(if (target == "en") 192 else 512).forEach { result += TranslationPart(it, it.count { char -> char.isLetter() } >= (if (target == "en") 1 else 2)) }
            }
            offset = match.range.last + 1
        }
        if (offset < value.length) result += TranslationPart(value.substring(offset), false)
    }
}
