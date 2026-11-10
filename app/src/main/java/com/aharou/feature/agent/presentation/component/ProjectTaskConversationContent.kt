package com.aharou.feature.agent.presentation.component

import com.aharou.feature.agent.domain.task.ProjectTask
import com.aharou.feature.agent.presentation.AgentUIMessage
import com.aharou.feature.agent.presentation.RunningToolOutput
import com.aharou.feature.agent.presentation.MessageRole

internal data class ProjectConversationRound(val id: String, val messages: List<AgentUIMessage>) {
    val finalReply: AgentUIMessage?
        get() = messages.lastOrNull()?.takeIf {
            it.role == MessageRole.ASSISTANT && it.content.isNotBlank() && it.error == null &&
                !it.isError && !it.isSamplingLoopStop && !it.isCompactionFailure
        }
}

internal fun projectConversationRounds(messages: List<AgentUIMessage>,
    previous: List<ProjectConversationRound> = emptyList()): List<ProjectConversationRound> {
    val rounds = mutableListOf<ProjectConversationRound>()
    val current = mutableListOf<AgentUIMessage>()
    fun flush() {
        if (current.isNotEmpty()) {
            val ids = current.mapTo(mutableSetOf()) { it.id }
            val prior = previous.firstOrNull { round -> round.messages.any { it.id in ids } }
            rounds += ProjectConversationRound(prior?.id ?: current.first().id, current.toList())
        }
        current.clear()
    }
    messages.forEach { message ->
        if (message.isCompactionMarker || message.isContextSummary || message.isBackgroundNotification) return@forEach
        if (message.role == MessageRole.USER) flush()
        current += message
    }
    flush()
    return rounds
}

internal fun projectFinalReply(rounds: List<ProjectConversationRound>, running: Boolean): AgentUIMessage? =
    if (running) null else rounds.lastOrNull()?.finalReply

internal fun projectProcessRecords(task: ProjectTask): List<String> =
    (task.sessionIds + task.processSessionIds).distinct()

internal fun projectRecordSelection(records: List<String>, requested: String?): String? =
    requested?.takeIf { it in records } ?: records.firstOrNull()

internal fun projectLiveToolMessages(messages: List<AgentUIMessage>, tools: List<RunningToolOutput>): List<AgentUIMessage> =
    tools.distinctBy { it.messageId }.map { tool ->
        val stored = messages.firstOrNull { it.id == tool.messageId && it.role == MessageRole.TOOL }
        stored?.copy(content = tool.text) ?: AgentUIMessage(id = tool.messageId, role = MessageRole.TOOL,
            content = tool.text, toolName = tool.toolName, toolArgs = tool.toolArgs)
    }
