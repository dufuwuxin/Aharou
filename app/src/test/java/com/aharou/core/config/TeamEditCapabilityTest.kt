package com.aharou.core.config

import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class TeamEditCapabilityTest {
    @After fun reset() { TeamEditCapability.revoke() }
    @Test fun disabledByDefaultAndRevocationRejectsOldAddress() {
        TeamEditCapability.revoke()
        assertNull(TeamEditCapability.address.value)
        val address = TeamEditCapability.issue("/project/a")
        assertEquals("ok", TeamEditCapability.use(address, "/project/a") { "ok" })
        TeamEditCapability.revoke()
        assertTrue(runCatching { TeamEditCapability.use(address, "/project/a") { "not allowed" } }.isFailure)
    }
    @Test fun newGrantAndAnotherWorkspaceCannotReuseTheOldCapability() {
        val address = TeamEditCapability.issue("/project/a")
        assertTrue(runCatching { TeamEditCapability.use(address, "/project/b") { true } }.isFailure)
        TeamEditCapability.issue("/project/a")
        assertTrue(runCatching { TeamEditCapability.use(address, "/project/a") { true } }.isFailure)
    }
    @Test fun onlyTeamControlFieldsAreAllowedAndAddressEncodingRoundTrips() {
        val address = TeamEditCapability.issue("/project/a")
        for (scope in listOf("global", "project")) {
            for (field in listOf("teams.$scope:6162.routes", "teams.$scope:6162.nodes", "team.stages.$scope:6162:0.condition")) {
                assertEquals(field, TeamEditCapability.use(TeamEditCapability.childAddress(address, field), "/project/a") { it })
            }
        }
        val field = "teams.project:6162.routes"
        assertEquals(field, TeamEditCapability.use(TeamEditCapability.childAddress(address, field), "/project/a") { it })
        for (path in listOf("providers.a.api_key", "soul.name", "team.stages.project:6162:1.file", "teams.project:6162.remove")) {
            assertFalse(TeamEditCapability.allowed(path))
            assertTrue(runCatching { TeamEditCapability.use(TeamEditCapability.childAddress(address, path), "/project/a") { it } }.isFailure)
        }
    }
    @Test fun realChannelListsBothScopesAndPersistsConditionWithRevision() {
        val directory = java.nio.file.Files.createTempDirectory("team-channel").toFile()
        try {
            val provider = com.aharou.testutil.TestFileAccessProvider()
            val teamFiles = listOf("global", "project").associateWith { scope ->
                java.io.File(directory, "$scope.md").apply {
                    writeText(com.aharou.feature.agent.domain.subagent.AgentTeamParser.serialize(
                        com.aharou.feature.agent.domain.subagent.AgentTeamForm(name = scope,
                            admins = listOf("worker"),
                            members = listOf(com.aharou.feature.agent.domain.subagent.TeamMemberRef("worker")),
                            stages = listOf(com.aharou.feature.agent.domain.subagent.AgentTeamStageForm(
                                title = "stage", members = listOf("worker"), prompt = "deliver",
                                tasks = listOf(com.aharou.feature.agent.domain.subagent.AgentTeamTaskForm(agent = "worker", prompt = "deliver")))))))
                }
            }
            val repo = io.mockk.spyk(com.aharou.feature.agent.domain.subagent.AgentTeamRepository(provider))
            io.mockk.every { repo.forWorkspace("/project") } returns repo
            io.mockk.every { repo.listAll() } answers {
                teamFiles.map { (scope, file) -> com.aharou.feature.agent.domain.subagent.AgentTeamEntry(
                    com.aharou.feature.agent.domain.subagent.AgentTeamParser.parse(provider, file.path)!!,
                    com.aharou.feature.agent.domain.subagent.AgentDefinitionScope.valueOf(scope.uppercase())) }
            }
            val workflows = io.mockk.mockk<com.aharou.feature.agent.domain.teamworkflow.TeamWorkflowRepository>(relaxed = true)
            io.mockk.every { workflows.forWorkspace("/project") } returns workflows
            val fields = com.aharou.feature.agent.data.ConfigTeamControlFields(repo, workflows)
            val context = io.mockk.mockk<android.content.Context>()
            val address = TeamEditCapability.issue("/project")
            fun request(action: String, path: String = address, value: String? = null) =
                com.aharou.feature.agent.domain.tool.config.teamCapabilityRequest(context, action, path, value, "/project", fields)
            fun data(result: com.aharou.feature.agent.domain.tool.ToolResult) =
                (result as com.aharou.feature.agent.domain.tool.ToolResult.Success).data.jsonObject
            val listed = data(request("list")).getValue("fields").jsonArray.map { it.jsonObject }
            assertTrue(listed.any { it.getValue("field").jsonPrimitive.content.startsWith("teams.global:") })
            assertTrue(listed.any { it.getValue("field").jsonPrimitive.content.startsWith("teams.project:") })
            assertTrue(listed.all { "type" in it && "access" in it && "value" in it })
            for (condition in listed.filter { it.getValue("field").jsonPrimitive.content.endsWith(".condition") }) {
                val path = condition.getValue("address").jsonPrimitive.content
                val before = data(request("get", path))
                val edit = buildJsonObject { put("expectedRevision", before.getValue("revision")); put("value", "never") }.toString()
                assertTrue(data(request("set", path, edit)).getValue("applied").jsonPrimitive.boolean)
                assertEquals("never", data(request("get", path)).getValue("value").jsonPrimitive.content)
                assertTrue(request("set", path, edit) is com.aharou.feature.agent.domain.tool.ToolResult.Error)
            }
            assertTrue(teamFiles.values.all { com.aharou.feature.agent.domain.subagent.AgentTeamParser.parse(provider, it.path)!!.stages.single().condition == "never" })
            TeamEditCapability.revoke()
            assertTrue(request("list") is com.aharou.feature.agent.domain.tool.ToolResult.Error)
        } finally { directory.deleteRecursively() }
    }

}
