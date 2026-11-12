package com.aharou.feature.settings.presentation.component

import androidx.compose.runtime.saveable.Saver
import com.aharou.feature.agent.domain.context.ContextCategory
import com.aharou.feature.agent.domain.context.ContextEntry
import com.aharou.feature.agent.domain.context.ContextKind
import com.aharou.feature.agent.domain.context.ContextLoad
import com.aharou.feature.agent.domain.context.ContextOrigin
import com.aharou.feature.agent.domain.context.WorldbookRules
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.settings.presentation.ContextEditorDraft

internal val ContextEntryStateSaver = Saver<ContextEntry?, ArrayList<String>>(
    save = { entry -> entry?.let {
        arrayListOf("context-entry-v2", it.id, it.title, it.description, it.content, it.scope.name, it.kind.name, it.loading.name,
            it.origin.name, it.sourceLabel, it.version, it.editable.toString(), it.pending.toString(),
            it.enabled.toString(), it.category.name, it.folder, it.sessionId.orEmpty(), it.workspaceRoot.orEmpty(), it.requestKey.orEmpty(), it.worldbook.toJson()).apply { addAll(it.keywords) }
    } ?: arrayListOf() },
    restore = { saved -> if (saved.isEmpty()) null else runCatching {
        val current = saved[0] == "context-entry-v2"
        val value = if (current) saved.drop(1) else saved
        ContextEntry(value[0], value[1], value[2], value[3], MemoryScope.valueOf(value[4]),
            ContextKind.valueOf(value[5]), ContextLoad.valueOf(value[6]), ContextOrigin.valueOf(value[7]),
            value[8], value[9], value[10].toBoolean(), value[11].toBoolean(), value[12].toBoolean(),
            requireNotNull(ContextCategory.from(value[13])), value[14], value[15].ifBlank { null }, value[16].ifBlank { null }, value.getOrNull(17)?.ifBlank { null }, keywords = value.drop(if (current) 19 else 18),
            worldbook = if (current) WorldbookRules.fromJson(value[18]) else WorldbookRules())
    }.getOrNull() },
)

internal val ContextEditorDraftSaver = Saver<ContextEditorDraft, ArrayList<String>>(
    save = { ArrayList(it.savedFields()) },
    restore = { runCatching { ContextEditorDraft.restore(it) }.getOrNull() },
)
