package com.aharou.feature.agent.presentation

import com.aharou.feature.agent.data.task.ProjectPlanOutcome
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
        coEvery { repository.createProject(any(), any(), any(), any()) } answers {
            ProjectPlanOutcome(ProjectPlan(id = arg(3), workspace = thirdArg(), title = firstArg(), requirements = secondArg()), ruleFallback = true)
        }
        coEvery { repository.appendRequirements(any(), any(), any()) } answers {
            ProjectPlanOutcome(ProjectPlan(id = firstArg(), workspace = thirdArg(), title = "p", requirements = secondArg()), ruleFallback = false)
        }
        coEvery { repository.supplement(any(), any(), any()) } returns false
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
        coVerify(exactly = 1) { repository.createProject("project", "requirement", "/first", any()) }
        coVerify(exactly = 0) { repository.createProject(any(), any(), "/second", any()) }
    }

    @Test fun aPlannedProjectStartsItsFlowOnceSetupIsReleasedAndRecordsTheFallback() = runTest(dispatcher) {
        val finish = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { workflow.run(any(), "/first") } coAnswers { finish.await() }
        viewModel.workspace("/first"); runCurrent()
        viewModel.createProject("project", "requirement"); runCurrent()
        val state = viewModel.state.value
        val id = requireNotNull(state.selectedProjectId)
        org.junit.Assert.assertFalse(state.busy)
        org.junit.Assert.assertTrue(state.planningProjectIds.isEmpty())
        org.junit.Assert.assertEquals(setOf(id), state.ruleFallbackProjectIds)
        org.junit.Assert.assertEquals(setOf(id), state.activeProjectIds)
        coVerify(exactly = 1) { workflow.run(id, "/first") }
        viewModel.stopProject(id); runCurrent()
    }

    @Test fun addingASubtaskPassesTheChosenParentToThePlanner() = runTest(dispatcher) {
        coEvery { repository.addTaskByDescription("p", "subtask", "/first", "parent") } answers {
            ProjectPlanOutcome(ProjectPlan(id = "p", workspace = "/first", title = "p", requirements = "r"), ruleFallback = false)
        }
        coEvery { workflow.run("p", "/first") } coAnswers { kotlinx.coroutines.awaitCancellation() }
        viewModel.workspace("/first"); runCurrent()
        viewModel.addTaskByDescription("p", "subtask", parentId = "parent"); runCurrent()
        coVerify(exactly = 1) { repository.addTaskByDescription("p", "subtask", "/first", "parent") }
        org.junit.Assert.assertTrue("p" in viewModel.state.value.activeProjectIds)
        org.junit.Assert.assertTrue(viewModel.state.value.ruleFallbackProjectIds.isEmpty())
        viewModel.stopProject("p"); runCurrent()
    }

    private val projectTask = com.aharou.feature.agent.domain.task.ProjectTask(id = "t", workspace = "/first", title = "t", requirement = "t", projectId = "p")

    @Test fun workArrivingWhileTheFlowRunsGetsOneMorePassWhenItEnds() = runTest(dispatcher) {
        var finish = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { workflow.run("p", "/first") } coAnswers { finish.await() }
        coEvery { repository.load("/first") } returns ProjectTaskSnapshot(tasks = listOf(projectTask.copy(version = 1)),
            projects = listOf(ProjectPlan(id = "p", workspace = "/first", title = "p", requirements = "r")))
        viewModel.workspace("/first"); runCurrent()
        viewModel.startProject("p"); runCurrent()
        viewModel.supplement(projectTask, "late"); runCurrent()
        coVerify(exactly = 1) { workflow.run("p", "/first") }
        val first = finish
        finish = kotlinx.coroutines.CompletableDeferred()
        first.complete(Unit); runCurrent()
        coVerify(exactly = 2) { workflow.run("p", "/first") }
        org.junit.Assert.assertTrue("p" in viewModel.state.value.activeProjectIds)
        viewModel.stopProject("p"); runCurrent()
    }

    @Test fun aSingleTaskRunDoesNotQueueTheWholeProjectFlow() = runTest(dispatcher) {
        coEvery { workflow.run("p", "/first", "t") } coAnswers { kotlinx.coroutines.awaitCancellation() }
        viewModel.workspace("/first"); runCurrent()
        viewModel.runProjectTask(projectTask); runCurrent()
        viewModel.supplement(projectTask, "note"); runCurrent()
        coVerify(exactly = 1) { repository.supplement("t", "note", "/first") }
        coVerify(exactly = 0) { workflow.run("p", "/first", null) }
        org.junit.Assert.assertTrue(viewModel.state.value.activeProjectIds.isEmpty())
        viewModel.cancelExecution("t"); runCurrent()
    }

    @Test fun aManualStartDuringASingleTaskRunStillQueuesTheProjectFlow() = runTest(dispatcher) {
        coEvery { workflow.run("p", "/first", "t") } coAnswers { kotlinx.coroutines.awaitCancellation() }
        coEvery { workflow.run("p", "/first") } coAnswers { kotlinx.coroutines.awaitCancellation() }
        viewModel.workspace("/first"); runCurrent()
        viewModel.runProjectTask(projectTask); runCurrent()
        viewModel.startProject("p"); runCurrent()
        coVerify(exactly = 1) { workflow.run("p", "/first", null) }
        org.junit.Assert.assertTrue("p" in viewModel.state.value.activeProjectIds)
        viewModel.stopProject("p"); viewModel.cancelExecution("t"); runCurrent()
    }

    @Test fun theExtraPassAfterAFlowIsSkippedWhileASingleTaskRuns() = runTest(dispatcher) {
        val finish = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { workflow.run("p", "/first") } coAnswers { finish.await() }
        coEvery { workflow.run("p", "/first", "t") } coAnswers { kotlinx.coroutines.awaitCancellation() }
        viewModel.workspace("/first"); runCurrent()
        viewModel.startProject("p"); runCurrent()
        viewModel.supplement(projectTask, "late"); runCurrent()
        // The pending check after the flow is held open so a single-task run can start inside that window.
        val pending = kotlinx.coroutines.CompletableDeferred<ProjectTaskSnapshot>()
        coEvery { repository.load("/first") } coAnswers { pending.await() }
        finish.complete(Unit); runCurrent()
        viewModel.runProjectTask(projectTask); runCurrent()
        org.junit.Assert.assertTrue("t" in viewModel.state.value.activeTaskIds)
        pending.complete(ProjectTaskSnapshot(tasks = listOf(projectTask.copy(version = 1)),
            projects = listOf(ProjectPlan(id = "p", workspace = "/first", title = "p", requirements = "r")))); runCurrent()
        coVerify(exactly = 1) { workflow.run("p", "/first", null) }
        org.junit.Assert.assertTrue(viewModel.state.value.activeProjectIds.isEmpty())
        viewModel.cancelExecution("t"); runCurrent()
    }

    @Test fun aSupplementIsSavedThenContinuesAnIdleProjectButNotARunningOne() = runTest(dispatcher) {
        val finish = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { workflow.run("p", "/first") } coAnswers { finish.await() }
        val task = com.aharou.feature.agent.domain.task.ProjectTask(id = "t", workspace = "/first", title = "t", requirement = "t", projectId = "p")
        viewModel.workspace("/first"); runCurrent()
        viewModel.supplement(task, "also offline"); runCurrent()
        coVerify(exactly = 1) { repository.supplement("t", "also offline", "/first") }
        org.junit.Assert.assertTrue("p" in viewModel.state.value.activeProjectIds)
        viewModel.supplement(task, "and dark mode"); runCurrent()
        coVerify(exactly = 1) { repository.supplement("t", "and dark mode", "/first") }
        coVerify(exactly = 1) { workflow.run("p", "/first") }
        viewModel.stopProject("p"); runCurrent()
    }

    @Test fun runningOneProjectTaskUsesTheProjectFlowForThatTaskOnly() = runTest(dispatcher) {
        val finish = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { workflow.run("p", "/first", "t") } coAnswers { finish.await() }
        val task = com.aharou.feature.agent.domain.task.ProjectTask(id = "t", workspace = "/first", title = "t", requirement = "t", projectId = "p")
        viewModel.runProjectTask(task); viewModel.runProjectTask(task); runCurrent()
        org.junit.Assert.assertTrue("t" in viewModel.state.value.activeTaskIds)
        coVerify(exactly = 1) { workflow.run("p", "/first", "t") }
        viewModel.cancelExecution("t"); runCurrent()
        org.junit.Assert.assertTrue(viewModel.state.value.activeTaskIds.isEmpty())
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
