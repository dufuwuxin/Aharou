package com.aharou.feature.agent.presentation

import com.aharou.feature.agent.data.task.ProjectTaskRepository
import com.aharou.feature.agent.data.task.ProjectTaskSnapshot
import com.aharou.feature.agent.domain.runner.AgentTurnRunner
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.task.ProjectPlan
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectTaskViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<ProjectTaskRepository>()
    private val workflow = mockk<com.aharou.feature.agent.domain.task.ProjectWorkflowRunner>()
    private lateinit var viewModel: ProjectTaskViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repository.snapshots } returns MutableStateFlow(emptyMap())
        coEvery { repository.load(any()) } returns ProjectTaskSnapshot()
        coEvery { repository.createProject(any(), any(), any()) } answers {
            ProjectPlan(workspace = thirdArg(), title = firstArg(), requirements = secondArg())
        }
        coEvery { repository.appendRequirements(any(), any(), any()) } returns Unit
        coEvery { workflow.members() } returns listOf("member")
        viewModel = ProjectTaskViewModel(repository, mockk<SessionUseCase>(), mockk<AgentTurnRunner>(), workflow)
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun oneProjectStartIsIdempotentAndDoesNotFollowWorkspaceSwitch() = runTest(dispatcher) {
        val finish = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { workflow.run("p", "/first") } coAnswers { finish.await() }
        viewModel.workspace("/first"); runCurrent()
        viewModel.startProject("p"); viewModel.startProject("p")
        viewModel.workspace("/second"); runCurrent()
        org.junit.Assert.assertTrue("p" in viewModel.state.value.activeProjectIds)
        coVerify(exactly = 1) { workflow.run("p", "/first") }
        coVerify(exactly = 0) { workflow.run("p", "/second") }
        viewModel.stopProject("p"); runCurrent()
        org.junit.Assert.assertTrue(viewModel.state.value.activeProjectIds.isEmpty())
    }

    @Test fun queuedProjectCreationKeepsTheSubmittingWorkspace() = runTest(dispatcher) {
        viewModel.workspace("/first")
        runCurrent()
        viewModel.createProject("project", "requirement")
        viewModel.workspace("/second")
        runCurrent()
        coVerify(exactly = 1) { repository.createProject("project", "requirement", "/first") }
        coVerify(exactly = 0) { repository.createProject(any(), any(), "/second") }
    }

    @Test fun queuedRequirementsKeepTheSubmittingWorkspace() = runTest(dispatcher) {
        viewModel.workspace("/first")
        runCurrent()
        viewModel.appendRequirements("project", "next", "request")
        viewModel.workspace("/second")
        runCurrent()
        coVerify(exactly = 1) { repository.appendRequirements("project", "next", "/first") }
        coVerify(exactly = 0) { repository.appendRequirements(any(), any(), "/second") }
    }
}
