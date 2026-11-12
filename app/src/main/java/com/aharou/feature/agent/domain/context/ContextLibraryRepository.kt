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
    private val builtins: BuiltinContextEntries? = null,
) {
    private class Snapshot(
        val root: String?, val sessionId: String?, val memoryAllowed: Boolean,
        val injectionCategory: ContextCategory,
        val userText: String = "", val roleName: String? = null,
        val roleTags: List<String> = emptyList(),
    ) {
        var entries: List<ContextEntry>? = null
        val plans = mutableMapOf<Boolean, ContextPreview>()
    }
    private val snapshot = ThreadLocal<Snapshot?>()

    fun withPromptSnapshot(ctx: AgentContext, roleSession: Boolean = ctx.agentDefinition != null, block: () -> String): String {
        val previous = snapshot.get()
        snapshot.set(Snapshot(ctx.projectRoot.takeIf { it.isNotBlank() }, ctx.sessionId,
            ctx.agentDefinition?.let { InjectPart.MEMORY in it.inject } ?: true,
            if (roleSession) ContextCategory.ROLE_INJECTED else ContextCategory.MAIN_INJECTED,
            ctx.currentUserText, ctx.agentDefinition?.name, ctx.agentDefinition?.tags.orEmpty()))
        return try { block() } finally { if (previous == null) snapshot.remove() else snapshot.set(previous) }
    }

    private fun memoryEntry(m: Memory, projectRoot: String?) = ContextEntry(
        ContextIdentity.id(ContextOrigin.MEMORY, m.scope, if (m.scope == MemoryScope.PROJECT) "$projectRoot/${m.name}" else m.name), m.name, m.description, m.content,
        m.scope, m.contextKind, m.contextLoad, ContextOrigin.MEMORY, m.file?.absolutePath ?: m.name,
        m.version, pending = m.pending, enabled = m.expiresAt?.let { it > System.currentTimeMillis() } ?: true,
        category = m.contextCategory, folder = m.contextFolder, sessionId = m.contextSessionId,
        workspaceRoot = if (m.scope == MemoryScope.PROJECT) projectRoot else null, requestKey = m.contextRequest,
        keywords = m.keywords, worldbook = m.worldbook, graphAliases = m.graphAliases,
    ).let { it.copy(graphId = ContextGraphIdentity.scoped(ContextGraphIdentity.raw(m), it)) }

    internal fun sources(projectRoot: String?): List<ContextEntry> = buildList {
        builtins?.ensureSeeded(memories)
        addAll(memories.listMemoriesInScope(MemoryScope.GLOBAL, projectRoot))
        if (!projectRoot.isNullOrBlank()) addAll(memories.listMemoriesInScope(MemoryScope.PROJECT, projectRoot))
    }.let(MemoryFileStorage::migrateContextLayout).filterNot { memory ->
        !memory.hasContextMetadata && listOf("SOUL", "CORE", "GLOBAL").any { it.equals(memory.name, true) }
    }.map { memoryEntry(it, projectRoot) }

    fun list(projectRoot: String?, sessionId: String? = null): List<ContextEntry> {
        val active = snapshot.get()?.takeIf { it.root == projectRoot?.takeIf(String::isNotBlank) }
        val entries = active?.entries ?: sources(projectRoot).also { active?.entries = it }
        return entries.filter { sessionId == null || it.category != ContextCategory.TEMPORARY || it.sessionId == sessionId }
    }

    /** Named requests are explicit retrieval, not a second prompt storage channel. OFF always wins. */
    fun resolvePrompt(name: String): String {
        val active = snapshot.get()
        return list(active?.root, active?.sessionId).filter { entry ->
            entry.requestKey == name && entry.enabled && !entry.pending && entry.loading != ContextLoad.OFF &&
                entry.category == ContextCategory.RULE && active?.memoryAllowed != false &&
                entry.worldbook.omission(active?.userText.orEmpty(),
                    if (active?.injectionCategory == ContextCategory.ROLE_INJECTED) WorldbookAudience.ROLE else WorldbookAudience.MAIN,
                    active?.roleName, active?.roleTags.orEmpty()) == null
        }.joinToString("\n\n") { ContextLibraryPlanner.safe(it.content) }
    }

    fun search(query: String, projectRoot: String?): List<ContextEntry> {
        if (query.isBlank()) return emptyList()
        return ContextSearch.search(list(projectRoot), query)
    }

    fun save(entry: ContextEntry, projectRoot: String?): Boolean = synchronized(MemoryFileStorage.lock) {
        val entries = sources(projectRoot)
        val source = entries.firstOrNull { it.id == entry.id } ?: return@synchronized false
        val rules = ContextGraphIdentity.normalize(entry.worldbook, entries)
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
                contextFolder = entry.folder, contextSessionId = entry.sessionId, contextRequest = source.requestKey,
                worldbook = rules, graphAliases = (original.graphAliases + source.id + source.title).distinct()
            ), entry.version)
        }
        memories.writeMemory(source.title, entry.description, entry.content, source.scope, projectRoot,
            expectedVersion = entry.version, contextKind = entry.kind, contextLoad = entry.loading,
            contextCategory = entry.category, contextFolder = entry.folder, contextSessionId = entry.sessionId,
            contextRequest = source.requestKey, worldbook = rules.takeUnless { it == source.worldbook }) == MemoryWriteResult.Success
    }

    fun create(name: String, description: String, content: String, scope: MemoryScope, kind: ContextKind,
               loading: ContextLoad, projectRoot: String?, category: ContextCategory = ContextCategory.LONG_TERM,
               folder: String = "", sessionId: String? = null, worldbook: WorldbookRules = WorldbookRules()): Boolean {
        if (name.isBlank() || content.isBlank() || !ContextFolderPath.valid(folder) ||
            (category == ContextCategory.TEMPORARY && sessionId.isNullOrBlank())) return false
        return memories.writeMemory(name, description, content, scope, projectRoot,
            expectedVersion = MemorySource.VERSION_NEW, contextKind = kind, contextLoad = loading,
            contextCategory = category, contextFolder = folder, contextSessionId = sessionId,
            worldbook = ContextGraphIdentity.normalize(worldbook, sources(projectRoot)).takeUnless { it == WorldbookRules() }) == MemoryWriteResult.Success
    }

    fun delete(entry: ContextEntry, projectRoot: String?): Boolean = synchronized(MemoryFileStorage.lock) {
        val current = sources(projectRoot).firstOrNull { it.id == entry.id } ?: return@synchronized false
        if (entry.version.isBlank() || current.version != entry.version || !current.editable) return@synchronized false
        if (current.scope == MemoryScope.PROJECT && entry.workspaceRoot != projectRoot) return@synchronized false
        memories.deleteMemory(current.title, current.scope, projectRoot)
    }

    /** The confirmation snapshot must still describe every affected native entry, including descendants. */
    fun deleteFolder(category: ContextCategory, folder: String, scope: MemoryScope, projectRoot: String?,
                     expectedEntries: List<ContextEntry>, removeMarkers: () -> Boolean): Boolean = synchronized(MemoryFileStorage.lock) {
        if (folder.isEmpty() || !ContextFolderPath.valid(folder) ||
            (scope == MemoryScope.PROJECT && projectRoot.isNullOrBlank())) return@synchronized false
        fun affected(entry: ContextEntry) = entry.category == category && entry.scope == scope &&
            (entry.folder == folder || entry.folder.startsWith("$folder/"))
        val expected = expectedEntries.filter(::affected)
        if (expected.any { !it.editable || it.version.isBlank() ||
                (scope == MemoryScope.PROJECT && it.workspaceRoot != projectRoot) }) return@synchronized false
        val current = sources(projectRoot).filter(::affected)
        if (expected.associate { it.id to it.version } != current.associate { it.id to it.version }) return@synchronized false
        val parent = folder.substringBeforeLast('/', "")
        val updates = current.map { entry ->
            val memory = com.aharou.feature.agent.domain.memory.MemoryParser.parse(java.io.File(entry.sourceLabel), scope)
                ?: return@synchronized false
            if (memory.version != entry.version) return@synchronized false
            memory.copy(contextFolder = parent)
        }
        MemoryFileStorage.rewriteContextMetadata(updates, removeMarkers)
    }

    fun preview(projectRoot: String?, query: String, sessionId: String? = null,
                roleName: String? = null, memoryAllowed: Boolean = true, roleTags: List<String> = emptyList()): ContextPreview {
        val previous = snapshot.get()
        snapshot.set(Snapshot(projectRoot?.takeIf(String::isNotBlank), sessionId, memoryAllowed,
            if (roleName == null) ContextCategory.MAIN_INJECTED else ContextCategory.ROLE_INJECTED, query, roleName, roleTags))
        return try { plan(projectRoot, sessionId) } finally { if (previous == null) snapshot.remove() else snapshot.set(previous) }
    }

    private fun plan(root: String?, sessionId: String?, ordinaryMemoryEnabled: Boolean = true,
                     memberName: String? = null, query: String = ""): ContextPreview {
        val normalizedRoot = root?.takeIf(String::isNotBlank)
        val active = snapshot.get()?.takeIf { it.root == normalizedRoot && it.sessionId == sessionId }
        val memoryAllowed = ordinaryMemoryEnabled && active?.memoryAllowed != false
        active?.plans?.get(memoryAllowed)?.let { return it }
        val audience = active?.injectionCategory ?: if (memberName == null) ContextCategory.MAIN_INJECTED else ContextCategory.ROLE_INJECTED
        return ContextLibraryPlanner.plan(list(normalizedRoot), sessionId,
            injectionCategory = audience, memoryAllowed = memoryAllowed,
            userText = active?.userText ?: query, roleName = active?.roleName ?: memberName, roleTags = active?.roleTags.orEmpty()).also { active?.plans?.set(memoryAllowed, it) }
    }

    fun render(projectRoot: String?, sessionId: String?, origin: ContextOrigin, enabled: Boolean = true,
               memberName: String? = null, inheritsMainRules: Boolean = false, query: String = "",
               ordinaryMemoryEnabled: Boolean = true, expand: ((String) -> String)? = null): String {
        if (!enabled) return ""
        if (expand != null) {
            val active = snapshot.get()
            val audience = active?.injectionCategory ?: if (memberName == null) ContextCategory.MAIN_INJECTED else ContextCategory.ROLE_INJECTED
            val entries = list(projectRoot).map { entry ->
                if (entry.category.isInjection && entry.category != audience || !entry.enabled || entry.loading != ContextLoad.FULL) entry
                else entry.copy(content = expand(entry.content))
            }
            return ContextLibraryPlanner.plan(entries, sessionId, origin, audience,
                ordinaryMemoryEnabled && active?.memoryAllowed != false,
                active?.userText ?: query, active?.roleName ?: memberName, active?.roleTags.orEmpty()).rendered
        }
        return plan(projectRoot, sessionId, ordinaryMemoryEnabled, memberName, query).rendered
    }
}
