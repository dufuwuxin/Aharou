package com.aharou.feature.agent.presentation

import com.aharou.feature.agent.data.local.entity.ChatSessionEntity
import com.aharou.feature.agent.domain.checkpoint.CheckpointManager
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.runner.AgentTurnRequest
import com.aharou.feature.agent.domain.runner.AgentTurnRunner
import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.tool.ToolRegistry
import com.aharou.feature.agent.domain.tool.custom.ToolInjectionGate
import com.aharou.feature.agent.domain.tool.custom.ToolSettingsStore
import com.aharou.feature.agent.domain.workflow.AgentWorkflow
import com.aharou.feature.agent.domain.tool.question.PendingUserQuestionStore
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ContinuationRunnerTest {
    @Test fun actualRunnerKeepsStoredProgressWithoutANewUserMessageCheckpointOrTitle() = runTest {
        val workflow = mockk<AgentWorkflow>()
        val sessions = mockk<SessionUseCase>(relaxed = true)
        val persistence = mockk<MessagePersistenceUseCase>(relaxed = true)
        val checkpoints = mockk<CheckpointManager>(relaxed = true)
        val registry = mockk<ToolRegistry>(relaxed = true)
        val gate = mockk<ToolInjectionGate>(relaxed = true)
        val questions = mockk<PendingUserQuestionStore>()
        val workspaces = mockk<WorkspaceRepository>()
        val original = listOf(AgentMessage.UserMessage("u", "original task"),
            AgentMessage.AssistantMessage("a", "saved progress"))
        val session = ChatSessionEntity("s", "original title", 1, 2, workspacePath = "/project",
            providerId = "provider", model = "model", parentId = "parent")
        coEvery { sessions.getSessionById("s") } returns session
        coEvery { persistence.buildHistory("s", any()) } returns original
        coEvery { questions.takeAnswered("s") } returns emptyList()
        every { workspaces.current } returns MutableStateFlow(null)
        every { workspaces.workspaces } returns MutableStateFlow(emptyList())
        coEvery { gate.snapshotForProject(any()) } returns ToolSettingsStore.Snapshot(emptyList(), emptyMap())
        val captured = slot<AgentContext>()
        every { workflow.executeEvents(any(), capture(captured), any(), any()) } returns emptyFlow()
        val runner = AgentTurnRunner(workflow, registry, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), sessions, mockk(relaxed = true), mockk(relaxed = true), persistence,
            checkpoints, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            workspaces, questions, gate, mockk(relaxed = true))
        runner.run(AgentTurnRequest("s", "", projectRoot = "/project", isAutoTrigger = true,
            skipTitleUpdate = true, persistUserMessage = false)).toList()
        assertEquals(original, captured.captured.history)
        assertFalse(captured.captured.currentInputCountsAsRound)
        assertEquals("original task", captured.captured.currentUserText)
        assertEquals("/project", captured.captured.projectRoot)
        coVerify(exactly = 0) { checkpoints.createCheckpoint(any(), any(), any()) }
        coVerify(exactly = 0) { sessions.updateTitle(any(), any()) }
        coVerify(exactly = 0) { persistence.persist(any(), MessageRole.USER, any(), id = any(), attachments = any()) }
        coVerify(exactly = 0) { persistence.deleteMessagesFromTimestamp(any(), any()) }
    }
}
