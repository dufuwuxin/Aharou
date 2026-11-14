package com.aharou.feature.agent.domain.task

import com.aharou.feature.agent.domain.tool.ToolResult
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProjectTeamExecutionTest {
    private val selected = ProjectTeamExecution.Selection("builders", "sha256", "explicit:exact-name")
    private fun done(run: String? = "real-run") = ToolResult.Success(buildJsonObject {
        put("state", "done"); run?.let { put("runId", it) }
        put("outputs", buildJsonObject { put("builder", "changed file") })
    })
    private fun reject(block: () -> Unit): String = try { block(); error("accepted invalid receipt") }
        catch (failure: IllegalStateException) { assertFalse(failure.message == "accepted invalid receipt"); failure.message.orEmpty() }

    @Test fun invokesActualTeamCallbackAndBindsReceiptToVersion() = runBlocking {
        var called = 0
        val result = ProjectTeamExecution.execute(2000, current = {}, invoke = { called++; done() })
        val receipt = ProjectTeamExecution.receipt(result, selected, 7)
        assertEquals(1, called)
        assertTrue(receipt.first.contains(":v7:run:real-run"))
        val task = ProjectTask(workspace = "/w", projectId = "p", title = "task", requirement = "implement", status = ProjectTaskStatus.REVIEW,
            requirementsVersion = 7, processRequirementsVersion = 7, processParticipants = listOf(selected.participant), evidenceRefs = listOf(receipt.first))
        assertTrue(ProjectWorkflowPlan.delivered(task))
        assertNotEquals(ProjectTaskStatus.DONE, task.status)
    }
    @Test fun doneWithoutRunIdCannotBecomeReview() {
        assertTrue(reject { ProjectTeamExecution.receipt(done(null), selected, 1) }.contains("RUNID_MISSING"))
    }
    @Test fun blockedAndToolErrorStayFailures() {
        val blocked = ToolResult.Success(buildJsonObject { put("state", "blocked"); put("reason", "review rejected") })
        assertTrue(reject { ProjectTeamExecution.receipt(blocked, selected, 1) }.contains("review rejected"))
        assertTrue(reject { ProjectTeamExecution.receipt(ToolResult.Error("no team", "PROJECT_TEAM_UNAVAILABLE"), selected, 1) }.contains("PROJECT_TEAM_UNAVAILABLE"))
    }
    @Test fun cancellationAndVersionChangeCancelOriginalCall() = runBlocking {
        val started = CompletableDeferred<Unit>(); val stopped = CompletableDeferred<Unit>()
        var version = 1
        val running = async {
            ProjectTeamExecution.execute(2000, current = { check(version == 1) { "UPSTREAM_CHANGED" } }, invoke = {
                started.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            })
        }
        started.await(); running.cancelAndJoin(); assertTrue(stopped.isCompleted)
        var cleanup = false
        try {
            supervisorScope {
                val changed = async {
                    ProjectTeamExecution.execute(2000, current = { check(version == 1) { "UPSTREAM_CHANGED" } }, invoke = {
                        version = 2
                        try { awaitCancellation() } finally { cleanup = true }
                    })
                }
                changed.await()
            }
            fail("late version accepted")
        } catch (failure: IllegalStateException) { assertEquals("UPSTREAM_CHANGED", failure.message) }
        assertTrue(cleanup)
    }
    @Test fun explicitLegacyOwnersStayDirectWhileUnassignedAndNewProjectsUseTeam() {
        val project = ProjectPlan(id = "p", workspace = "/w", title = "legacy")
        val owned = ProjectTask(workspace = "/w", projectId = "p", title = "task", requirement = "implement", owner = "builder", members = listOf("reviewer"))
        assertFalse(ProjectTeamExecution.usesTeam(project, owned))
        assertEquals(listOf("builder", "reviewer"), ProjectWorkflowPlan.participants(owned))
        assertTrue(ProjectTeamExecution.usesTeam(project, owned.copy(owner = "", members = emptyList())))
        assertTrue(ProjectTeamExecution.usesTeam(project.copy(executionTeam = "auto"), owned))
    }
    @Test fun autoWithoutAvailableTeamIsRejectedAndEmptyOutputsAreRejected() {
        assertTrue(reject { ProjectTeamExecution.selection(ToolResult.Error("no available team", "PROJECT_TEAM_UNAVAILABLE")) }.contains("PROJECT_TEAM_UNAVAILABLE"))
        val empty = ToolResult.Success(buildJsonObject { put("state", "done"); put("runId", "run"); put("outputs", buildJsonObject {}) })
        assertTrue(reject { ProjectTeamExecution.receipt(empty, selected, 1) }.contains("OUTPUTS_MISSING"))
    }
}
