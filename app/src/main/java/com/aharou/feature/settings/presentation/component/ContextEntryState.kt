package com.aharou.feature.settings.presentation.component

import androidx.compose.runtime.saveable.Saver
import com.aharou.feature.agent.domain.context.ContextCategory
import com.aharou.feature.agent.domain.context.ContextEntry
import com.aharou.feature.agent.domain.context.ContextKind
import com.aharou.feature.agent.domain.context.ContextLoad
import com.aharou.feature.agent.domain.context.ContextOrigin
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.settings.presentation.ContextEditorDraft

internal val ContextEntryStateSaver = Saver<ContextEntry?, ArrayList<String>>(
    save = { entry -> entry?.let {
        arrayListOf(it.id, it.title, it.description, it.content, it.scope.name, it.kind.name, it.loading.name,
            it.origin.name, it.sourceLabel, it.version, it.editable.toString(), it.pending.toString(),
            it.enabled.toString(), it.category.name, it.folder, it.sessionId.orEmpty(), it.workspaceRoot.orEmpty())
    } ?: arrayListOf() },
    restore = { value -> if (value.isEmpty()) null else runCatching {
        ContextEntry(value[0], value[1], value[2], value[3], MemoryScope.valueOf(value[4]),
            ContextKind.valueOf(value[5]), ContextLoad.valueOf(value[6]), ContextOrigin.valueOf(value[7]),
            value[8], value[9], value[10].toBoolean(), value[11].toBoolean(), value[12].toBoolean(),
            requireNotNull(ContextCategory.from(value[13])), value[14], value[15].ifBlank { null }, value[16].ifBlank { null })
    }.getOrNull() },
)

internal val ContextEditorDraftSaver = Saver<ContextEditorDraft, ArrayList<String>>(
    save = { ArrayList(it.savedFields()) },
    restore = { runCatching { ContextEditorDraft.restore(it) }.getOrNull() },
)
