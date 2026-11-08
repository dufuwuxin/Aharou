package com.aharou.feature.agent.domain.context

import com.aharou.core.util.SecretMasker
import com.aharou.core.util.TextSanitizer

object ContextLibraryPlanner {
    const val FULL_ENTRY_CHARS = 900
    const val FULL_TOTAL_CHARS = 3000
    const val INDEX_TOTAL_CHARS = 6000

    internal fun safe(text: String): String = SecretMasker.mask(TextSanitizer.sanitize(text))

    fun plan(entries: List<ContextEntry>, sessionId: String? = null, onlyOrigin: ContextOrigin? = null,
             injectionCategory: ContextCategory = ContextCategory.MAIN_INJECTED,
             memoryAllowed: Boolean = true): ContextPreview {
        val full = mutableListOf<ContextEntry>()
        val indexed = mutableListOf<ContextEntry>()
        val omitted = mutableListOf<Pair<ContextEntry, String>>()
        val output = mutableListOf<String>()
        val renderedEntries = linkedMapOf<String, String>()
        var fullChars = 0
        var indexChars = 0
        for (entry in entries.distinctBy { it.id }) {
            // Filter before charging budgets: another audience must not crowd out this one.
            if (entry.category.isInjection && entry.category != injectionCategory) {
                omitted += entry to "different injection audience"
                continue
            }
            if (!entry.category.isInjection && !memoryAllowed) {
                omitted += entry to "ordinary memory disabled"
                continue
            }
            if (entry.category == ContextCategory.TEMPORARY && (sessionId.isNullOrBlank() || entry.sessionId != sessionId)) {
                omitted += entry to "temporary: different or missing session"
                continue
            }
            if (!entry.enabled || entry.loading == ContextLoad.OFF) {
                omitted += entry to "off or unavailable"
                continue
            }
            if (entry.loading == ContextLoad.SEARCH) {
                omitted += entry to "search only: explicit retrieval required"
                continue
            }
            val header = safe("[context ${entry.kind.name} ${entry.id}] ${entry.title}")
            val body = safe(entry.content)
            val block = "$header\n$body"
            if (entry.loading == ContextLoad.FULL && !entry.pending && body.length <= FULL_ENTRY_CHARS && fullChars + block.length <= FULL_TOTAL_CHARS) {
                full += entry
                if (onlyOrigin == null || entry.origin == onlyOrigin) {
                    output += block
                    renderedEntries[entry.id] = block
                }
                fullChars += block.length
            } else {
                if (entry.loading == ContextLoad.FULL) {
                    omitted += entry to if (entry.pending) "pending: body not inlined; index fallback" else "full budget exceeded: whole-entry index fallback"
                }
                val index = "$header\n${safe(entry.description).take(240)}${if (entry.pending) " [pending: unconfirmed]" else ""}"
                if (indexChars + index.length <= INDEX_TOTAL_CHARS) {
                    indexed += entry
                    if (onlyOrigin == null || entry.origin == onlyOrigin) {
                        output += index
                        renderedEntries[entry.id] = index
                    }
                    indexChars += index.length
                } else omitted += entry to "index budget exceeded"
            }
        }
        return ContextPreview(full.filter { onlyOrigin == null || it.origin == onlyOrigin }, indexed.filter { onlyOrigin == null || it.origin == onlyOrigin }, omitted.filter { onlyOrigin == null || it.first.origin == onlyOrigin }, output.joinToString("\n\n"), renderedEntries)
    }
}
