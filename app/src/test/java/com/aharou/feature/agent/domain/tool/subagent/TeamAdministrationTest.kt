package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.feature.agent.domain.subagent.*
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class TeamAdministrationTest {
    private fun team(admin: String = MAIN_AGENT_ADMIN) = AgentTeam("team", "purpose",
        listOf(TeamStage("stage", listOf(TeamTask(agent = "builder", prompt = "real task",
            writePaths = listOf("src/one.kt"), id = "task")), id = "stage")), "charter",
        admins = listOf(admin), members = listOf(TeamMemberRef("builder"), TeamMemberRef("reviewer")))

    @Test fun onlyMainManagedTeamRevealsCompleteStructure() {
        val details = TeamAdministration.detail(team(), true)
        assertEquals(setOf("name", "description", "charter", "admins", "members", "stages", "routes"), details.keys)
        val task = details.getValue("stages").jsonArray.single().jsonObject.getValue("tasks").jsonArray.single().jsonObject
        assertEquals("real task", task.getValue("prompt").jsonPrimitive.content)
        assertEquals("src/one.kt", task.getValue("writePaths").jsonArray.single().jsonPrimitive.content)
        for ((candidate, main) in listOf(team("builder") to true, team() to false)) {
            assertEquals(setOf("name", "description", "charter"), TeamAdministration.detail(candidate, main).keys)
        }
    }

    @Test fun assignmentPersistsThroughTheRealWorkspaceRepositoryAndReadsBackUnchangedAuthority() {
        val root = "~/workspace/.aharou/teams"
        val routed = team().let { source -> source.copy(
            stages = source.stages + TeamStage("finish", listOf(TeamTask(agent = "builder", prompt = "finish", id = "final")), id = "finish"),
            routes = listOf(TeamRoute(TeamFlowNode("stage", "task"), TeamFlowNode("finish", "final"), TeamRouteAction.EXECUTE))) }
        val files = mutableMapOf("$root/team.md" to AgentTeamParser.serialize(routed.toForm(AgentDefinitionScope.PROJECT)))
        val access = io.mockk.mockk<com.aharou.feature.workspace.domain.FileAccessProvider>(relaxed = true)
        io.mockk.every { access.forWorkspace("/fixture") } returns access
        io.mockk.every { access.checked() } returns access
        io.mockk.every { access.isDirectory(any()) } answers { firstArg<String>() == root }
        io.mockk.every { access.isFile(any()) } answers { files.containsKey(firstArg<String>()) }
        io.mockk.every { access.listFiles(root) } returns listOf(com.aharou.feature.workspace.domain.FileEntry("team.md", false, 1, 0, null, "rw"))
        io.mockk.every { access.readFile(any()) } answers { files.getValue(firstArg()) }
        io.mockk.every { access.writeFile(any(), any(), any(), any()) } answers { files[firstArg()] = secondArg() }
        val repository = AgentTeamRepository(access)
        val before = repository.listAll().single().team
        val tasks = TeamTasks(repository, io.mockk.mockk(), io.mockk.mockk(), io.mockk.mockk(), io.mockk.mockk(relaxed = true))
        val result = tasks.assign(mapOf("team" to kotlinx.serialization.json.JsonPrimitive("team"),
            "stageId" to kotlinx.serialization.json.JsonPrimitive("stage"), "taskId" to kotlinx.serialization.json.JsonPrimitive("task"),
            "member" to kotlinx.serialization.json.JsonPrimitive("reviewer")),
            com.aharou.feature.agent.domain.model.AgentContext(null, null, "/fixture", null), true)
        assertTrue(result is com.aharou.feature.agent.domain.tool.ToolResult.Success)
        val after = repository.listAll().single().team
        assertEquals("reviewer", after.stages.first().tasks.single().agent)
        assertEquals(before.stages.first().tasks.single().writePaths, after.stages.first().tasks.single().writePaths)
        assertEquals(before.stages.first().tasks.single().prompt, after.stages.first().tasks.single().prompt)
        assertEquals(1, before.routes.size)
        assertEquals(before.routes, after.routes)
        assertEquals(before.admins, after.admins)
        assertEquals(before.members, after.members)
    }

    @Test fun assignmentRebindsOnlyAnExistingTaskAndPreservesAuthority() {
        val source = team()
        val entry = AgentTeamEntry(source, AgentDefinitionScope.PROJECT)
        val form = requireNotNull(TeamAdministration.assign(entry, "stage", "task", "reviewer"))
        val task = form.stages.single().tasks.single()
        assertEquals("reviewer", task.agent)
        assertEquals("real task", task.prompt)
        assertEquals("src/one.kt", task.writePaths)
        val rebound = source.copy(stages = source.stages.map { it.copy(tasks = it.tasks.map { task -> task.copy(agent = "reviewer") }) })
        val enabled = listOf("builder", "reviewer").map { AgentDefinition(it, it, prompt = "role", filePath = "$it.md") }
        val plan = TeamRunner.build(rebound, "goal", enabled, 2) as TeamRunner.Plan.Ready
        assertEquals("reviewer", plan.pipeline.single().jsonObject.getValue("agent").jsonPrimitive.content)
        assertNull(TeamAdministration.assign(entry, "stage", "missing", "reviewer"))
        assertNull(TeamAdministration.assign(entry, "stage", "task", "outsider"))
        assertNull(TeamAdministration.assign(entry, "stage", "task", MAIN_AGENT_ADMIN))
        assertNull(TeamAdministration.assign(AgentTeamEntry(team("builder"), AgentDefinitionScope.PROJECT), "stage", "task", "reviewer"))
    }
}
