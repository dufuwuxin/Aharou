package com.aharou.feature.agent.domain.workflow

import com.aharou.feature.agent.domain.context.ContextLibraryPlanner
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.tool.foldStoredToolResults
import com.aharou.feature.agent.domain.tool.projectFileHistory
import com.aharou.feature.settings.data.repository.ContextPreferences
import java.security.MessageDigest

internal fun initialRequestMessages(request: String, context: AgentContext, reminder: String?): List<AgentMessage> {
    if (request.isBlank() && !context.currentInputCountsAsRound && context.inputImages.isEmpty() && reminder == null) {
        return context.history
    }
    return context.history + AgentMessage.UserMessage(
        id = context.inputMessageId,
        content = if (reminder == null) request else "$request\n\n$reminder",
        images = context.inputImages,
        countsAsRound = context.currentInputCountsAsRound
    )
}

/** One receipt-folding boundary per turn; appending results must not slide that boundary. */
internal class RequestHistoryProjector {
    private data class Receipt(val sourceDigest: String, val projected: AgentMessage.ToolResultMessage)
    private var receipts: MutableMap<String, Receipt>? = null

    private fun digest(message: AgentMessage.ToolResultMessage): String {
        val hash = MessageDigest.getInstance("SHA-256")
        for (text in listOf(message.toolName, message.result, message.modelResult.orEmpty())) {
            hash.update(text.length.toString().toByteArray(Charsets.UTF_8))
            hash.update(0.toByte())
            hash.update(text.toByteArray(Charsets.UTF_8))
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun project(messages: List<AgentMessage>, supportsVision: Boolean, preferences: ContextPreferences,
                compactReceipts: Boolean, transientContext: String = ""): List<AgentMessage> {
        val recent = projectImagesForEstimate(messages, supportsVision)
            .trimToRecentRounds(preferences.rounds, preferences.preserveSummary)
        if (compactReceipts && receipts == null) {
            val folded = projectFileHistory(foldStoredToolResults(recent, preferences.keepTools)).messages
            receipts = recent.asSequence().zip(folded.asSequence()).mapNotNull { (original, projected) ->
                if (original is AgentMessage.ToolResultMessage && projected is AgentMessage.ToolResultMessage &&
                    original.images.isEmpty() && original != projected)
                    original.messageId to Receipt(digest(original), projected) else null
            }.take(512).toMap().toMutableMap()
        }
        val present = recent.filterIsInstance<AgentMessage.ToolResultMessage>().mapTo(HashSet()) { it.messageId }
        receipts?.keys?.retainAll(present)
        val stable = if (receipts.isNullOrEmpty()) recent else recent.map { message ->
            val saved = (message as? AgentMessage.ToolResultMessage)?.let { receipts?.get(it.messageId) }
            if (saved != null && message is AgentMessage.ToolResultMessage) {
                if (message.images.isEmpty() && saved.sourceDigest == digest(message)) saved.projected
                else { receipts?.remove(message.messageId); message }
            } else message
        }
        val index = projectFileHistory(stable, keepRecent = Int.MAX_VALUE).index
        val context = listOf(index, transientContext).filter { it.isNotBlank() }.joinToString("\n\n")
        if (context.isBlank()) return stable
        return stable + AgentMessage.UserMessage(id = "request-file-history-index",
            content = ContextLibraryPlanner.safe(context), countsAsRound = false)
    }
}
