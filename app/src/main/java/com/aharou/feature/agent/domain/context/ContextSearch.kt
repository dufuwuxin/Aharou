package com.aharou.feature.agent.domain.context

import java.util.Locale

object ContextSearch {
    private val whitespace = Regex("\\s+")

    private val queryToken = Regex("""tag:"[^"]*"|"[^"]*"|\S+""", RegexOption.IGNORE_CASE)

    fun tagQuery(query: String): Pair<List<String>, String> {
        val tokens = queryToken.findAll(query).map { it.value }.toList()
        val tags = tokens.filter { it.startsWith("tag:", true) }
            .map { it.substring(4).removeSurrounding("\"").lowercase(Locale.ROOT) }
        return tags to tokens.filterNot { it.startsWith("tag:", true) }.joinToString(" ")
    }

    fun matchesTags(tags: List<String>, required: List<String>): Boolean = required.all { requested ->
        tags.any { it.lowercase(Locale.ROOT) == requested }
    }

    fun explain(entry: ContextEntry): String = entry.worldbook.explanation()

    fun search(entries: List<ContextEntry>, query: String, includeSource: Boolean = false): List<ContextEntry> {
        val (requiredTags, text) = tagQuery(query)
        val candidates = if (requiredTags.isEmpty()) entries else entries.filter { matchesTags(it.worldbook.tags, requiredTags) }
        val normalized = text.trim().lowercase(Locale.ROOT)
        val terms = normalized.split(whitespace).filter(String::isNotBlank).distinct()
        if (terms.isEmpty()) return candidates
        return candidates.mapNotNull { entry ->
            val title = entry.title.lowercase(Locale.ROOT)
            val keywords = entry.keywords.map { it.lowercase(Locale.ROOT) }
            val description = entry.description.lowercase(Locale.ROOT)
            val content = entry.content.lowercase(Locale.ROOT)
            val relations = entry.worldbook.relations
            val tags = entry.worldbook.tags.map { it.lowercase(Locale.ROOT) }
            val source = if (includeSource) entry.sourceLabel.lowercase(Locale.ROOT) else ""
            var score = if (title == normalized) 64.0 else 0.0
            for (term in terms) {
                val match = maxOf(
                    if (term in title) 16.0 else 0.0,
                    if (keywords.any { term in it }) 8.0 * entry.worldbook.keywordWeight else 0.0,
                    if (tags.any { term in it }) 6.0 * entry.worldbook.tagWeight else 0.0,
                    if (term in description) 4.0 else 0.0,
                    if (term in content) 2.0 else 0.0,
                    relations.filter { term in it.target.lowercase(Locale.ROOT) }.maxOfOrNull { it.weight } ?: 0.0,
                    if (term in source) 1.0 else 0.0,
                )
                if (match == 0.0) return@mapNotNull null
                score += match
            }
            entry to score * (1.0 + (entry.worldbook.importance ?: 0) / 100.0)
        }.sortedByDescending { it.second }.map { it.first }
    }
}
