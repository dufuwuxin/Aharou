package com.aharou.feature.agent.presentation

import com.aharou.feature.agent.presentation.component.projectConversationRounds
import com.aharou.feature.agent.presentation.component.projectFinalReply
import com.aharou.feature.agent.presentation.component.projectLiveToolMessages
import com.aharou.feature.agent.presentation.component.projectRecordSelection
import com.aharou.feature.agent.presentation.component.projectProcessRecords
import com.aharou.feature.agent.domain.task.ProjectTask
import com.aharou.feature.agent.domain.task.ProjectTaskMemberRun
import org.junit.Assert.*
import org.junit.Test

class ProjectTaskConversationContentTest {
    @Test fun samplingStopRetainsWarningAndRejectsTruncatedReply() {
        val stop = message("stop", MessageRole.TOOL, "truncated").copy(isSamplingLoopStop = true)
        val round = projectConversationRounds(listOf(message("a", MessageRole.ASSISTANT, "partial"), stop)).single()
        assertEquals(listOf("a", "stop"), round.messages.map { it.id })
        assertNull(round.finalReply)
    }

    @Test fun compactionFailureRetainsWarningAndRejectsEarlierReply() {
        val failure = message("failure", MessageRole.TOOL, "failed").copy(isCompactionFailure = true)
        val round = projectConversationRounds(listOf(message("a", MessageRole.ASSISTANT, "partial"), failure)).single()
        assertEquals(failure, round.messages.last())
        assertNull(round.finalReply)
    }

    @Test fun successfulReplyAfterCompactionFailureIsFinal() {
        val warning = message("failure", MessageRole.TOOL, "compaction failed").copy(isCompactionFailure = true)
        val answer = message("answer", MessageRole.ASSISTANT, "completed after recovery")
        val round = projectConversationRounds(listOf(warning, answer)).single()
        assertEquals(listOf(warning, answer), round.messages)
        assertEquals(answer, projectFinalReply(listOf(round), running = false))
        assertNull(projectFinalReply(listOf(round), running = true))
    }

    @Test fun terminalWarningInAssistantRoleIsNotFinal() {
        val warning = message("stop", MessageRole.ASSISTANT, "interrupted").copy(isSamplingLoopStop = true)
        assertNull(projectConversationRounds(listOf(warning)).single().finalReply)
    }

    @Test fun multipleUserRoundsKeepDifferentContents() {
        val rounds = projectConversationRounds(listOf(message("u1", MessageRole.USER, "first request"),
            message("a1", MessageRole.ASSISTANT, "first answer", "real reasoning"),
            message("u2", MessageRole.USER, "second request"), message("t", MessageRole.TOOL, "tool"),
            message("a2", MessageRole.ASSISTANT, "second answer")))
        assertEquals(2, rounds.size)
        assertEquals("first answer", rounds[0].finalReply?.content)
        assertEquals("real reasoning", rounds[0].messages.last().reasoning)
        assertEquals("second answer", rounds[1].finalReply?.content)
    }

    @Test fun toolsUsersEmptyAndFailedRepliesAreNotFinal() {
        listOf(message("t", MessageRole.TOOL, "tool"), message("u", MessageRole.USER, "request"),
            message("empty", MessageRole.ASSISTANT, ""),
            message("marked-error", MessageRole.ASSISTANT, "failed").copy(isError = true),
            message("error", MessageRole.ASSISTANT, "partial").copy(error = "failed")).forEach {
            assertNull(projectConversationRounds(listOf(it)).single().finalReply)
        }
        assertNull(projectConversationRounds(listOf(message("a", MessageRole.ASSISTANT, "intermediate"),
            message("t", MessageRole.TOOL, "working"))).single().finalReply)
    }

    @Test fun internalSummaryIsNotFinalReply() {
        assertTrue(projectConversationRounds(listOf(message("s", MessageRole.ASSISTANT, "internal")
            .copy(isContextSummary = true))).isEmpty())
    }

    @Test fun absentReasoningIsNotInvented() {
        val round = projectConversationRounds(listOf(message("a", MessageRole.ASSISTANT, "plain"))).single()
        assertNull(round.messages.single().reasoning)
        assertEquals("plain", round.finalReply?.content)
    }

    @Test fun longReplyPreservesEveryCharacterNotUILayout() {
        val content = "paragraph\n".repeat(20000) + "UNIQUE_END"
        assertEquals(content, projectConversationRounds(listOf(message("a", MessageRole.ASSISTANT, content)))
            .single().finalReply?.content)
    }

    @Test fun independentMapperCallsDoNotReuseOtherRecords() {
        val a = listOf(message("same", MessageRole.ASSISTANT, "A"))
        val b = listOf(message("same", MessageRole.ASSISTANT, "B"))
        assertEquals("A", projectConversationRounds(a).single().finalReply?.content)
        assertEquals("B", projectConversationRounds(b).single().finalReply?.content)
        assertEquals("A", projectConversationRounds(a).single().finalReply?.content)
    }

    @Test fun activeSessionWithoutTokensCannotExposeFinalReply() {
        val rounds = projectConversationRounds(listOf(message("a", MessageRole.ASSISTANT, "intermediate")))
        assertNull(projectFinalReply(rounds, running = true))
        assertEquals("intermediate", projectFinalReply(rounds, running = false)?.content)
    }

    @Test fun moreThanThirtyMessagesKeepExpandedRoundIdentityOnAppendAndEarlierLoad() {
        val all = (1..65).map { message("m$it", MessageRole.TOOL, "output $it") }
        val initial = projectConversationRounds(all.take(31).takeLast(30))
        val expanded = setOf(initial.single().id)
        val appended = projectConversationRounds(all.take(32).takeLast(30), initial)
        assertTrue(appended.single().id in expanded)
        val earlier = projectConversationRounds(all.take(32), appended)
        assertTrue(earlier.single().id in expanded)
        assertEquals((1..32).map { "m$it" }, earlier.single().messages.map { it.id })
    }

    @Test fun earlierDifferentRoundDoesNotStealExpansionOrMergeUserBoundaries() {
        val later = listOf(message("u2", MessageRole.USER, "second"), message("a2", MessageRole.ASSISTANT, "B"))
        val initial = projectConversationRounds(later.takeLast(1))
        val expanded = setOf(initial.single().id)
        val both = projectConversationRounds(listOf(message("u1", MessageRole.USER, "first"),
            message("a1", MessageRole.ASSISTANT, "A")) + later, initial)
        assertEquals(2, both.size)
        assertFalse(both[0].id in expanded)
        assertTrue(both[1].id in expanded)
        assertEquals(listOf("u2", "a2"), both[1].messages.map { it.id })
    }

    @Test fun liveToolsHaveRealOutputEvenWithoutAssistantTokensAndAreDeduplicated() {
        val tool = RunningToolOutput("t", "actual running output", "Bash", "args")
        val stored = message("t", MessageRole.TOOL, "pending")
        val live = projectLiveToolMessages(listOf(stored), listOf(tool, tool))
        assertEquals(1, live.size)
        assertEquals(MessageRole.TOOL, live.single().role)
        assertEquals("actual running output", live.single().content)
        assertEquals(listOf("t"), live.map { it.id })
        assertEquals("Bash", projectLiveToolMessages(emptyList(), listOf(tool)).single().toolName)
    }

    @Test fun historicalSelectionIsPassedToReaderAndInvalidSelectionFallsBack() {
        val records = listOf("record-A", "record-B")
        assertEquals("record-A", projectRecordSelection(records, "record-A"))
        assertEquals("record-B", projectRecordSelection(records, "record-B"))
        assertEquals("record-A", projectRecordSelection(records, "removed"))
        assertEquals("record-A", projectRecordSelection(records, null))
        assertNull(projectRecordSelection(emptyList(), "record-A"))
    }

    @Test fun memberProcessRecordSelectionKeepsProductOrderAndHistoricalChoice() {
        val member = ProjectTaskMemberRun("reviewer", 2, 1, outcome = "SUCCEEDED")
        val task = ProjectTask(workspace = "workspace", title = "task", requirement = "requirement",
            sessionIds = listOf("original", "shared"), processSessionIds = listOf("shared", "member"),
            processMembers = mapOf("member" to member))
        val records = projectProcessRecords(task)
        assertEquals(listOf("original", "shared", "member"), records)
        assertEquals("original", projectRecordSelection(records, null))
        val selected = projectRecordSelection(records, "member")
        assertEquals("member", selected)
        assertEquals(member, task.processMembers[selected])
        val appended = task.copy(processSessionIds = task.processSessionIds + "next")
        assertEquals("member", projectRecordSelection(projectProcessRecords(appended), selected))
        assertEquals("original", projectRecordSelection(projectProcessRecords(appended), "removed"))
    }

    private fun message(id: String, role: MessageRole, content: String, reasoning: String? = null) =
        AgentUIMessage(id = id, role = role, content = content, reasoning = reasoning)
}
