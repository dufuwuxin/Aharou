package com.aharou.feature.agent.domain.task

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ProjectWorkflowPlanTest {
    private fun task(id: String, order: Long = 0) = ProjectTask(id = id, workspace = "w", projectId = "p",
        title = id, requirement = id, order = order, createdAt = 1, version = 1)

    @Test fun requiredPredecessorRunsBeforeAnEarlierOrderedDependent() {
        val first = task("first", 2)
        val second = task("second", 1).copy(dependencyIds = listOf("first"))
        assertEquals("first", ProjectWorkflowPlan.next(listOf(second, first))?.id)
        val delivered = first.copy(processRequirementsVersion = 1, status = ProjectTaskStatus.REVIEW)
        assertEquals("second", ProjectWorkflowPlan.next(listOf(second, delivered))?.id)
        assertNull(delivered.acceptedRequirementsVersion)
    }

    @Test fun appendingNewWorkDoesNotRerunCurrentDeliveredRequirements() {
        val done = task("first").copy(processRequirementsVersion = 1, status = ProjectTaskStatus.REVIEW)
        assertNull(ProjectWorkflowPlan.next(listOf(done)))
        assertEquals("new", ProjectWorkflowPlan.next(listOf(done, task("new", 2)))?.id)
        assertEquals("first", ProjectWorkflowPlan.next(listOf(done.copy(requirementsVersion = 2), task("new", 2)))?.id)
    }

    @Test fun revokedOrBlockedDeliveryCannotBeSkippedByOldVersionProof() {
        val delivered = task("a").copy(status = ProjectTaskStatus.REVIEW, processRequirementsVersion = 1)
        assertTrue(ProjectWorkflowPlan.delivered(delivered))
        for (status in listOf(ProjectTaskStatus.READY, ProjectTaskStatus.BLOCKED, ProjectTaskStatus.DRAFT)) {
            val revoked = delivered.copy(status = status)
            assertFalse(ProjectWorkflowPlan.delivered(revoked))
            assertEquals("a", ProjectWorkflowPlan.next(listOf(revoked))?.id)
        }
    }

    @Test fun tasksSavedBeforeSupplementsExistDecodeWithEmptySupplements() {
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(ProjectTask.serializer(),
            """{"id":"t","workspace":"w","title":"old","requirement":"old","status":"REVIEW","requirementsVersion":3,"legacyField":1}""")
        assertTrue(old.supplements.isEmpty())
        assertNull(old.createdBySessionId)
        assertEquals(3L, old.requirementsVersion)
        assertFalse(ProjectWorkflowPlan.prompt(old, listOf(old), "r").contains("User supplements"))
        assertTrue(ProjectWorkflowPlan.prompt(old.copy(supplements = listOf("keep offline mode")), listOf(old), "r").contains("keep offline mode"))
    }

    @Test fun oldTasksDecodeWithoutInventingAMemberRun() {
        val old = Json.decodeFromString(ProjectTask.serializer(), """{"workspace":"w","title":"old","requirement":"old"}""")
        assertTrue(old.processSessionIds.isEmpty())
        assertNull(old.processRequirementsVersion)
        assertFalse(ProjectWorkflowPlan.delivered(old))
        val project = Json.decodeFromString(ProjectPlan.serializer(), """{"workspace":"w","title":"old","requirements":"unchanged"}""")
        assertTrue(project.flowSessionIds.isEmpty())
    }

    @Test fun participantsKeepRepeatedRolesAndOnlyDefaultWhenAllAreBlank() {
        assertEquals(listOf("reviewer", "reviewer", "builder"), ProjectWorkflowPlan.participants(
            task("a").copy(owner = " reviewer ", members = listOf("reviewer", " ", "builder"))))
        assertEquals(listOf("builder"), ProjectWorkflowPlan.participants(task("a").copy(members = listOf("builder"))))
        assertEquals(listOf(""), ProjectWorkflowPlan.participants(task("a").copy(members = listOf(" "))))
    }

    @Test fun changingParticipantsInvalidatesPreviousWorkflowDelivery() {
        val delivered = task("a").copy(status = ProjectTaskStatus.REVIEW, processRequirementsVersion = 1,
            processParticipants = listOf("builder"), owner = "builder")
        assertFalse(ProjectWorkflowPlan.delivered(delivered.copy(members = listOf("reviewer"))))
        assertFalse(ProjectWorkflowPlan.delivered(delivered.copy(owner = "reviewer")))
    }

    @Test fun memberRecordsRoundTripWithoutChangingLegacyDefaults() {
        val old = Json.decodeFromString(ProjectTask.serializer(), """{"workspace":"w","title":"old","requirement":"old"}""")
        assertTrue(old.processMembers.isEmpty())
        assertNull(old.processParticipants)
        val recorded = old.copy(processMembers = mapOf("s" to ProjectTaskMemberRun("builder", 1, 1, "TIMEOUT", "limit")))
        val repositoryJson = Json { encodeDefaults = true }
        assertEquals(recorded, repositoryJson.decodeFromString(ProjectTask.serializer(), repositoryJson.encodeToString(ProjectTask.serializer(), recorded)))
    }

    @Test fun laterParticipantsReceiveEarlierResults() {
        val a = task("a")
        assertFalse(ProjectWorkflowPlan.prompt(a, listOf(a), "req").contains("Earlier participants"))
        assertTrue(ProjectWorkflowPlan.prompt(a, listOf(a), "req", "reviewer", 2, listOf("[1: builder] done")).contains("[1: builder] done"))
    }

    @Test fun blockedRetryReusesOnlyTheUnbrokenLeadOfTheLatestAttempt() {
        fun record(role: String, ordinal: Int, outcome: String, version: Long = 1) = ProjectTaskMemberRun(role, ordinal, version, outcome)
        fun blocked(vararg runs: Pair<String, ProjectTaskMemberRun>) = task("a").copy(owner = "builder", members = listOf("reviewer"),
            status = ProjectTaskStatus.BLOCKED, processSessionIds = runs.map { it.first }, processMembers = runs.toMap())
        val builder = "m1" to record("builder", 1, "SUCCEEDED")
        val reviewer = "m2" to record("reviewer", 2, "SUCCEEDED")
        assertEquals(listOf("m1"), ProjectWorkflowPlan.reusableRuns(blocked(builder, "m2" to record("reviewer", 2, "FAILED"))))
        assertEquals(listOf("m1", "m2"), ProjectWorkflowPlan.reusableRuns(blocked(builder, reviewer)))
        // The older reviewer worked from the older builder output, so it must run again after a newer builder run.
        assertEquals(listOf("m3"), ProjectWorkflowPlan.reusableRuns(blocked(builder, reviewer, "m3" to record("builder", 1, "SUCCEEDED"))))
        assertEquals(listOf("m3"), ProjectWorkflowPlan.reusableRuns(blocked(builder, reviewer, "m3" to record("builder", 1, "SUCCEEDED"),
            "m4" to record("reviewer", 2, "FAILED"))))
        assertEquals(emptyList<String>(), ProjectWorkflowPlan.reusableRuns(blocked(builder, reviewer, "m3" to record("builder", 1, "FAILED"))))
        assertEquals(emptyList<String>(), ProjectWorkflowPlan.reusableRuns(blocked("m1" to record("builder", 1, "SUCCEEDED", version = 0))))
        assertEquals(emptyList<String>(), ProjectWorkflowPlan.reusableRuns(blocked("m1" to record("reviewer", 1, "SUCCEEDED"))))
        assertEquals(emptyList<String>(), ProjectWorkflowPlan.reusableRuns(blocked(builder, reviewer).copy(status = ProjectTaskStatus.REVIEW)))
    }

    @Test fun emptyAppendPreviewIsZeroAndDoesNotBuildInvalidDrafts() {
        val p = ProjectPlan(workspace = "w", title = "p", requirements = "old")
        assertEquals(0, ProjectPlanDraft.preview(p, ""))
        assertEquals(0, ProjectPlanDraft.preview(p, "  \n"))
        assertEquals(2, ProjectPlanDraft.preview(p, "1. new\n2. next"))
    }
}
