package com.aharou.feature.agent.domain.workflow

import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.model.CONTEXT_COMPACTION_MARKER

/**
 * 只保留最近 [rounds] 轮（一问一答算一轮）作为发送投影。
 *
 * 从最早的轮次往前丢，且始终切在「用户消息」边界上——工具调用与它的结果永远落在同一段里，
 * 不会切出「有 tool_call 却没有结果」的断头消息。历史本身不动，数据库里仍然完整，
 * 只影响这一轮发给模型的那一份。
 */
internal fun List<AgentMessage>.trimToRecentRounds(rounds: Int, preserveSummary: Boolean = true): List<AgentMessage> {
    if (rounds <= 0) return this
    val userIndexes = indices.filter { (this[it] as? AgentMessage.UserMessage)?.let { user -> user.countsAsRound && user.content != CONTEXT_COMPACTION_MARKER } == true }
    if (userIndexes.size <= rounds) return this
    val cutoff = userIndexes[userIndexes.size - rounds]
    val recent = subList(cutoff, size)
    if (!preserveSummary) return recent
    val summaryIndex = (0 until cutoff - 1).lastOrNull { index ->
        (this[index] as? AgentMessage.UserMessage)?.content == CONTEXT_COMPACTION_MARKER &&
            (this[index + 1] as? AgentMessage.AssistantMessage)?.toolCalls?.isEmpty() == true
    } ?: return recent
    // A newer summary already in the retained segment supersedes any earlier one.
    if (recent.indices.any { index ->
        (recent[index] as? AgentMessage.UserMessage)?.content == CONTEXT_COMPACTION_MARKER &&
            (recent.getOrNull(index + 1) as? AgentMessage.AssistantMessage)?.toolCalls?.isEmpty() == true
    }) return recent
    return subList(summaryIndex, summaryIndex + 2) + recent
}
