package com.aharou.feature.agent.domain.context

import com.aharou.feature.agent.domain.memory.Memory
import com.aharou.feature.agent.domain.memory.MemoryFileStorage
import com.aharou.feature.agent.domain.memory.MemoryRepository
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.agent.domain.memory.MemorySource
import com.aharou.feature.agent.domain.memory.MemoryWriteResult
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.subagent.InjectPart
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContextLibraryRepository @Inject constructor(
    private val memories: MemoryRepository,
) {
    private class Snapshot(
        val root: String?, val sessionId: String?, val memoryAllowed: Boolean, val ghost: Boolean,
    ) {
        var entries: List<ContextEntry>? = null
        var plan: ContextPreview? = null
    }
    private val snapshot = ThreadLocal<Snapshot?>()

    fun withPromptSnapshot(ctx: AgentContext, ghost: Boolean, block: () -> String): String {
        val previous = snapshot.get()
        snapshot.set(Snapshot(ctx.projectRoot.takeIf { it.isNotBlank() }, ctx.sessionId,
            ctx.agentDefinition?.let { InjectPart.MEMORY in it.inject } ?: true, ghost))
        return try { block() } finally { if (previous == null) snapshot.remove() else snapshot.set(previous) }
    }

    private fun memoryEntry(m: Memory, projectRoot: String?) = ContextEntry(
        ContextIdentity.id(ContextOrigin.MEMORY, m.scope, if (m.scope == MemoryScope.PROJECT) "$projectRoot/${m.name}" else m.name), m.name, m.description, m.content,
        m.scope, m.contextKind, m.contextLoad, ContextOrigin.MEMORY, m.file?.absolutePath ?: m.name,
        m.version, pending = m.pending, enabled = m.expiresAt?.let { it > System.currentTimeMillis() } ?: true,
        category = m.contextCategory, folder = m.contextFolder, sessionId = m.contextSessionId,
        workspaceRoot = if (m.scope == MemoryScope.PROJECT) projectRoot else null,
    )

    internal fun sources(projectRoot: String?): List<ContextEntry> = buildList {
        addAll(memories.listMemoriesInScope(MemoryScope.GLOBAL, projectRoot))
        if (!projectRoot.isNullOrBlank()) addAll(memories.listMemoriesInScope(MemoryScope.PROJECT, projectRoot))
    }.filterNot { memory ->
        !memory.hasContextMetadata && listOf("SOUL", "CORE", "GLOBAL").any { it.equals(memory.name, true) }
    }.map { memoryEntry(it, projectRoot) }

    fun list(projectRoot: String?, sessionId: String? = null): List<ContextEntry> {
        val active = snapshot.get()?.takeIf { it.root == projectRoot }
        val entries = active?.entries ?: sources(projectRoot).also { active?.entries = it }
        return entries.filter { sessionId == null || it.category != ContextCategory.TEMPORARY || it.sessionId == sessionId }
    }

    fun search(query: String, projectRoot: String?): List<ContextEntry> {
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return emptyList()
        return list(projectRoot).filter { e -> terms.all { term -> "${e.title}\n${e.description}\n${e.content}".contains(term, true) } }
    }

    fun save(entry: ContextEntry, projectRoot: String?): Boolean = synchronized(MemoryFileStorage.lock) {
        val source = sources(projectRoot).firstOrNull { it.id == entry.id } ?: return@synchronized false
        if (!source.editable || source.scope != entry.scope || source.origin != entry.origin) return@synchronized false
        if (source.scope == MemoryScope.PROJECT && (entry.workspaceRoot != projectRoot || source.workspaceRoot != projectRoot)) return@synchronized false
        if (entry.category == ContextCategory.TEMPORARY && entry.sessionId.isNullOrBlank()) return@synchronized false
        if (entry.version.isBlank() || entry.title.isBlank() || !ContextFolderPath.valid(entry.folder)) return@synchronized false
        if (entry.title != source.title) {
            val original = com.aharou.feature.agent.domain.memory.MemoryParser.parse(
                java.io.File(source.sourceLabel), source.scope
            ) ?: return@synchronized false
            return@synchronized MemoryFileStorage.renameChecked(original, original.copy(
                name = entry.title, description = entry.description, content = entry.content,
                contextKind = entry.kind, contextLoad = entry.loading, contextCategory = entry.category,
                contextFolder = entry.folder, contextSessionId = entry.sessionId
            ), entry.version)
        }
        memories.writeMemory(source.title, entry.description, entry.content, source.scope, projectRoot,
            expectedVersion = entry.version, contextKind = entry.kind, contextLoad = entry.loading,
            contextCategory = entry.category, contextFolder = entry.folder, contextSessionId = entry.sessionId) == MemoryWriteResult.Success
    }

    fun create(name: String, description: String, content: String, scope: MemoryScope, kind: ContextKind,
               loading: ContextLoad, projectRoot: String?, category: ContextCategory = ContextCategory.LONG_TERM,
               folder: String = "", sessionId: String? = null): Boolean {
        if (name.isBlank() || content.isBlank() || !ContextFolderPath.valid(folder) ||
            (category == ContextCategory.TEMPORARY && sessionId.isNullOrBlank())) return false
        return memories.writeMemory(name, description, content, scope, projectRoot,
            expectedVersion = MemorySource.VERSION_NEW, contextKind = kind, contextLoad = loading,
            contextCategory = category, contextFolder = folder, contextSessionId = sessionId) == MemoryWriteResult.Success
    }

    fun delete(entry: ContextEntry, projectRoot: String?): Boolean = synchronized(MemoryFileStorage.lock) {
        val current = sources(projectRoot).firstOrNull { it.id == entry.id } ?: return@synchronized false
        if (entry.version.isBlank() || current.version != entry.version || !current.editable) return@synchronized false
        if (current.scope == MemoryScope.PROJECT && entry.workspaceRoot != projectRoot) return@synchronized false
        memories.deleteMemory(current.title, current.scope, projectRoot)
    }

    fun preview(projectRoot: String?, query: String, sessionId: String? = null): ContextPreview {
        val previous = snapshot.get()
        snapshot.set(Snapshot(projectRoot, sessionId, true, false))
        return try { plan(projectRoot, sessionId) } finally { if (previous == null) snapshot.remove() else snapshot.set(previous) }
    }

    private fun plan(root: String?, sessionId: String?): ContextPreview {
        val active = snapshot.get()?.takeIf { it.root == root && it.sessionId == sessionId }
        active?.plan?.let { return it }
        val entries = list(root).map { entry ->
            entry.copy(enabled = entry.enabled && active?.ghost != true && active?.memoryAllowed != false)
        }
        return ContextLibraryPlanner.plan(entries, sessionId).also { active?.plan = it }
    }

    fun render(projectRoot: String?, sessionId: String?, origin: ContextOrigin, enabled: Boolean = true,
               memberName: String? = null, inheritsMainRules: Boolean = false, query: String = ""): String {
        if (!enabled) return ""
        return plan(projectRoot, sessionId).rendered
    }
}
