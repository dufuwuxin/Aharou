package com.aharou.feature.agent.domain.steward

/** Process-local ownership and revision of a steward task; no notification queues are consumed here. */
class StewardTurnPolicy {
    data class Ticket(val generation: Long, val sessionId: String)

    private var generation = 0L
    private var sessionId: String? = null
    var enabled = false
        private set
    var instruction = ""
        private set

    fun enable(instruction: String, sessionId: String?): Long {
        generation++
        this.sessionId = sessionId
        this.instruction = instruction.trim()
        enabled = true
        return generation
    }

    fun disable() {
        generation++
        enabled = false
    }

    fun bindAnchor(sessionId: String, instruction: String, revision: Long = generation) {
        if (enabled && this.sessionId == sessionId && revision == generation && this.instruction.isBlank()) {
            this.instruction = instruction.trim()
        }
    }

    fun ticket(sessionId: String): Ticket? =
        if (enabled && this.sessionId == sessionId && instruction.isNotBlank()) Ticket(generation, sessionId) else null

    fun isCurrent(ticket: Ticket): Boolean =
        enabled && ticket.generation == generation && ticket.sessionId == sessionId

    companion object {
        fun skillModelRequest(request: String, modelRequest: String, skillPrompt: String): String =
            if (modelRequest.startsWith(request)) skillPrompt + modelRequest.removePrefix(request)
            else if (modelRequest == request) skillPrompt else "$skillPrompt\n\n$modelRequest"

        fun mergeModelRequest(modelRequest: String, nextInstruction: String?): String =
            if (nextInstruction.isNullOrBlank()) modelRequest else "$modelRequest\n\n$nextInstruction"
    }
}
