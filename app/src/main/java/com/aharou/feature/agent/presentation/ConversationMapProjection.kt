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

/** Only the supplied page is projected. Non-user records before the first user stay in a prelude. */
internal fun projectConversationMap(
    messages: List<AgentUIMessage>,
    hasMore: Boolean
): ConversationMapProjection {
    val seen = HashSet<String>()
    val nodes = mutableListOf<ConversationMapNode>()
    var anchor: AgentUIMessage? = null
    var summary: String? = null
    var count = 0
    var assistants = 0
    var tools = 0
    fun finish() {
        val start = anchor ?: return
        nodes += ConversationMapNode(start.id, start.id, summary, start.timestamp, count, assistants, tools)
    }
    messages.forEach { message ->
        // The first occurrence owns identity and order, so duplicate ids never create duplicate Lazy keys.
        if (!seen.add(message.id)) return@forEach
        val startsTurn = message.role == MessageRole.USER &&
            !message.isCompactionMarker && !message.isContextSummary && !message.isBackgroundNotification
        if (startsTurn || anchor == null) {
            finish()
            anchor = message
            summary = if (startsTurn) message.content.take(CONVERSATION_MAP_SUMMARY_LIMIT * 3)
                .replace(Regex("\\s+"), " ").trim().take(CONVERSATION_MAP_SUMMARY_LIMIT) else null
            count = 0
            assistants = 0
            tools = 0
        }
        count++
        if (message.role == MessageRole.ASSISTANT) assistants++
        if (message.role == MessageRole.TOOL) tools++
    }
    finish()
    return ConversationMapProjection(nodes, seen.size, hasMore)
}
