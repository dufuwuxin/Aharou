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
