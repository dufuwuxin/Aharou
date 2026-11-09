package com.aharou.feature.settings.presentation

import com.aharou.feature.agent.domain.context.ContextCategory
import com.aharou.feature.agent.domain.context.ContextEntry
import com.aharou.feature.agent.domain.context.ContextKind
import com.aharou.feature.agent.domain.context.ContextLoad
import com.aharou.feature.agent.domain.memory.MemoryScope

internal fun contextParentFolder(path: String): String = path.substringBeforeLast('/', "")

internal fun contextChildFolders(paths: List<String>, parent: String): List<String> {
    val prefix = if (parent.isEmpty()) "" else "$parent/"
    return paths.asSequence().filter { it.isNotEmpty() && it.startsWith(prefix) && it != parent }
        .map { prefix + it.removePrefix(prefix).substringBefore('/') }.distinct().sorted().toList()
}

internal fun contextFolderEntries(entries: List<ContextEntry>, category: ContextCategory, folder: String): List<ContextEntry> =
    entries.filter { it.category == category && it.folder == folder }

internal fun defaultContextLoad(category: ContextCategory): ContextLoad = when (category) {
    ContextCategory.RULE, ContextCategory.ROLE_INJECTED, ContextCategory.MAIN_INJECTED, ContextCategory.TEMPORARY -> ContextLoad.FULL
    ContextCategory.LONG_TERM -> ContextLoad.SEARCH
}

internal data class ContextEditorDraft(
    val name: String,
    val description: String,
    val content: String,
    val category: ContextCategory,
    val folder: String,
    val scope: MemoryScope,
    val kind: ContextKind,
    val loading: ContextLoad,
    val sessionId: String?,
    val requestKey: String? = null,
) {
    fun changeCategory(next: ContextCategory): ContextEditorDraft = if (next == category) this else copy(
        category = next, folder = "", loading = defaultContextLoad(next),
        kind = if (next == ContextCategory.RULE) ContextKind.INSTRUCTION else ContextKind.REFERENCE,
        sessionId = if (next == ContextCategory.TEMPORARY) sessionId else null,
    )

    fun savedFields(): List<String> = listOf(name, description, content, category.name, folder, scope.name,
        kind.name, loading.name, sessionId.orEmpty(), requestKey.orEmpty())

    companion object {
        fun from(entry: ContextEntry?, category: ContextCategory, folder: String, hasWorkspace: Boolean): ContextEditorDraft =
            ContextEditorDraft(entry?.title.orEmpty(), entry?.description.orEmpty(), entry?.content.orEmpty(),
                entry?.category ?: category, entry?.folder ?: folder,
                entry?.scope ?: if (hasWorkspace) MemoryScope.PROJECT else MemoryScope.GLOBAL,
                entry?.kind ?: if (category == ContextCategory.RULE) ContextKind.INSTRUCTION else ContextKind.REFERENCE,
                entry?.loading ?: defaultContextLoad(category), entry?.sessionId, entry?.requestKey)

        fun restore(fields: List<String>): ContextEditorDraft = ContextEditorDraft(fields[0], fields[1], fields[2],
            requireNotNull(ContextCategory.from(fields[3])), fields[4], MemoryScope.valueOf(fields[5]),
            ContextKind.valueOf(fields[6]), ContextLoad.valueOf(fields[7]), fields[8].ifBlank { null }, fields.getOrNull(9)?.ifBlank { null })
    }
}

/** Search spans the whole library; browsing still only exposes the current folder. */
internal fun contextVisibleEntries(entries: List<ContextEntry>, query: String,
                                   category: ContextCategory, folder: String?,
                                   scope: MemoryScope?, loading: ContextLoad?): List<ContextEntry> =
    if (query.isNotBlank()) filterContextEntries(entries, query, scope, loading, null)
    else if (folder == null) emptyList()
    else filterContextEntries(contextFolderEntries(entries, category, folder), "", scope, loading, null)

internal fun contextSearchSnippet(entry: ContextEntry, query: String): String {
    val text = "${entry.description}\n${entry.content}".replace(Regex("\\s+"), " ").trim()
    val terms = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    val hit = terms.map { text.indexOf(it, ignoreCase = true) }.filter { it >= 0 }.minOrNull() ?: 0
    val start = (hit - 55).coerceAtLeast(0)
    val end = (start + 180).coerceAtMost(text.length)
    return (if (start > 0) "…" else "") + text.substring(start, end) + if (end < text.length) "…" else ""
}
