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

internal data class ProjectProgressNode(val id: String, val kind: String, val text: String)

/** Ordering comes from persisted user/assistant/tool messages, not synthesized thinking. */
internal fun projectProgressNodes(messages: List<AgentUIMessage>, liveTools: List<AgentUIMessage>,
    liveText: String, running: Boolean = false): List<ProjectProgressNode> {
    val rounds = projectConversationRounds(messages)
    val toolIds = liveTools.mapTo(hashSetOf()) { it.id }
    val nodes = rounds.flatMap { round ->
        val finalId = round.finalReply?.id?.takeIf { (!running && liveText.isBlank()) || round != rounds.lastOrNull() }
        round.messages.filter { it.id !in toolIds && it.content.isNotBlank() }.map { message ->
            ProjectProgressNode(message.id, when (message.role) {
                MessageRole.USER -> "目标"
                MessageRole.TOOL -> "工具"
                else -> if (message.id == finalId) "结果" else "计划（公开回复）"
            }, message.content)
        }
    }.toMutableList()
    nodes += liveTools.filter { it.content.isNotBlank() }.map { ProjectProgressNode(it.id, "工具（运行中）", it.content) }
    if (liveText.isNotBlank()) nodes += ProjectProgressNode("stream", "公开回复（生成中）", liveText)
    return nodes
}
