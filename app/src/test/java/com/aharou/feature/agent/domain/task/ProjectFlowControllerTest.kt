package com.aharou.feature.agent.domain.task

import com.aharou.feature.agent.data.task.ProjectTaskRepository
import com.aharou.feature.agent.data.task.ProjectTaskSnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectFlowControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<ProjectTaskRepository>()
    private val workflow = mockk<ProjectWorkflowRunner>()
    private lateinit var controller: ProjectFlowController
    private val task = ProjectTask(id = "t", workspace = "w", projectId = "p", title = "task", requirement = "work")

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        controller = ProjectFlowController(repository, Provider { workflow })
        coEvery { repository.load(any()) } returns ProjectTaskSnapshot(tasks = listOf(task))
        coEvery { workflow.run(any(), any(), any()) } coAnswers { awaitCancellation() }
    }

    @After fun tearDown() {
        controller.state.value.projects.forEach { controller.stopProject(it.projectId, it.workspace) }
        controller.state.value.tasks.forEach { controller.cancelExecution(it.taskId, it.workspace) }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test fun callerCancellationAndObserverUnsubscriptionDoNotOwnTheFlow() = runTest(dispatcher) {
        val caller = launch { controller.startProject("p", "w"); awaitCancellation() }
        val observer = launch { controller.state.collect {} }
        runCurrent()
        caller.cancelAndJoin(); observer.cancelAndJoin(); runCurrent()
        assertEquals(setOf(ProjectRunKey("w", "p")), controller.state.value.projects)
        controller.stopProject("p", "w"); runCurrent()
        assertTrue(controller.state.value.projects.isEmpty())
    }

    @Test fun cancellationBeforeTheJobBodyStartsStillClearsBothActiveSets() = runTest(dispatcher) {
        controller.startProject("p", "w")
        controller.stopProject("p", "w")
        controller.runProjectTask(task.copy(projectId = "other"))
        controller.cancelExecution("t", "w")
        runCurrent()
        assertTrue(controller.state.value.projects.isEmpty())
        assertTrue(controller.state.value.tasks.isEmpty())
    }

    @Test fun singleTaskSurvivesItsCallerAndRemainsExplicitlyCancellable() = runTest(dispatcher) {
        val caller = launch { controller.runProjectTask(task); awaitCancellation() }
        runCurrent(); caller.cancelAndJoin(); runCurrent()
        assertEquals(setOf(ProjectTaskRunKey("w", "t")), controller.state.value.tasks)
        controller.cancelExecution("t", "other"); runCurrent()
        assertFalse(controller.state.value.tasks.isEmpty())
        controller.cancelExecution("t", "w"); runCurrent()
        assertTrue(controller.state.value.tasks.isEmpty())
    }

    @Test fun equalProjectIdsInDifferentWorkspacesStartAndStopIndependently() = runTest(dispatcher) {
        controller.startProject("p", "w"); controller.startProject("p", "other"); runCurrent()
        assertEquals(2, controller.state.value.projects.size)
        controller.stopProject("p", "w"); runCurrent()
        assertEquals(setOf(ProjectRunKey("other", "p")), controller.state.value.projects)
        coVerify(exactly = 1) { workflow.run("p", "w", null) }
        coVerify(exactly = 1) { workflow.run("p", "other", null) }
    }

    @Test fun repeatedStartsAndLateChangesCoalesceToOneSuccessfulExtraPass() = runTest(dispatcher) {
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        coEvery { workflow.run("p", "w", null) } coAnswers { if (++calls == 1) finish.await() else awaitCancellation() }
        controller.startProject("p", "w"); controller.startProject("p", "w"); runCurrent()
        repeat(3) { controller.startProject("p", "w", true) }; runCurrent()
        assertEquals(1, calls)
        finish.complete(Unit); runCurrent()
        assertEquals(2, calls)
        assertEquals(setOf(ProjectRunKey("w", "p")), controller.state.value.projects)
    }

    @Test fun failedFlowPublishesAnErrorAndDoesNotUseItsRerunMarker() = runTest(dispatcher) {
        val fail = CompletableDeferred<Unit>()
        coEvery { workflow.run("p", "w", null) } coAnswers { fail.await(); error("MEMBER_FAILED") }
        controller.startProject("p", "w"); runCurrent()
        controller.startProject("p", "w", true); runCurrent()
        fail.complete(Unit); runCurrent()
        assertTrue(controller.state.value.projects.isEmpty())
        assertEquals("MEMBER_FAILED", controller.state.value.errors["w"])
        coVerify(exactly = 1) { workflow.run("p", "w", null) }
        coVerify(exactly = 0) { repository.load(any()) }
    }

    @Test fun deliveredTasksDoNotTriggerAnotherPass() = runTest(dispatcher) {
        val finish = CompletableDeferred<Unit>()
        coEvery { workflow.run("p", "w", null) } coAnswers { finish.await() }
        coEvery { repository.load("w") } returns ProjectTaskSnapshot(tasks = listOf(
            task.copy(status = ProjectTaskStatus.REVIEW, processRequirementsVersion = task.requirementsVersion)))
        controller.startProject("p", "w"); runCurrent()
        controller.startProject("p", "w", true); runCurrent()
        finish.complete(Unit); runCurrent()
        coVerify(exactly = 1) { workflow.run("p", "w", null) }
        assertTrue(controller.state.value.projects.isEmpty())
    }

    @Test fun stopClearsRerunAndDoesNotRestartACancellingJob() = runTest(dispatcher) {
        val cleanup = CompletableDeferred<Unit>()
        coEvery { workflow.run("p", "w", null) } coAnswers {
            try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
        }
        controller.startProject("p", "w"); runCurrent()
        controller.startProject("p", "w", true); runCurrent()
        controller.stopProject("p", "w"); controller.startProject("p", "w", true); runCurrent()
        cleanup.complete(Unit); runCurrent()
        coVerify(exactly = 1) { workflow.run("p", "w", null) }
        assertTrue(controller.state.value.projects.isEmpty())
    }

    @Test fun stopDuringLatePendingReadInvalidatesTheOldContinuation() = runTest(dispatcher) {
        val finish = CompletableDeferred<Unit>()
        val pending = CompletableDeferred<ProjectTaskSnapshot>()
        coEvery { workflow.run("p", "w", null) } coAnswers { finish.await() }
        coEvery { repository.load("w") } coAnswers { pending.await() }
        controller.startProject("p", "w"); runCurrent()
        controller.startProject("p", "w", true); runCurrent()
        finish.complete(Unit); runCurrent()
        controller.stopProject("p", "w"); runCurrent()
        pending.complete(ProjectTaskSnapshot(tasks = listOf(task))); runCurrent()
        coVerify(exactly = 1) { workflow.run("p", "w", null) }
        assertTrue(controller.state.value.projects.isEmpty())
    }

    @Test fun lateProducerFromBeforeStopCannotRestartButANewRequestCan() = runTest(dispatcher) {
        val beforeStop = controller.stopEpoch("p", "w")
        controller.startProject("p", "w"); runCurrent()
        controller.stopProject("p", "w"); runCurrent()
        controller.continueProject("p", "w", beforeStop); runCurrent()
        assertTrue(controller.state.value.projects.isEmpty())
        coVerify(exactly = 1) { workflow.run("p", "w", null) }
        controller.continueProject("p", "w", controller.stopEpoch("p", "w")); runCurrent()
        assertEquals(setOf(ProjectRunKey("w", "p")), controller.state.value.projects)
        coVerify(exactly = 2) { workflow.run("p", "w", null) }
    }

    @Test fun automaticUpdatesDoNotQueueAWholeFlowBehindASingleTaskButManualStartsDo() = runTest(dispatcher) {
        controller.runProjectTask(task); controller.runProjectTask(task); runCurrent()
        controller.startProject("p", "w", true); runCurrent()
        coVerify(exactly = 0) { workflow.run("p", "w", null) }
        controller.startProject("p", "w"); runCurrent()
        coVerify(exactly = 1) { workflow.run("p", "w", null) }
        coVerify(exactly = 1) { workflow.run("p", "w", "t") }
    }
}
