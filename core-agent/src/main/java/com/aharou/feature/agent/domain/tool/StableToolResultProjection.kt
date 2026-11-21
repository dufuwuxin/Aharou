package com.aharou.feature.agent.domain.tool

import com.aharou.feature.agent.domain.model.AgentMessage

/** One execution's request projection. New results cannot rewrite an already-sent tool prefix.
 * User retention changes, changed source results and compaction take effect immediately.
 * Full storage/history stays independent from this cache; permissions and execution are never cached.
 */
class StableToolResultProjection {
    private val sent = mutableMapOf<String, Pair<AgentMessage.ToolResultMessage, AgentMessage.ToolResultMessage>>()
    private var retention: Int? = null

    fun project(messages: List<AgentMessage>, keepRecent: Int): List<AgentMessage> {
        if (retention != keepRecent) { sent.clear(); retention = keepRecent }
        val candidates = foldStoredToolResults(messages, keepRecent)
        val retained = hashSetOf<String>()
        return messages.mapIndexed { index, message ->
            if (message !is AgentMessage.ToolResultMessage) message else {
                val key = message.messageId
                retained += key
                val previous = sent[key]
                if (previous?.first == message) previous.second else {
                    val projected = candidates[index] as AgentMessage.ToolResultMessage
                    sent[key] = message to projected
                    projected
                }
            }
        }.also { sent.keys.retainAll(retained) }
    }
}
