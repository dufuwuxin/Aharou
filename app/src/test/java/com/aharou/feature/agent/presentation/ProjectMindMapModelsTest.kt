package com.aharou.feature.agent.presentation

import com.aharou.feature.agent.data.local.entity.ToolCallReceiptEntity
import com.aharou.feature.agent.domain.task.*
import com.aharou.feature.agent.presentation.component.*
import org.junit.Assert.*
import org.junit.Test

class ProjectMindMapModelsTest {
    private val project = ProjectPlan(id = "p", workspace = "/A", title = "Real project")
    private fun task(id: String = "t") = ProjectTask(id = id, workspace = "/A", projectId = "p", title = id,
        requirement = "Real requirement", order = 1)
    private fun receipt(id: Long, at: Long = 10, status: String = "SUCCESS", tool: String = "readFile",
        category: String = "TOOL", args: String = "") = ToolCallReceiptEntity(id = id, sessionId = "s", createdAt = at,
        status = status, toolName = tool, category = category, argsPreview = args)

    @Test fun projectAndWorkspaceAreBothRequiredAndStableTreeOrderIsUsed() {
        val root = task("root").copy(order = 5)
        val child = task("child").copy(parentId = "root", order = 0)
        val otherWorkspace = task("foreign").copy(workspace = "/B")
        val otherProject = task("other").copy(projectId = "q")
        assertEquals(listOf("root", "child"), ProjectMindMapModels.tasks(project,
            listOf(child, otherWorkspace, otherProject, root)).map { it.id })
    }

    @Test fun rolesComeOnlyFromRealTaskFieldsAndProcessKeysAreNotRoles() {
        val t = task().copy(owner = "Lead", members = listOf("", "Lead", "Android"),
            processMembers = mapOf("private-session-id" to ProjectTaskMemberRun("Reviewer", 1, 1)))
        assertEquals(listOf("Android", "Lead", "Reviewer"), ProjectMindMapModels.roles(listOf(t)))
        assertTrue(ProjectMindMapModels.related(t, "Reviewer"))
        assertFalse(ProjectMindMapModels.related(t, "private-session-id"))
        assertTrue(ProjectMindMapModels.roles(listOf(task())).isEmpty())
    }

    @Test fun executionAndProcessSessionsAreDistinctAndBlankSessionsAreRejected() {
        assertEquals(listOf("a", "b"), ProjectMindMapModels.sessions(task().copy(
            sessionIds = listOf("a", "", "a"), processSessionIds = listOf("b", "a"))))
    }

    @Test fun onlyCurrentAcceptedDoneTasksCountAndBranchesNeverCount() {
        val done = task().copy(status = ProjectTaskStatus.DONE, acceptance = "Accepted", evidenceRefs = listOf("artifact"),
            requirementsVersion = 2, acceptedRequirementsVersion = 2)
        assertTrue(ProjectMindMapModels.accepted(done))
        assertFalse(ProjectMindMapModels.accepted(done.copy(status = ProjectTaskStatus.REVIEW)))
        assertFalse(ProjectMindMapModels.accepted(done.copy(acceptedRequirementsVersion = 1)))
        assertFalse(ProjectMindMapModels.accepted(done.copy(acceptance = "")))
        assertFalse(ProjectMindMapModels.accepted(done.copy(evidenceRefs = emptyList())))
        assertFalse(ProjectMindMapModels.accepted(done.copy(isRequirementBranch = true)))
    }

    @Test fun deniedTakesPrecedenceAndDoesNotMatchFailedWhileUnknownIsNotSuccess() {
        val denied = receipt(1, category = "DENIED", status = "ERROR")
        assertEquals(MapReceiptOutcome.DENIED, ProjectMindMapModels.outcome(denied))
        assertFalse(ProjectMindMapModels.matches(denied, MapReceiptFilter.ERROR))
        assertTrue(ProjectMindMapModels.matches(denied, MapReceiptFilter.DENIED))
        assertEquals(MapReceiptOutcome.PARTIAL, ProjectMindMapModels.outcome(receipt(2, status = "PARTIAL")))
        assertEquals(MapReceiptOutcome.UNKNOWN, ProjectMindMapModels.outcome(receipt(3, status = "")))
        assertEquals(MapReceiptOutcome.UNKNOWN, ProjectMindMapModels.outcome(receipt(4, status = "FUTURE")))
    }

    @Test fun retriesRetainAllIdsAndSameTimestampSortsById() {
        val rows = listOf(receipt(3), receipt(2, status = "ERROR"), receipt(1, at = 1, category = "DENIED", status = ""))
        assertEquals(listOf(1L, 2L, 3L), ProjectMindMapModels.ordered(rows).map { it.id })
    }

    @Test fun shellAndUnknownActionsAreNotInventedFileOrMemoryReads() {
        assertEquals(MapReceiptKind.OTHER, ProjectMindMapModels.kind(receipt(1, tool = "Bash", args = "cat file")))
        assertEquals(MapReceiptKind.FILE, ProjectMindMapModels.kind(receipt(2, tool = "functions.editFile")))
        assertEquals(MapReceiptKind.WEB, ProjectMindMapModels.kind(receipt(3, tool = "websearch")))
        assertEquals(MapReceiptKind.MEMORY, ProjectMindMapModels.kind(receipt(4, tool = "memory", args = "{\"action\":\"read\"}")))
        assertEquals(MapReceiptKind.OTHER, ProjectMindMapModels.kind(receipt(5, tool = "memory", args = "{\"action\":\"save\"}")))
    }

    @Test fun storedMapArgumentsAndJsonBothClassifyMemoryReadWithoutClassifyingSave() {
        for (args in listOf("{action=\"read\", name=\"scope\"}", "{action=\"library_search\"}",
            "{\"action\": \"read\"}")) {
            assertEquals(args, MapReceiptKind.MEMORY, ProjectMindMapModels.kind(receipt(1, tool = "memory", args = args)))
        }
        assertEquals(MapReceiptKind.OTHER, ProjectMindMapModels.kind(receipt(2, tool = "memory", args = "{action=\"read_extra\"}")))
        assertEquals(MapReceiptKind.OTHER, ProjectMindMapModels.kind(receipt(3, tool = "memory", args = "{action=\"save\"}")))
    }

    @Test fun onlySuccessfulCompleteResultHunksAreShownAsFileChanges() {
        val json = """{"hunks":[{"start_line":3,"diff":"-old\n+new"}]}"""
        val row = receipt(1, tool = "editFile").copy(resultPreview = json)
        assertEquals(listOf("-old\n+new"), ProjectMindMapModels.diff(row))
        assertTrue(ProjectMindMapModels.diff(row.copy(status = "ERROR")).isEmpty())
        assertTrue(ProjectMindMapModels.diff(row.copy(category = "DENIED")).isEmpty())
        assertTrue(ProjectMindMapModels.diff(row.copy(toolName = "writeFile")).isEmpty())
        assertTrue(ProjectMindMapModels.diff(row.copy(resultPreview = json.dropLast(3) + "…")).isEmpty())
        assertTrue(ProjectMindMapModels.diff(row.copy(resultPreview = "success", argsPreview = json)).isEmpty())
    }

    @Test fun dependencyCyclesAreDetectedWithoutRecursionAndMissingReferencesAreSafe() {
        val a = task("a").copy(dependencyIds = listOf("b"))
        val b = task("b").copy(dependencyIds = listOf("a"))
        assertTrue(ProjectMindMapModels.cyclic(a, listOf(a, b)))
        assertFalse(ProjectMindMapModels.cyclic(a, listOf(a)))
    }

    @Test fun navigationReturnsToExistingSelectionRatherThanBuildingDependencyLoops() {
        val history = listOf("map", "role:Lead", "task:a", "task:b")
        assertEquals(listOf("map", "role:Lead", "task:a"), ProjectMindMapModels.navigate(history, "task:a"))
        assertEquals(history, ProjectMindMapModels.navigate(history, "task:b"))
        assertEquals(history + "task:c", ProjectMindMapModels.navigate(history, "task:c"))
    }
}
