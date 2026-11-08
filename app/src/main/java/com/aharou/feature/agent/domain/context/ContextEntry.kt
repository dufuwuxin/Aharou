package com.aharou.feature.agent.domain.context

import com.aharou.feature.agent.domain.memory.MemoryScope
import java.security.MessageDigest

enum class ContextKind { INSTRUCTION, REFERENCE }
enum class ContextLoad { FULL, INDEX, SEARCH, OFF }
enum class ContextOrigin { MEMORY }
enum class ContextCategory {
    RULE, TEMPORARY, LONG_TERM, ROLE_INJECTED, MAIN_INJECTED;

    val isInjection: Boolean get() = this == ROLE_INJECTED || this == MAIN_INJECTED

    companion object {
        /** Old undifferentiated injection belongs to the main agent, never to every role. */
        fun from(raw: String?): ContextCategory? = when (val value = raw?.trim()?.uppercase()) {
            "INJECTED" -> MAIN_INJECTED
            else -> entries.firstOrNull { it.name == value }
        }
    }
}

data class ContextEntry(
    val id: String,
    val title: String,
    val description: String,
    val content: String,
    val scope: MemoryScope,
    val kind: ContextKind,
    val loading: ContextLoad,
    val origin: ContextOrigin,
    val sourceLabel: String,
    val version: String = "",
    val editable: Boolean = true,
    val pending: Boolean = false,
    val enabled: Boolean = true,
    val category: ContextCategory = ContextCategory.LONG_TERM,
    val folder: String = "",
    val sessionId: String? = null,
    val workspaceRoot: String? = null,
)

data class ContextPreview(
    val full: List<ContextEntry>,
    val indexed: List<ContextEntry>,
    val omitted: List<Pair<ContextEntry, String>>,
    val rendered: String,
    val renderedEntries: Map<String, String> = emptyMap()
)

internal object ContextFolderPath {
    fun valid(path: String): Boolean = path.isEmpty() || path.split('/').all { segment ->
        segment.isNotBlank() && segment.length <= 80 && segment == segment.trim() &&
            segment != "." && segment != ".." && segment.none { it.isISOControl() || it == '\\' }
    }
}

internal object ContextIdentity {
    fun id(origin: ContextOrigin, scope: MemoryScope, source: String): String =
        "${origin.name}:${scope.name}:$source"
    fun version(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
}
