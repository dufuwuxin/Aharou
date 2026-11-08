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
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
    private val sessions = mockk<SessionUseCase>()
    private val workspaces = mockk<com.aharou.feature.workspace.data.repository.WorkspaceRepository>()
    private val runner = mockk<AgentTurnRunner>()
    private lateinit var flows: com.aharou.feature.agent.domain.task.ProjectFlowController
    private lateinit var viewModel: ProjectTaskViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { workspaces.workspaces } returns MutableStateFlow(emptyList())
        coEvery { workspaces.activateProjectWorkspace(any(), any(), any()) } returns true
        every { repository.snapshots } returns MutableStateFlow(emptyMap())
        coEvery { repository.load(any()) } returns ProjectTaskSnapshot()
        coEvery { repository.createOwnedProject(any(), any(), any(), any()) } answers {
            ProjectPlanOutcome(ProjectPlan(id = arg(3), workspace = "/owned/${arg<String>(3)}", title = firstArg(), requirements = secondArg()), ruleFallback = true)
        }
        coEvery { repository.appendRequirements(any(), any(), any()) } answers {
            ProjectPlanOutcome(ProjectPlan(id = firstArg(), workspace = thirdArg(), title = "p", requirements = secondArg()), ruleFallback = false)
        }
        coEvery { repository.supplement(any(), any(), any()) } returns false
        coEvery { workflow.members() } returns listOf("member")
        flows = com.aharou.feature.agent.domain.task.ProjectFlowController(repository, javax.inject.Provider { workflow })
        viewModel = ProjectTaskViewModel(repository, sessions, runner, workflow, flows, javax.inject.Provider { workspaces })
    }

    @After fun tearDown() {
        flows.state.value.projects.forEach { flows.stopProject(it.projectId, it.workspace) }
        flows.state.value.tasks.forEach { flows.cancelExecution(it.taskId, it.workspace) }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test fun deletionUsesProjectWorkspaceAndKeepsConversations() = runTest(dispatcher) {
        val project = ProjectPlan(id = "owned", workspace = "/owned", title = "p", requirements = "r")
        (repository.snapshots as MutableStateFlow<Map<String, ProjectTaskSnapshot>>).value = mapOf("/owned" to ProjectTaskSnapshot(projects = listOf(project)))
        coEvery { repository.deleteProject("owned", "/owned") } returns true
        viewModel.workspace("/other"); runCurrent()
        viewModel.deleteProject("owned"); runCurrent()
        coVerify(exactly = 1) { repository.deleteProject("owned", "/owned") }
        coVerify(exactly = 0) { repository.deleteProject("owned", "/other") }
    }

    @Test fun deletionWaitsForRunningProjectCleanupBeforeRemovingIt() = runTest(dispatcher) {
        val project = ProjectPlan(id = "p", workspace = "/first", title = "p", requirements = "r")
        val task = projectTask
        val snapshot = ProjectTaskSnapshot(projects = listOf(project), tasks = listOf(task))
        (repository.snapshots as MutableStateFlow<Map<String, ProjectTaskSnapshot>>).value = mapOf("/first" to snapshot)
        coEvery { repository.load("/first") } returns snapshot
        coEvery { repository.deleteProject("p", "/first") } returns true
        val finish = kotlinx.coroutines.CompletableDeferred<Unit>()
        var cancelled = false
        coEvery { workflow.run("p", "/first") } coAnswers {
            try { kotlinx.coroutines.awaitCancellation() } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { cancelled = true; finish.await() }
            }
        }
        viewModel.workspace("/first"); runCurrent(); viewModel.selectProject("p"); viewModel.select("t")
        viewModel.startProject("p"); runCurrent()
        org.junit.Assert.assertTrue(viewModel.canDeleteProject(project))
        viewModel.deleteProject("p"); runCurrent()
        org.junit.Assert.assertTrue(cancelled)
        coVerify(exactly = 0) { repository.deleteProject(any(), any()) }
        finish.complete(Unit); runCurrent()
        coVerify(exactly = 1) { repository.deleteProject("p", "/first") }
        org.junit.Assert.assertNull(viewModel.state.value.selectedProjectId)
        org.junit.Assert.assertNull(viewModel.state.value.selectedId)
        org.junit.Assert.assertTrue(flows.state.value.projects.isEmpty())
        org.junit.Assert.assertFalse(viewModel.state.value.busy)
    }

    @Test fun deletionWaitsForLocalExecutionAndKeepsSameTaskIdInOtherWorkspace() = runTest(dispatcher) {
        val firstTask = projectTask.copy(status = com.aharou.feature.agent.domain.task.ProjectTaskStatus.RUNNING)
        val otherTask = firstTask.copy(workspace = "/other", projectId = "other")
        val stores = mapOf(
            "/first" to ProjectTaskSnapshot(tasks = listOf(firstTask), projects = listOf(ProjectPlan(id = "p", workspace = "/first", title = "p", requirements = "r"))),
            "/other" to ProjectTaskSnapshot(tasks = listOf(otherTask), projects = listOf(ProjectPlan(id = "other", workspace = "/other", title = "q", requirements = "r")))
        )
        (repository.snapshots as MutableStateFlow<Map<String, ProjectTaskSnapshot>>).value = stores
        coEvery { repository.load(any()) } answers { stores.getValue(firstArg()) }
        coEvery { repository.save(any()) } answers { firstArg() }
        coEvery { runner.createSessionEntity(any()) } answers { rootSession("session-" + firstArg<String>(), firstArg()) }
        coEvery { sessions.upsertSession(any()) } returns Unit
        val firstStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val otherStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val cleanup = kotlinx.coroutines.CompletableDeferred<Unit>()
        val deleted = kotlinx.coroutines.CompletableDeferred<Unit>()
        var firstCancelled = false
        var otherCancelled = false
        coEvery { repository.deleteProject("p", "/first") } answers { deleted.complete(Unit); true }
        viewModel.workspace("/first"); runCurrent()
        viewModel.execute(firstTask, true) { _, _, _ ->
            firstStarted.complete(Unit)
            try { kotlinx.coroutines.awaitCancellation() } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { firstCancelled = true; cleanup.await() }
            }
        }
        firstStarted.await()
        viewModel.workspace("/other"); runCurrent()
        viewModel.execute(otherTask, true) { _, _, _ ->
            otherStarted.complete(Unit)
            try { kotlinx.coroutines.awaitCancellation() } finally { otherCancelled = true }
        }
        otherStarted.await()
        viewModel.selectProject("other"); viewModel.select("t")
        viewModel.deleteProject("p"); runCurrent()
        org.junit.Assert.assertTrue(firstCancelled)
        org.junit.Assert.assertFalse(otherCancelled)
        org.junit.Assert.assertFalse(deleted.isCompleted)
        coVerify(exactly = 0) { repository.deleteProject(any(), any()) }
        cleanup.complete(Unit)
        deleted.await(); runCurrent()
        org.junit.Assert.assertFalse(otherCancelled)
        org.junit.Assert.assertEquals("other", viewModel.state.value.selectedProjectId)
        org.junit.Assert.assertEquals("t", viewModel.state.value.selectedId)
        viewModel.cancelExecution("t")
        viewModel.state.first { it.activeTaskIds.isEmpty() }
    }

    @Test fun failedDeletionKeepsTheProjectAndSelection() = runTest(dispatcher) {
        val project = ProjectPlan(id = "p", workspace = "/first", title = "p", requirements = "r")
        (repository.snapshots as MutableStateFlow<Map<String, ProjectTaskSnapshot>>).value = mapOf("/first" to ProjectTaskSnapshot(projects = listOf(project)))
        coEvery { repository.deleteProject("p", "/first") } throws IllegalStateException("WRITE_FAILED")
        viewModel.workspace("/first"); runCurrent(); viewModel.selectProject("p")
        viewModel.deleteProject("p"); runCurrent()
        org.junit.Assert.assertEquals(listOf(project), viewModel.state.value.projects)
        org.junit.Assert.assertEquals("p", viewModel.state.value.selectedProjectId)
        org.junit.Assert.assertEquals("WRITE_FAILED", viewModel.state.value.error)
        org.junit.Assert.assertTrue(viewModel.canDeleteProject(project))
    }

    @Test fun oneProjectStartIsIdempotentAndDoesNotFollowWorkspaceSwitch() = runTest(dispatcher) {
        val finish = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { workflow.run("p", "/first") } coAnswers { finish.await() }
        viewModel.workspace("/first"); runCurrent()
        viewModel.startProject("p"); viewModel.startProject("p")
        viewModel.workspace("/second"); runCurrent()
        org.junit.Assert.assertTrue(viewModel.state.value.activeProjectIds.isEmpty())
        coVerify(exactly = 1) { workflow.run("p", "/first") }
        coVerify(exactly = 0) { workflow.run("p", "/second") }
        viewModel.workspace("/first"); runCurrent()
        viewModel.stopProject("p"); runCurrent()
        org.junit.Assert.assertTrue(viewModel.state.value.activeProjectIds.isEmpty())
    }

    @Test fun queuedProjectCreationKeepsTheSubmittingWorkspace() = runTest(dispatcher) {
        viewModel.workspace("/first")
        runCurrent()
        viewModel.createProject("project", "requirement")
        viewModel.workspace("/second")
        runCurrent()
        coVerify(exactly = 1) { repository.createOwnedProject("project", "requirement", "/first", any()) }
        coVerify(exactly = 0) { repository.createOwnedProject(any(), any(), "/second", any()) }
    }

    @Test fun aPlannedProjectStartsItsFlowOnceSetupIsReleasedAndRecordsTheFallback() = runTest(dispatcher) {
        val finish = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { workflow.run(any(), match { it.startsWith("/owned/") }) } coAnswers { finish.await() }
        viewModel.workspace("/first"); runCurrent()
        viewModel.createProject("project", "requirement"); runCurrent()
        val state = viewModel.state.value
        val id = requireNotNull(state.selectedProjectId)
        org.junit.Assert.assertFalse(state.busy)
        org.junit.Assert.assertTrue(state.planningProjectIds.isEmpty())
        org.junit.Assert.assertEquals(setOf(id), state.ruleFallbackProjectIds)
        org.junit.Assert.assertEquals(setOf(id), state.activeProjectIds)
        coVerify(exactly = 1) { workflow.run(id, "/owned/$id") }
        viewModel.stopProject(id); runCurrent()
    }

    @Test fun lateCreationContinuesInOwnedWorkspaceWithoutTakingNewSelection() = runTest(dispatcher) {
        val result = kotlinx.coroutines.CompletableDeferred<ProjectPlanOutcome>()
        coEvery { repository.createOwnedProject(any(), any(), any(), any()) } coAnswers { result.await() }
        coEvery { workflow.run(any(), "/owned/new") } coAnswers { kotlinx.coroutines.awaitCancellation() }
        viewModel.workspace("/first"); runCurrent()
        viewModel.createProject("same", "requirements"); runCurrent()
        viewModel.workspace("/second"); viewModel.selectProject("other"); runCurrent()
        result.complete(ProjectPlanOutcome(ProjectPlan("new", "/owned/new", "same", "requirements", workspaceOwnership = "owned"), false)); runCurrent()
        org.junit.Assert.assertEquals("/second", viewModel.state.value.workspace)
        org.junit.Assert.assertEquals("other", viewModel.state.value.selectedProjectId)
        coVerify(exactly = 0) { workspaces.activateProjectWorkspace(any(), any(), any()) }
        coVerify(exactly = 1) { workflow.run(any(), "/owned/new") }
    }

    @Test fun aggregatedProjectOpenLoadsTheActualWorkspaceBeforeNavigation() = runTest(dispatcher) {
        val project = ProjectPlan("p", "/actual", "project", "requirements")
        val snapshots = MutableStateFlow(mapOf("/actual" to ProjectTaskSnapshot(projects = listOf(project))))
        every { repository.snapshots } returns snapshots
        coEvery { repository.load("/actual") } returns snapshots.value.getValue("/actual")
        viewModel = ProjectTaskViewModel(repository, sessions, runner, workflow, flows, javax.inject.Provider { workspaces })
        viewModel.workspace("/other"); runCurrent()
        var opened = false
        viewModel.openProject("p") { opened = true }; runCurrent()
        org.junit.Assert.assertTrue(opened)
        org.junit.Assert.assertEquals("/actual", viewModel.state.value.workspace)
        org.junit.Assert.assertEquals("p", viewModel.state.value.selectedProjectId)
        coVerify(exactly = 1) { workspaces.activateProjectWorkspace("/actual", "/other", any()) }
    }

    @Test fun explicitContinuationUsesTheLegacySourceAndSelectsOnlyTheNewOwnedProject() = runTest(dispatcher) {
        val original = ProjectPlan("legacy", "/legacy", "same title", "requirements")
        val snapshots = MutableStateFlow(mapOf("/legacy" to ProjectTaskSnapshot(projects = listOf(original))))
        every { repository.snapshots } returns snapshots
        coEvery { repository.continueInOwnedWorkspace("legacy", "/legacy", any()) } answers {
            ProjectPlanOutcome(ProjectPlan(thirdArg(), "/continued", "same title", "requirements",
                workspaceOwnership = "owned", continuationSourceProjectId = "legacy"), false)
        }
        coEvery { workflow.run(any(), "/continued") } coAnswers { kotlinx.coroutines.awaitCancellation() }
        viewModel = ProjectTaskViewModel(repository, sessions, runner, workflow, flows, javax.inject.Provider { workspaces })
        viewModel.workspace("/other"); runCurrent()
        var opened: String? = null
        viewModel.continueInOwnedWorkspace("legacy") { opened = it }; runCurrent()
        org.junit.Assert.assertNotNull(opened)
        org.junit.Assert.assertTrue(opened != original.id)
        org.junit.Assert.assertEquals(opened, viewModel.state.value.selectedProjectId)
        org.junit.Assert.assertEquals("/continued", viewModel.state.value.workspace)
        org.junit.Assert.assertEquals(original, snapshots.value.getValue("/legacy").projects.single())
        coVerify(exactly = 1) { repository.continueInOwnedWorkspace("legacy", "/legacy", any()) }
        coVerify(exactly = 0) { repository.createOwnedProject(any(), any(), any(), any()) }
    }

    @Test fun runningOrPlanningLegacyProjectRejectsExplicitContinuation() = runTest(dispatcher) {
        val original = ProjectPlan("legacy", "/legacy", "legacy", "requirements")
        every { repository.snapshots } returns MutableStateFlow(mapOf("/legacy" to ProjectTaskSnapshot(projects = listOf(original))))
        coEvery { workflow.run("legacy", "/legacy") } coAnswers { kotlinx.coroutines.awaitCancellation() }
        coEvery { repository.appendRequirements("legacy", any(), "/legacy") } coAnswers { kotlinx.coroutines.awaitCancellation() }
        viewModel = ProjectTaskViewModel(repository, sessions, runner, workflow, flows, javax.inject.Provider { workspaces })
        viewModel.workspace("/legacy"); runCurrent()
        viewModel.startProject("legacy"); runCurrent()
        viewModel.continueInOwnedWorkspace("legacy") { error("must not navigate") }; runCurrent()
        coVerify(exactly = 0) { repository.continueInOwnedWorkspace(any(), any(), any()) }
        viewModel.stopProject("legacy"); runCurrent()
        viewModel.appendRequirements("legacy", "more", "request"); runCurrent()
        viewModel.continueInOwnedWorkspace("legacy") { error("must not navigate") }; runCurrent()
        org.junit.Assert.assertFalse(viewModel.canContinueInOwnedWorkspace(original))
        coVerify(exactly = 0) { repository.continueInOwnedWorkspace(any(), any(), any()) }
        androidx.lifecycle.ViewModelStore().also { it.put("planning", viewModel) }.clear(); runCurrent()
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

    @Test fun continuingDeeperParentsKeepsTheirIdsAndSubmittingWorkspace() = runTest(dispatcher) {
        coEvery { repository.addTaskByDescription("p", any(), "/first", any()) } answers {
            ProjectPlanOutcome(ProjectPlan(id = "p", workspace = "/first", title = "p", requirements = "r"), ruleFallback = false)
        }
        coEvery { workflow.run("p", "/first") } coAnswers { kotlinx.coroutines.awaitCancellation() }
        viewModel.workspace("/first"); runCurrent()
        for (parent in listOf("level-3", "level-4", "level-8")) {
            viewModel.addTaskByDescription("p", "continue-$parent", parentId = parent)
            runCurrent()
            coVerify(exactly = 1) { repository.addTaskByDescription("p", "continue-$parent", "/first", parent) }
        }
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
        viewModel.workspace("/first"); runCurrent()
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
    @Test fun stoppingWhileSupplementIsPlanningRejectsItsLateContinuation() = runTest(dispatcher) {
        val saved = kotlinx.coroutines.CompletableDeferred<Boolean>()
        coEvery { workflow.run("p", "/first") } coAnswers { kotlinx.coroutines.awaitCancellation() }
        coEvery { repository.supplement("t", "late", "/first") } coAnswers { saved.await() }
        viewModel.workspace("/first"); runCurrent()
        viewModel.startProject("p"); runCurrent()
        viewModel.supplement(projectTask, "late"); runCurrent()
        viewModel.stopProject("p"); runCurrent()
        saved.complete(false); runCurrent()
        org.junit.Assert.assertTrue(flows.state.value.projects.isEmpty())
        coVerify(exactly = 1) { workflow.run("p", "/first") }
    }

    @Test fun clearingAndRecreatingTheViewModelKeepsSharedExecution() = runTest(dispatcher) {
        coEvery { workflow.run("p", "/first") } coAnswers { kotlinx.coroutines.awaitCancellation() }
        val store = androidx.lifecycle.ViewModelStore()
        store.put("project", viewModel)
        viewModel.workspace("/first"); runCurrent()
        viewModel.startProject("p"); runCurrent()
        store.clear(); runCurrent()
        org.junit.Assert.assertEquals(1, flows.state.value.projects.size)
        viewModel = ProjectTaskViewModel(repository, sessions, runner, workflow, flows, javax.inject.Provider { workspaces })
        viewModel.workspace("/first"); runCurrent()
        org.junit.Assert.assertEquals(setOf("p"), viewModel.state.value.activeProjectIds)
        coVerify(exactly = 1) { workflow.run("p", "/first") }
        viewModel.stopProject("p"); runCurrent()
        org.junit.Assert.assertTrue(flows.state.value.projects.isEmpty())
    }

    private fun rootSession(id: String, workspace: String = "/first", updated: Long = 1) =
        com.aharou.feature.agent.data.local.entity.ChatSessionEntity(id, id, 1, updated, workspacePath = workspace)

    @Test fun concurrentProjectBindingsCreateAndLinkExactlyOneRoot() = runTest(dispatcher) {
        var snapshot = ProjectTaskSnapshot(projects = listOf(ProjectPlan("p", "/first", "p", "requirements")))
        coEvery { repository.load("/first") } coAnswers { snapshot }
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val root = rootSession("root")
        coEvery { runner.createSessionEntity("/first") } coAnswers { gate.await(); root }
        coEvery { sessions.upsertSession(root) } returns Unit
        coEvery { sessions.getSessionById("root") } returns root
        coEvery { repository.linkProjectExecution("p", "/first", "root") } coAnswers {
            snapshot = snapshot.copy(projects = snapshot.projects.map { it.copy(flowSessionIds = listOf("root")) })
        }
        val first = async { viewModel.ensureProjectSession("p", "/first") }
        val second = async { viewModel.ensureProjectSession("p", "/first") }
        runCurrent(); gate.complete(Unit)
        org.junit.Assert.assertEquals("root", first.await())
        org.junit.Assert.assertEquals("root", second.await())
        coVerify(exactly = 1) { runner.createSessionEntity("/first"); sessions.upsertSession(root); repository.linkProjectExecution("p", "/first", "root") }
    }

    @Test fun projectBindingReusesNewestExistingRootAndRejectsChildOrOtherWorkspace() = runTest(dispatcher) {
        val ids = listOf("old", "latest", "child", "foreign", "deleted")
        coEvery { repository.load("/first") } returns ProjectTaskSnapshot(projects = listOf(ProjectPlan("p", "/first", "p", "r", flowSessionIds = ids)))
        coEvery { sessions.getSessionById("old") } returns rootSession("old")
        coEvery { sessions.getSessionById("latest") } returns rootSession("latest", updated = 2)
        coEvery { sessions.getSessionById("child") } returns rootSession("child", updated = 99).copy(parentId = "latest")
        coEvery { sessions.getSessionById("foreign") } returns rootSession("foreign", "/second", 100)
        coEvery { sessions.getSessionById("deleted") } returns null
        org.junit.Assert.assertEquals("latest", viewModel.ensureProjectSession("p", "/first"))
        coVerify(exactly = 0) { runner.createSessionEntity(any()); repository.linkProjectExecution(any(), any(), any()) }
    }

    @Test fun bindingKeepsItsWorkspaceWhileTheUiSwitches() = runTest(dispatcher) {
        coEvery { repository.load("/first") } returns ProjectTaskSnapshot(projects = listOf(ProjectPlan("p", "/first", "p", "r")))
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val root = rootSession("root")
        coEvery { runner.createSessionEntity("/first") } coAnswers { gate.await(); root }
        coEvery { sessions.upsertSession(root) } returns Unit
        coEvery { repository.linkProjectExecution("p", "/first", "root") } returns Unit
        viewModel.workspace("/first"); runCurrent()
        val binding = async { viewModel.ensureProjectSession("p", "/first") }; runCurrent()
        viewModel.workspace("/second"); runCurrent(); gate.complete(Unit)
        org.junit.Assert.assertEquals("root", binding.await())
        org.junit.Assert.assertEquals("/second", viewModel.state.value.workspace)
        coVerify(exactly = 0) { repository.linkProjectExecution(any(), "/second", any()) }
    }

    @Test fun failedProjectLinkDoesNotExposeOrRecreateTheUnboundSession() = runTest(dispatcher) {
        coEvery { repository.load("/first") } returns ProjectTaskSnapshot(projects = listOf(ProjectPlan("p", "/first", "p", "r")))
        val root = rootSession("root")
        coEvery { runner.createSessionEntity("/first") } returns root
        coEvery { sessions.upsertSession(root) } returns Unit
        coEvery { sessions.getSessionById("root") } returns root
        coEvery { repository.linkProjectExecution("p", "/first", "root") } throws IllegalStateException("WRITE_FAILED")
        val failure = try { viewModel.ensureProjectSession("p", "/first"); null } catch (error: IllegalStateException) { error }
        org.junit.Assert.assertEquals("WRITE_FAILED", failure?.message)
        coEvery { repository.linkProjectExecution("p", "/first", "root") } returns Unit
        org.junit.Assert.assertEquals("root", viewModel.ensureProjectSession("p", "/first"))
        coVerify(exactly = 1) { runner.createSessionEntity("/first"); sessions.upsertSession(root) }
    }

}
