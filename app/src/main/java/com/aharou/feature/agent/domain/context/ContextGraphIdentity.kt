package com.aharou.feature.agent.domain.context

import com.aharou.feature.agent.domain.memory.Memory
import java.util.UUID

/** Graph identity is separate from the public, name-based entry id. Reading never writes a file. */
internal object ContextGraphIdentity {
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val reference = Regex("graph:v1:(GLOBAL|PROJECT:[0-9a-f]{64}):${uuid.pattern}")

    fun valid(value: String): Boolean = uuid.matches(value)

    fun raw(memory: Memory): String = memory.graphId?.takeIf(::valid) ?: UUID.nameUUIDFromBytes(
        "legacy:${memory.scope}:${memory.file?.canonicalPath ?: memory.name}".toByteArray(Charsets.UTF_8)
    ).toString()

    fun scoped(raw: String, entry: ContextEntry): String =
        "graph:v1:" + (if (entry.scope == com.aharou.feature.agent.domain.memory.MemoryScope.GLOBAL) "GLOBAL"
        else "PROJECT:${ContextIdentity.version(entry.workspaceRoot.orEmpty())}") + ":$raw"

    class Index(entries: List<ContextEntry>) {
        private val graphIds = entries.groupBy { it.graphId }
        private val publicIds = entries.groupBy { it.id }
        private val titles = entries.groupBy { it.title }
        private val aliases = entries.flatMap { entry ->
            entry.graphAliases.distinct().filter { alias -> !alias.startsWith("MEMORY:") ||
                alias.startsWith("MEMORY:${entry.scope}:" +
                    if (entry.scope == com.aharou.feature.agent.domain.memory.MemoryScope.PROJECT)
                        "${entry.workspaceRoot}/" else "") }.map { it to entry }
        }.groupBy({ it.first }, { it.second })

        fun matches(target: String): List<ContextEntry> {
            if (target.startsWith("graph:")) return if (reference.matches(target)) graphIds[target].orEmpty() else emptyList()
            val exact = publicIds[target].orEmpty()
            val historical = aliases[target].orEmpty()
            if (target.startsWith("MEMORY:")) return historical.ifEmpty { exact }
            if (exact.isNotEmpty()) return exact
            val candidates = (historical + titles[target].orEmpty()).distinctBy { it.id }
            return if (historical.isNotEmpty() && candidates.map { it.scope to it.workspaceRoot }.distinct().size == 1)
                historical else candidates
        }
    }

    fun matches(target: String, entries: List<ContextEntry>): List<ContextEntry> = Index(entries).matches(target)

    fun normalize(rules: WorldbookRules, entries: List<ContextEntry>): WorldbookRules {
        val index = Index(entries)
        return rules.copy(
        relations = rules.relations.map { relation ->
            val target = index.matches(relation.target).singleOrNull()
            if (target?.graphId?.startsWith("graph:") == true) relation.copy(target = target.graphId) else relation
        }
    )
    }
}
