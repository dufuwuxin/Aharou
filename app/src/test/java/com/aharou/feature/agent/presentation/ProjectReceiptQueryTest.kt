package com.aharou.feature.agent.presentation

import com.aharou.feature.agent.data.local.dao.ToolCallReceiptDao
import com.aharou.feature.agent.data.local.entity.ToolCallReceiptEntity
import com.aharou.feature.agent.data.task.ProjectTaskRepository
import com.aharou.feature.agent.domain.runner.AgentTurnRunner
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.task.ProjectFlowController
import com.aharou.feature.agent.domain.task.ProjectWorkflowRunner
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class ProjectReceiptQueryTest {
    private fun viewModel(dao: ToolCallReceiptDao): ProjectTaskViewModel {
        val repository = mockk<ProjectTaskRepository>(relaxed = true)
        every { repository.snapshots } returns MutableStateFlow(emptyMap())
        val workflow = mockk<ProjectWorkflowRunner>(relaxed = true)
        coEvery { workflow.members() } returns emptyList()
        val flows = mockk<ProjectFlowController>(relaxed = true)
        every { flows.state } returns MutableStateFlow(com.aharou.feature.agent.domain.task.ProjectFlowState())
        val workspaces = mockk<WorkspaceRepository>(relaxed = true)
        every { workspaces.workspaces } returns MutableStateFlow(emptyList())
        return ProjectTaskViewModel(repository, mockk<SessionUseCase>(relaxed = true), mockk<AgentTurnRunner>(relaxed = true),
            workflow, flows, javax.inject.Provider { workspaces }, dao)
    }

    private fun receipt(id: Long) = ToolCallReceiptEntity(
        id = id, sessionId = "session-$id", toolName = "readFile", status = "SUCCESS", createdAt = id
    )

    @Test fun projectQueryPassesWorkspaceAndOnlyDistinctLinkedTaskSessions() = runTest {
        val dao = mockk<ToolCallReceiptDao>()
        val call = AtomicReference<Triple<String, List<String>, Int>>()
        every { dao.observeProject(any(), any(), any()) } answers {
            call.set(Triple(firstArg(), secondArg(), thirdArg()))
            flowOf(listOf(receipt(2), receipt(1)))
        }

        val rows = viewModel(dao).projectReceipts("/A", listOf("process-b", "task-a", "process-b", ""), 64).first()

        assertEquals(Triple("/A", listOf("process-b", "task-a"), 64), call.get())
        assertEquals(listOf(2L, 1L), rows.map { it.id })
        assertEquals(emptyList<ToolCallReceiptEntity>(), viewModel(dao).projectReceipts("/A", emptyList()).first())
        assertEquals(emptyList<ToolCallReceiptEntity>(), viewModel(dao).projectReceipts("", listOf("task-a")).first())
    }

    @Test fun longTaskHistoryIsChunkedBelowSQLiteParameterLimitAndRecombinedChronologically() = runTest {
        val dao = mockk<ToolCallReceiptDao>()
        val querySizes = mutableListOf<Int>()
        every { dao.observeProject(any(), any(), any()) } answers {
            val ids: List<String> = secondArg()
            val cap: Int = thirdArg()
            querySizes += ids.size
            assertTrue(ids.size <= 800)
            flowOf(ids.mapIndexed { index, _ -> receipt(index.toLong() + 1) }.take(cap))
        }

        val linked = (0..1604).map { "session-$it" }
        val rows = viewModel(dao).projectReceipts("/A", linked, 17).first()

        assertEquals(listOf(800, 800, 5), querySizes)
        assertEquals(17, rows.size)
        assertEquals(rows.map { it.id }.sortedDescending(), rows.map { it.id })
    }

}
