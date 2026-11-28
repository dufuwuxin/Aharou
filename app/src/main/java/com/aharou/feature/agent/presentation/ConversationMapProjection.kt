package com.aharou.feature.agent.presentation

internal const val CONVERSATION_MAP_SUMMARY_LIMIT = 160

internal data class ConversationMapNode(
    val id: String,
    val startMessageId: String,
    val userSummary: String?,
    val timestamp: Long,
    val messageCount: Int,
    val assistantCount: Int,
    val toolCount: Int
)

internal data class ConversationMapProjection(
    val nodes: List<ConversationMapNode>,
    val loadedMessageCount: Int,
    val hasMore: Boolean
)

/** Projects only real user-authored messages; message ids remain the navigation identity. */
internal fun projectConversationMap(
    messages: List<AgentUIMessage>,
    hasMore: Boolean
): ConversationMapProjection {
    val loadedMessageCount = messages.map { it.id }.toSet().size
    val seenUsers = HashSet<String>()
    val nodes = messages.mapNotNull { message ->
        if (message.role != MessageRole.USER || message.isCompactionMarker ||
            message.isContextSummary || message.isBackgroundNotification || !seenUsers.add(message.id)
        ) return@mapNotNull null

        val summary = message.content.take(CONVERSATION_MAP_SUMMARY_LIMIT * 3)
            .replace(Regex("\\s+"), " ").trim().take(CONVERSATION_MAP_SUMMARY_LIMIT)
        ConversationMapNode(
            id = message.id,
            startMessageId = message.id,
            userSummary = summary,
            timestamp = message.timestamp,
            messageCount = 1,
            assistantCount = 0,
            toolCount = 0
        )
    }
    return ConversationMapProjection(nodes, loadedMessageCount, hasMore)
}
