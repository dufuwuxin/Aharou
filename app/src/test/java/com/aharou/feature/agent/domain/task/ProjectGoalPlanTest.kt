package com.aharou.feature.agent.domain.task

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ProjectGoalPlanTest {
    private val task = ProjectTask(workspace = "w", title = "t", requirement = "r")
    @Test fun legacyRecordsKeepDefaultsAndOriginalRequirements() {
        val old = Json.decodeFromString(ProjectPlan.serializer(), """{"workspace":"w","title":"old","requirements":"keep"}""")
        assertEquals("short", old.horizon)
        assertEquals("", old.overallGoal)
        assertEquals("", old.goalAcceptance)
        assertEquals("", old.goalCheckpoint)
        assertEquals(0L, old.goalRevision)
        assertEquals("keep", old.requirements)
    }
    @Test fun emptyProjectGoalRoundTripsWithoutDependingOnTaskIdentity() {
        val project = ProjectPlan(workspace = "w", title = "empty", horizon = "long", overallGoal = "ship",
            goalAcceptance = "verified", goalCheckpoint = "GOAL_ACCEPTANCE_REQUIRED", goalRevision = 3)
        val encoded = Json.encodeToString(ProjectPlan.serializer(), project)
        assertEquals(project, Json.decodeFromString(ProjectPlan.serializer(), encoded))
        assertFalse(encoded.contains("taskId"))
    }

    @Test fun horizonsAreFiniteAndUnknownValuesUseShortBudget() {
        assertEquals(8, ProjectGoalPlan.taskBudget("unknown"))
        assertTrue(ProjectGoalPlan.taskBudget("long") > ProjectGoalPlan.taskBudget("short"))
        assertTrue(ProjectGoalPlan.timeBudgetMs("long") <= 120L * 60_000L)
    }
    @Test fun reviewNeverBecomesProjectCompletion() {
        val review = task.copy(status = ProjectTaskStatus.REVIEW, processRequirementsVersion = task.requirementsVersion)
        assertEquals("TASK_ACCEPTANCE_REQUIRED", ProjectGoalPlan.checkpoint("goal", "proof", listOf(review)))
        assertEquals(ProjectTaskStatus.REVIEW, review.status)
        assertEquals("GOAL_CONFIRMATION_REQUIRED", ProjectGoalPlan.checkpoint("goal", "", listOf(review)))
        assertEquals("BUDGET_REACHED", ProjectGoalPlan.checkpoint("goal", "proof", listOf(review), true))
    }
    @Test fun noTasksMeansConfirmNextStageNotDone() {
        assertEquals("NEXT_STAGE_CONFIRMATION_REQUIRED", ProjectGoalPlan.checkpoint("goal", "proof", emptyList()))
    }
}
