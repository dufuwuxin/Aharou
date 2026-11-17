package com.aharou.feature.agent.presentation

import com.aharou.feature.agent.data.local.entity.AgentMessageEntity
import com.aharou.feature.agent.data.local.entity.toUIMessage
import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.tool.ExecutionHistoryEvent
import com.aharou.feature.agent.domain.tool.ExecutionHistoryTree
import com.aharou.feature.agent.domain.tool.ToolCall
import com.aharou.feature.agent.domain.tool.projectExecutionHistory
import kotlinx.serialization.json.Json

internal data class SessionExecutionHistory(
    val sessionId: String?,
    val tree: ExecutionHistoryTree = ExecutionHistoryTree(),
    val loaded: Boolean = false,
    val hasMore: Boolean = false,
    val timestamps: Map<String, Long> = emptyMap(),
    val sources: Map<String, ExecutionHistorySource> = emptyMap(),
)

internal data class ExecutionHistorySource(val message: AgentUIMessage, val calls: String? = null)

private val historyJson = Json { ignoreUnknownKeys = true }

/** Uses raw paged records, not the bubble list which deliberately hides tool-call-only assistants. */
internal fun projectSessionExecutionHistory(sessionId: String, rows: List<AgentMessageEntity>, hasMore: Boolean): SessionExecutionHistory {
    val messages = rows.filter { it.sessionId == sessionId }.mapNotNull { row ->
        when (row.role) {
            "USER" -> AgentMessage.UserMessage(row.id, row.content, countsAsRound =
                !row.isCompactionMarker && !row.isContextSummary && !row.toUIMessage().isBackgroundNotification)
            "ASSISTANT" -> AgentMessage.AssistantMessage(id = row.id, content = "", toolCalls =
                row.toolCallsJson?.let { runCatching { historyJson.decodeFromString<List<ToolCall>>(it) }.getOrNull() }.orEmpty())
            "TOOL" -> AgentMessage.ToolResultMessage(id = row.toolCallId ?: row.id,
                toolName = row.toolName.orEmpty(), result = if (row.isError) "{\"status\":\"error\"}" else row.content, messageId = row.id)
            else -> null
        }
    }
    return SessionExecutionHistory(sessionId, projectExecutionHistory(messages), loaded = true, hasMore = hasMore,
        timestamps = rows.filter { it.sessionId == sessionId }.associate { it.id to it.timestamp },
        sources = rows.filter { it.sessionId == sessionId }.associate { it.id to ExecutionHistorySource(it.toUIMessage(), it.toolCallsJson) })
}

internal fun filterExecutionEvents(events: List<ExecutionHistoryEvent>, query: String): List<ExecutionHistoryEvent> {
    val needle = query.trim()
    if (needle.isEmpty()) return events
    return events.filter { event ->
        listOfNotNull(event.path, event.action, event.category, event.status, event.callId,
            event.userMessageId, event.assistantMessageId, event.toolMessageId).any { it.contains(needle, ignoreCase = true) }
    }
}

internal fun executionTreeJumpAllowed(sessionId: String?, sourceSessionId: String?, loaded: Boolean,
                                     bodyVisible: Boolean, anchor: String?, canLocate: (String) -> Boolean): Boolean =
    !sessionId.isNullOrBlank() && sessionId == sourceSessionId && loaded && bodyVisible && anchor != null && canLocate(anchor)
