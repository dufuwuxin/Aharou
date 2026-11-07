package com.aharou.feature.agent.domain.tool.session

import com.aharou.feature.agent.data.task.ProjectTaskRepository
import com.aharou.feature.agent.data.task.ProjectTaskSnapshot
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.task.ProjectPlan
import com.aharou.feature.agent.domain.task.ProjectTask
import com.aharou.feature.agent.domain.task.ProjectTaskChange
import com.aharou.feature.agent.domain.task.ProjectTaskGraph
import com.aharou.feature.agent.domain.task.ProjectTaskMemberRun
import com.aharou.feature.agent.domain.task.ProjectTaskStatus
import com.aharou.feature.agent.domain.tool.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ProjectTaskToolTest {
    private val flows = mockk<com.aharou.feature.agent.domain.task.ProjectFlowController>(relaxed = true)
    private fun task(id: String, parent: String? = null, status: ProjectTaskStatus = ProjectTaskStatus.READY,
        sessions: List<String> = emptyList(), by: String? = null) = ProjectTask(id = id, workspace = "w", projectId = "p",
        title = id, requirement = id, parentId = parent, status = status, processSessionIds = sessions,
        processMembers = sessions.associateWith { ProjectTaskMemberRun("", 1, 1) }, createdBySessionId = by,
        version = 1, order = 1, createdAt = 1)
    private val running = task("current", status = ProjectTaskStatus.RUNNING, sessions = listOf("member"))
    private fun request(action: String, parent: String? = null) = MemberTaskRequest(action, "Look into cache", "find the cache owner", parent)
    private fun rejected(tasks: List<ProjectTask>, session: String, request: MemberTaskRequest): String =
        try { ProjectTaskTool.memberChanges(tasks, session, request); fail("must reject"); "" } catch (e: ProjectTaskToolException) { e.code }

    @Test fun callersOutsideARunningTaskOrItsSubtreeAreRejected() {
        val other = task("other")
        assertEquals(ProjectTaskTool.NOT_IN_TASK, rejected(listOf(running, other), "stranger", request("add")))
        assertEquals(ProjectTaskTool.NOT_IN_TASK, rejected(listOf(running.copy(status = ProjectTaskStatus.REVIEW)), "member", request("add")))
        assertEquals("OUTSIDE_CURRENT_TASK", rejected(listOf(running, other), "member", request("add", "other")))
        assertEquals("OUTSIDE_CURRENT_TASK", rejected(listOf(running, other), "member", request("add", "missing")))
        val grandchild = task("grandchild", "child")
        val tasks = listOf(running, task("child", "current"), grandchild)
        assertEquals("grandchild", ProjectTaskTool.memberChanges(tasks, "member", request("add", "grandchild")).single().task.parentId)
    }

    @Test fun aSupersededOrFinishedMemberSessionIsRejected() {
        val rerun = running.copy(requirementsVersion = 2, processSessionIds = listOf("old", "member"), processMembers = mapOf(
            "old" to ProjectTaskMemberRun("", 1, 1, "SUPERSEDED"), "member" to ProjectTaskMemberRun("", 1, 2)))
        assertEquals(ProjectTaskTool.NOT_IN_TASK, rejected(listOf(rerun), "old", request("add")))
        assertEquals(1, ProjectTaskTool.memberChanges(listOf(rerun), "member", request("add")).size)
        assertEquals(ProjectTaskTool.NOT_IN_TASK, rejected(listOf(running.copy(requirementsVersion = 2)), "member", request("add")))
        val finished = running.copy(processMembers = mapOf("member" to ProjectTaskMemberRun("", 1, 1, "SUCCEEDED")))
        assertEquals(ProjectTaskTool.NOT_IN_TASK, rejected(listOf(finished), "member", request("add")))
        assertEquals(ProjectTaskTool.NOT_IN_TASK, try { ProjectTaskTool.outline(listOf(finished), "member", ""); "" }
            catch (e: ProjectTaskToolException) { e.code })
    }

    @Test fun outlineListsTasksLineByLineFiltersByQueryAndTruncates() {
        val child = task("child", "current").copy(title = "Cache layer", requirement = "Find the cache owner. Keep offline mode.",
            dependencyIds = listOf("current"))
        val tasks = listOf(running.copy(title = "Main", owner = "builder"), child)
        val full = ProjectTaskTool.outline(tasks, "member", "")
        val text = full.getValue("outline").jsonPrimitive.content
        assertTrue(text.contains("[RUNNING] Main (id=current, current)"))
        assertTrue(text.contains("  - OWNER: builder"))
        assertTrue(text.contains("  [READY] Cache layer (id=child)"))
        assertTrue(text.contains("    - REQUIREMENT: Keep offline mode."))
        assertTrue(text.contains("    - DEPENDENCY: Main"))
        assertEquals(2, full.getValue("tasks").jsonPrimitive.content.toInt())
        val found = ProjectTaskTool.outline(tasks, "member", "OFFLINE")
        val foundText = found.getValue("outline").jsonPrimitive.content
        assertFalse(foundText.contains("Main"))
        assertTrue(foundText.contains("REQUIREMENT: Keep offline mode."))
        assertFalse(foundText.contains("Find the cache owner"))
        assertEquals("OFFLINE", found.getValue("query").jsonPrimitive.content)
        val many = listOf(running) + (1..80).map { task("t$it", "current").copy(requirement = "x".repeat(400)) }
        val cut = ProjectTaskTool.outline(many, "member", "")
        val shown = cut.getValue("tasks").jsonPrimitive.content.toInt()
        val remaining = cut.getValue("remaining").jsonPrimitive.content.toInt()
        assertEquals(81, shown + remaining)
        assertTrue(remaining > 0)
        val cutText = cut.getValue("outline").jsonPrimitive.content
        assertTrue(cutText.length <= ProjectTaskTool.OUTLINE_TOTAL_MAX + 80)
        assertTrue(cutText.contains("$remaining more tasks not shown"))
        assertFalse(cutText.contains("x".repeat(ProjectTaskTool.OUTLINE_LINE_MAX + 1)))
    }

    @Test fun rootInputChoosesNewPlanOrExistingTaskWithoutTrustingArguments() = runBlocking {
        val repository = mockk<ProjectTaskRepository>()
        val project = ProjectPlan(id = "p", workspace = "w", title = "p", requirements = "r", flowSessionIds = listOf("root"))
        val snapshot = ProjectTaskSnapshot(version = 1, tasks = listOf(task("existing")), projects = listOf(project))
        every { repository.snapshots } returns MutableStateFlow(mapOf("w" to snapshot))
        coEvery { repository.load("w") } returns snapshot
        coEvery { repository.appendRequirements("p", "Add search", "w") } returns
            com.aharou.feature.agent.data.task.ProjectPlanOutcome(project, false)
        coEvery { repository.supplement("existing", "Use offline cache", "w") } returns false
        val tool = ProjectTaskTool(repository, flows)
        fun args(action: String, text: String, target: String? = null) = buildMap {
            put("action", JsonPrimitive(action)); put("requirement", JsonPrimitive(text))
            if (target != null) put("task_id", JsonPrimitive(target))
            put("project_id", JsonPrimitive("other")); put("workspace", JsonPrimitive("other"))
        }
        val root = AgentContext(null, null, "w", null, sessionId = "root")
        assertTrue(tool.executeWithContext(args("plan", "Add search"), root) is ToolResult.Success)
        assertTrue(tool.executeWithContext(args("supplement", "Use offline cache", "existing"), root) is ToolResult.Success)
        val rejected = tool.executeWithContext(args("supplement", "Use offline cache", "other"), root)
        assertTrue(rejected is ToolResult.Error)
        assertTrue(tool.executeWithContext(args("plan", "Add search"), root.copy(projectRoot = "other")) is ToolResult.Error)
        coVerify(exactly = 1) { repository.appendRequirements("p", "Add search", "w") }
        coVerify(exactly = 1) { repository.supplement("existing", "Use offline cache", "w") }
        coVerify(exactly = 0) { repository.supplement("other", any(), any()) }
        io.mockk.verify(exactly = 2) { flows.continueProject("p", "w", 0L) }
        io.mockk.verify(exactly = 0) { flows.continueProject(any(), "other", any()) }
        val planning = root.copy(mode = com.aharou.feature.agent.domain.model.AgentMode.PLAN)
        assertTrue(tool.executeWithContext(args("plan", "Add search"), planning) is ToolResult.Success)
        assertTrue(tool.executeWithContext(args("supplement", "Use offline cache", "existing"), planning) is ToolResult.Success)
        io.mockk.verify(exactly = 2) { flows.continueProject(any(), any(), any()) }
        coEvery { repository.appendRequirements("p", "Add search", "w") } throws IllegalStateException("WRITE_FAILED")
        coEvery { repository.supplement("existing", "Use offline cache", "w") } throws IllegalStateException("WRITE_FAILED")
        assertTrue(tool.executeWithContext(args("plan", "Add search"), root) is ToolResult.Error)
        assertTrue(tool.executeWithContext(args("supplement", "Use offline cache", "existing"), root) is ToolResult.Error)
        assertTrue(tool.executeWithContext(mapOf("action" to JsonPrimitive("outline")), root) is ToolResult.Success)
        assertTrue(tool.executeWithContext(args("plan", ""), root) is ToolResult.Error)
        io.mockk.verify(exactly = 2) { flows.continueProject(any(), any(), any()) }
    }

    @Test fun rootContinuationCapturesStopEpochBeforeSaving() = runBlocking {
        val repository = mockk<ProjectTaskRepository>()
        val project = ProjectPlan(id = "p", workspace = "w", title = "p", requirements = "r", flowSessionIds = listOf("root"))
        val snapshot = ProjectTaskSnapshot(tasks = listOf(task("existing")), projects = listOf(project))
        every { repository.snapshots } returns MutableStateFlow(mapOf("w" to snapshot))
        coEvery { repository.load("w") } returns snapshot
        var stopEpoch = 5L
        every { flows.stopEpoch("p", "w") } answers { stopEpoch }
        coEvery { repository.supplement("existing", "late", "w") } coAnswers { stopEpoch++; false }
        val result = ProjectTaskTool(repository, flows).executeWithContext(mapOf(
            "action" to JsonPrimitive("supplement"), "task_id" to JsonPrimitive("existing"), "requirement" to JsonPrimitive("late")),
            AgentContext(null, null, "w", null, sessionId = "root"))
        assertTrue(result is ToolResult.Success)
        io.mockk.verify(exactly = 1) { flows.continueProject("p", "w", 5L) }
        io.mockk.verify(exactly = 0) { flows.continueProject("p", "w", 6L) }
    }

    @Test fun outlineThroughTheToolReadsWithoutWriting() = runBlocking {
        val repository = mockk<ProjectTaskRepository>()
        val store = ProjectTaskSnapshot(version = 1, tasks = listOf(running), projects = listOf(ProjectPlan(id = "p", workspace = "w", title = "p", requirements = "r")))
        every { repository.snapshots } returns MutableStateFlow(mapOf("w" to store))
        coEvery { repository.load("w") } returns store
        val result = ProjectTaskTool(repository, flows).executeWithContext(mapOf("action" to JsonPrimitive("outline")),
            AgentContext(null, null, "w", null, sessionId = "member"))
        assertTrue((result as ToolResult.Success).data.jsonObject.getValue("outline").jsonPrimitive.content.contains("(id=current, current)"))
        coVerify(exactly = 0) { repository.transact(any(), any()) }
        coVerify(exactly = 0) { repository.save(any()) }
        io.mockk.verify(exactly = 0) { flows.continueProject(any(), any(), any()) }
    }

    @Test fun memberAddedTasksAndTreeDepthAreCapped() {
        val added = (1..ProjectTaskTool.MAX_MEMBER_TASKS).map { task("m$it", "current", by = "earlier") }
        assertEquals("MEMBER_TASK_LIMIT", rejected(listOf(running) + added, "member", request("branch")))
        val chain = (1..ProjectTaskGraph.MAX_TREE_DEPTH).map { task("d$it", if (it == 1) "current" else "d${it - 1}") }
        assertEquals("TASK_DEPTH_LIMIT", rejected(listOf(running) + chain, "member", request("add", "d${ProjectTaskGraph.MAX_TREE_DEPTH}")))
        val ancestors = (0 until ProjectTaskGraph.MAX_TREE_DEPTH).map { task(if (it == 0) "root" else "a$it", if (it == 0) null else if (it == 1) "root" else "a${it - 1}") }
        val anchored = running.copy(parentId = ancestors.last().id)
        assertEquals("TASK_DEPTH_LIMIT", rejected(ancestors + anchored, "member", request("branch")))
    }

    @Test fun branchMakesTheCurrentTaskDependOnTheNewTaskAndAddDoesNot() {
        val branch = ProjectTaskTool.memberChanges(listOf(running), "member", request("branch"), now = 5)
        val (child, current) = branch
        assertEquals("current", child.task.parentId)
        assertEquals("member", child.task.createdBySessionId)
        assertEquals(ProjectTaskStatus.READY, child.task.status)
        assertEquals(listOf(child.task.id), current.task.dependencyIds)
        val revised = ProjectTaskGraph.revise(running, current.task, 3)
        assertEquals(running.requirementsVersion + 1, revised.requirementsVersion)
        assertEquals(ProjectTaskStatus.READY, revised.status)
        assertNull(ProjectTaskGraph.validate(listOf(child.task, revised)))
        val add = ProjectTaskTool.memberChanges(listOf(running), "member", request("add"))
        assertEquals(1, add.size)
        assertEquals("current", add.single().task.parentId)
    }

    @Test fun theToolDerivesTheWorkspaceFromTheCallersRunningTask() = runBlocking {
        val repository = mockk<ProjectTaskRepository>()
        val snapshot = ProjectTaskSnapshot(version = 1, tasks = listOf(running), projects = listOf(ProjectPlan(id = "p", workspace = "w", title = "p", requirements = "r")))
        every { repository.snapshots } returns MutableStateFlow(mapOf("w" to snapshot))
        val staged = mutableListOf<ProjectTaskChange>()
        coEvery { repository.transact("w", any()) } answers {
            val changes = secondArg<(ProjectTaskSnapshot) -> List<ProjectTaskChange>>()(snapshot)
            staged += changes
            changes.map { it.task }
        }
        val tool = ProjectTaskTool(repository, flows)
        val args = mapOf("action" to JsonPrimitive("branch"), "title" to JsonPrimitive("Research"), "requirement" to JsonPrimitive("why"),
            "workspace" to JsonPrimitive("/elsewhere"))
        val result = tool.executeWithContext(args, AgentContext(null, null, "/elsewhere", null, sessionId = "member"))
        assertTrue(result is ToolResult.Success)
        val data = (result as ToolResult.Success).data.jsonObject
        assertEquals(staged[0].task.id, data.getValue("task_id").jsonPrimitive.content)
        assertTrue(data.getValue("instruction").jsonPrimitive.content.contains("End this turn now"))
        assertEquals(listOf(staged[0].task.id), staged[1].task.dependencyIds)
        val outsider = tool.executeWithContext(args, AgentContext(null, null, "w", null, sessionId = "someone-else"))
        assertEquals(ProjectTaskTool.NOT_IN_TASK, (outsider as ToolResult.Error).code)
    }
}
