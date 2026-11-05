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
        assertEquals(recorded, Json.decodeFromString(ProjectTask.serializer(), Json.encodeToString(ProjectTask.serializer(), recorded)))
    }

    @Test fun emptyAppendPreviewIsZeroAndDoesNotBuildInvalidDrafts() {
        val p = ProjectPlan(workspace = "w", title = "p", requirements = "old")
        assertEquals(0, ProjectPlanDraft.preview(p, ""))
        assertEquals(0, ProjectPlanDraft.preview(p, "  \n"))
        assertEquals(2, ProjectPlanDraft.preview(p, "1. new\n2. next"))
    }
}
