package com.aharou.feature.agent.domain.memory

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory drafts only: process death discards proposals, never silently applies them. */
data class MemoryProposal(
    val id: String,
    val inputs: List<Memory>,
    val name: String,
    val description: String,
    val content: String,
    val type: MemoryType,
    val merge: Boolean,
) {
    val payload: String get() = MemoryParser.format(
        name, description, content, type, inputs.flatMap { it.keywords }.distinct(),
        inputs.first().expiresAt, inputs.first().pending
    )
    val revision: String get() = MemoryFileStorage.versionOf(
        inputs.joinToString("\n") { "${it.file?.absolutePath}:${it.version}" } + "\n" + payload
    )
}

enum class MemoryProposalOutcome { APPLIED, STALE, MISSING, FAILED }

@Singleton
class MemoryProposalStore @Inject constructor() {
    private val _pending = MutableStateFlow<List<MemoryProposal>>(emptyList())
    val pending = _pending.asStateFlow()

    internal fun propose(
        inputs: List<Memory>, name: String, description: String, content: String,
        type: MemoryType, merge: Boolean
    ): MemoryProposal? = synchronized(MemoryFileStorage.lock) {
        if (inputs.isEmpty() || inputs.any { it.file == null || it.version.isBlank() }) return@synchronized null
        if ((merge && inputs.size < 2) || (!merge && inputs.size != 1)) return@synchronized null
        val frozen = inputs.map { it.copy(keywords = it.keywords.toList()) }
        val proposal = MemoryProposal(UUID.randomUUID().toString(), frozen, name, description, content, type, merge)
        _pending.value.firstOrNull { it.revision == proposal.revision }?.let { return@synchronized it }
        if (_pending.value.size >= 64) return@synchronized null
        _pending.value = _pending.value + proposal
        proposal
    }

    fun reject(id: String) = synchronized(MemoryFileStorage.lock) {
        _pending.value = _pending.value.filterNot { it.id == id }
    }

    /** Approval carries the reviewed revision; neither model nor prompt resolver is on this path. */
    fun approve(id: String, revision: String): MemoryProposalOutcome = synchronized(MemoryFileStorage.lock) {
        val proposal = _pending.value.firstOrNull { it.id == id } ?: return@synchronized MemoryProposalOutcome.MISSING
        if (proposal.revision != revision || proposal.inputs.any {
            MemoryParser.parse(it.file!!, it.scope) != it
        }) {
            reject(id)
            return@synchronized MemoryProposalOutcome.STALE
        }
        val applied = if (proposal.merge) {
            MemoryFileStorage.merge(proposal.inputs, proposal.name, proposal.description, proposal.content, proposal.type)
        } else {
            val input = proposal.inputs.single()
            MemoryFileStorage.write(input.file!!, proposal.payload, expectedVersion = input.version)
        }
        if (applied) reject(id)
        if (applied) MemoryProposalOutcome.APPLIED else MemoryProposalOutcome.FAILED
    }
}
