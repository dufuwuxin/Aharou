package com.aharou.feature.agent.domain.tool.session

import com.aharou.feature.agent.data.task.ProjectTaskRepository
import com.aharou.feature.agent.data.task.ProjectTaskSnapshot
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.task.ProjectPlan
import com.aharou.feature.agent.domain.task.ProjectTask
import com.aharou.feature.agent.domain.task.ProjectTaskChange
import com.aharou.feature.agent.domain.task.ProjectTaskGraph
import com.aharou.feature.agent.domain.task.ProjectTaskStatus
import com.aharou.feature.agent.domain.tool.ToolResult
import io.mockk.coEvery
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
    private fun task(id: String, parent: String? = null, status: ProjectTaskStatus = ProjectTaskStatus.READY,
        sessions: List<String> = emptyList(), by: String? = null) = ProjectTask(id = id, workspace = "w", projectId = "p",
        title = id, requirement = id, parentId = parent, status = status, processSessionIds = sessions, createdBySessionId = by,
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
        val tool = ProjectTaskTool(repository)
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
