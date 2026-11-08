package com.aharou.feature.agent.domain.task

import com.aharou.feature.agent.data.task.ProjectTaskRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
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
class ProjectFlowDeletionTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<ProjectTaskRepository>()
    private val workflow = mockk<ProjectWorkflowRunner>()
    private lateinit var flows: ProjectFlowController

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        flows = ProjectFlowController(repository, Provider { workflow })
    }

    @After fun tearDown() {
        flows.state.value.projects.forEach { flows.stopProject(it.projectId, it.workspace) }
        flows.state.value.tasks.forEach { flows.cancelExecution(it.taskId, it.workspace) }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test fun deletionWaitsForAllCleanupAndKeepsOtherWorkspaceRunning() = runTest(dispatcher) {
        val cleanup = mapOf("project" to CompletableDeferred<Unit>(), "t" to CompletableDeferred<Unit>())
        val cancelled = mutableSetOf<String>()
        coEvery { workflow.run("p", "/first", any()) } coAnswers {
            val kind = thirdArg<String?>() ?: "project"
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { cancelled += kind; cleanup.getValue(kind).await() }
            }
        }
        coEvery { workflow.run("p", "/other") } coAnswers { awaitCancellation() }
        flows.runProjectTask(ProjectTask(id = "t", workspace = "/first", title = "t", requirement = "r", projectId = "p"))
        runCurrent()
        flows.startProject("p", "/first")
        flows.startProject("p", "/other")
        runCurrent()
        val epoch = flows.stopEpoch("p", "/first")
        var deleted = false
        val deletion = launch { flows.withProjectStopped("p", "/first") { deleted = true } }
        runCurrent()
        assertEquals(setOf("project", "t"), cancelled)
        assertFalse(deleted)
        assertFalse(deletion.isCompleted)
        assertTrue(ProjectRunKey("/other", "p") in flows.state.value.projects)
        flows.startProject("p", "/first")
        flows.runProjectTask(ProjectTask(id = "new", workspace = "/first", title = "t", requirement = "r", projectId = "p"))
        runCurrent()
        coVerify(exactly = 1) { workflow.run("p", "/first") }
        coVerify(exactly = 0) { workflow.run("p", "/first", "new") }
        cleanup.getValue("project").complete(Unit)
        runCurrent()
        assertFalse(deleted)
        assertFalse(deletion.isCompleted)
        cleanup.getValue("t").complete(Unit)
        runCurrent()
        assertTrue(deleted)
        assertTrue(deletion.isCompleted)
        assertFalse(ProjectRunKey("/first", "p") in flows.state.value.projects)
        assertTrue(flows.state.value.tasks.isEmpty())
        flows.continueProject("p", "/first", epoch)
        runCurrent()
        coVerify(exactly = 1) { workflow.run("p", "/first") }
    }

    @Test fun failedDeletionReleasesTheBarrierForRetry() = runTest(dispatcher) {
        coEvery { workflow.run("p", "/first") } coAnswers { awaitCancellation() }
        try {
            flows.withProjectStopped("p", "/first") { error("WRITE_FAILED") }
            fail("write failure must propagate")
        } catch (failure: IllegalStateException) {
            assertEquals("WRITE_FAILED", failure.message)
        }
        flows.startProject("p", "/first")
        runCurrent()
        coVerify(exactly = 1) { workflow.run("p", "/first") }
        var deleted = false
        flows.withProjectStopped("p", "/first") { deleted = true }
        assertTrue(deleted)
        assertTrue(flows.state.value.projects.isEmpty())
    }
}
