package com.aharou.feature.agent.domain.steward

import com.aharou.feature.agent.domain.command.SlashCommandContext
import com.aharou.feature.agent.domain.command.SlashCommandRegistry
import com.aharou.feature.agent.domain.model.AgentImage
import com.aharou.feature.agent.presentation.AgentAttachment
import com.aharou.feature.agent.presentation.AgentTurnEndBridge
import com.aharou.feature.agent.presentation.CommandRequestContext
import com.aharou.feature.agent.presentation.QueuedRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StewardTurnPolicyTest {
    @Test fun initKeepsQueuedModelSuffixAttachmentsAndWorkspace() {
        val delegate = mockk<SlashCommandContext>(relaxed = true)
        val image = AgentImage("image/png", "image-data")
        val attachment = AgentAttachment("file.txt", "/a/file.txt", "/local/file.txt", "text/plain", 1L, false)
        val request = QueuedRequest(
            "request", "/init", "/init\n\nattachment: /a/file.txt\n\nnext step",
            "/a/source.kt", "selected", "/a", listOf(image), listOf(attachment),
        )
        var launched: QueuedRequest? = null
        CommandRequestContext(delegate, request) { launched = it }.initProject("init prompt")
        val actual = requireNotNull(launched)
        assertEquals("init prompt\n\nattachment: /a/file.txt\n\nnext step", actual.modelRequest)
        assertEquals(request.inputImages, actual.inputImages)
        assertEquals(request.inputAttachments, actual.inputAttachments)
        assertEquals(request.currentFile, actual.currentFile)
        assertEquals(request.selectedCode, actual.selectedCode)
        assertEquals("/a", actual.projectRoot)
        assertTrue(actual.isAutoTrigger)
        assertFalse(actual.persistUserMessage)
        verify(exactly = 0) { delegate.initProject(any()) }
    }

    @Test fun initKeepsIndependentModelBodyAndLocalActionsStayLocal() {
        val delegate = mockk<SlashCommandContext>(relaxed = true)
        val request = QueuedRequest("request", "/init", "attachment and next step", null, null, "/a")
        val launched = mutableListOf<QueuedRequest>()
        val context = CommandRequestContext(delegate, request) { launched += it }
        context.compactCurrentSession()
        context.showUsage()
        assertTrue(launched.isEmpty())
        verify(exactly = 1) { delegate.compactCurrentSession() }
        verify(exactly = 1) { delegate.showUsage() }
        context.initProject("init prompt")
        assertEquals("init prompt\n\nattachment and next step", launched.single().modelRequest)
    }

    @Test fun deferredStepAfterLocalActionKeepsOriginalWorkspace() {
        val registry = mockk<SlashCommandRegistry>()
        every { registry.resolve("/usage") } returns SlashCommandRegistry.ResolvedCommand.Action(mockk(), "")
        val bridge = AgentTurnEndBridge(mockk(), registry, mockk(), mockk())
        val action = QueuedRequest("request", "/usage", currentFile = null, selectedCode = null, projectRoot = "/a")
        val queues = MutableStateFlow(mapOf("session-a" to listOf(action)))
        var consumed = 0
        assertEquals(action, bridge.takeNextRequest("session-a", queues, { consumed++; "next" }, "/a"))
        assertEquals(0, consumed)
        val resumed = requireNotNull(bridge.takeNextRequest("session-a", queues, null, "/b"))
        assertEquals("/a", resumed.projectRoot)
        assertEquals("next", resumed.request)
        assertEquals(1, consumed)
        assertNull(bridge.takeNextRequest("session-a", queues, null, "/b"))
    }

    @Test fun disablingStewardInvalidatesStepHeldAcrossLocalAction() {
        val policy = StewardTurnPolicy()
        policy.enable("task", "a")
        val ticket = requireNotNull(policy.ticket("a"))
        val registry = mockk<SlashCommandRegistry>()
        every { registry.resolve("/usage") } returns SlashCommandRegistry.ResolvedCommand.Action(mockk(), "")
        val bridge = AgentTurnEndBridge(mockk(), registry, mockk(), mockk())
        val action = QueuedRequest("request", "/usage", currentFile = null, selectedCode = null, projectRoot = "/a")
        val queues = MutableStateFlow(mapOf("a" to listOf(action)))
        bridge.takeNextRequest("a", queues, { "next".takeIf { policy.isCurrent(ticket) } }, "/a")
        policy.disable()
        assertNull(bridge.takeNextRequest("a", queues, null, "/b"))
    }

    @Test fun nextStepJoinsCurrentModelRequestWithoutReplacingAttachments() {
        val model = "user request\n\nattachment: /workspace/file.txt"
        assertEquals("$model\n\nnext step", StewardTurnPolicy.mergeModelRequest(model, "next step"))
    }

    @Test fun noNextStepPreservesModelRequestExactly() {
        assertEquals("user\n", StewardTurnPolicy.mergeModelRequest("user\n", null))
        assertEquals("user\n", StewardTurnPolicy.mergeModelRequest("user\n", "  "))
    }

    @Test fun otherSessionCannotTriggerJudgement() {
        val policy = StewardTurnPolicy()
        policy.enable("task", "a")
        assertNull(policy.ticket("b"))
        assertNotNull(policy.ticket("a"))
    }

    @Test fun emptySessionWaitsForFirstRealRequest() {
        val policy = StewardTurnPolicy()
        policy.enable("", "a")
        assertTrue(policy.enabled)
        assertNull(policy.ticket("a"))
        policy.bindAnchor("b", "wrong session")
        assertEquals("", policy.instruction)
        policy.bindAnchor("a", "first request")
        assertNotNull(policy.ticket("a"))
        policy.bindAnchor("a", "later request")
        assertEquals("first request", policy.instruction)
    }

    @Test fun disabledJudgementCannotContinue() {
        val policy = StewardTurnPolicy()
        policy.enable("task", "a")
        val ticket = requireNotNull(policy.ticket("a"))
        policy.disable()
        assertFalse(policy.isCurrent(ticket))
    }

    @Test fun reenabledSameSessionRejectsOldJudgement() {
        val policy = StewardTurnPolicy()
        policy.enable("old task", "a")
        val old = requireNotNull(policy.ticket("a"))
        policy.disable()
        policy.enable("new task", "a")
        assertFalse(policy.isCurrent(old))
        assertTrue(policy.isCurrent(requireNotNull(policy.ticket("a"))))
    }

    @Test fun switchingAnchorRejectsOldJudgement() {
        val policy = StewardTurnPolicy()
        policy.enable("task", "a")
        val old = requireNotNull(policy.ticket("a"))
        policy.enable("other task", "b")
        assertFalse(policy.isCurrent(old))
        assertNull(policy.ticket("a"))
    }

    @Test fun lateHistoryReadCannotBindAfterReenable() {
        val policy = StewardTurnPolicy()
        val oldRevision = policy.enable("", "a")
        policy.enable("", "a")
        policy.bindAnchor("a", "stale history", oldRevision)
        assertEquals("", policy.instruction)
        policy.bindAnchor("a", "actual request")
        assertEquals("actual request", policy.instruction)
    }
}
