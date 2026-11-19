package com.aharou.feature.agent.domain.context


/** Keep admitted user instructions authoritative; catalogs and retrieved facts travel as request data. */
data class ContextPromptParts(val system: String, val data: String)

internal object ContextPromptPartitioner {
    fun partition(preview: ContextPreview, originals: Map<String, ContextEntry>,
        stableExpansion: (String) -> String): ContextPromptParts {
        val system = mutableListOf<String>()
        val data = mutableListOf<String>()
        val full = preview.full.associateBy { it.id }
        for ((id, block) in preview.renderedEntries) {
            val admitted = full[id]
            val original = originals[id]
            if (admitted == null || admitted.kind == ContextKind.REFERENCE || admitted.pending) {
                data += block
                continue
            }
            if (original == null || !original.content.contains("{{AICODE_")) {
                system += block
                continue
            }
            val stable = ContextLibraryPlanner.safe(stableExpansion(original.content))
            // One bounded copy: a dynamically expanded catalog travels as request data,
            // never duplicated into the cached system prefix.
            if (stable.isNotBlank() && stable == ContextLibraryPlanner.safe(admitted.content)) system += block
            else data += block
        }
        return ContextPromptParts(system.joinToString("\n\n"), data.joinToString("\n\n"))
    }
}
