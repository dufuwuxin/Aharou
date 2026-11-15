package com.aharou.core.config

import com.aharou.feature.agent.data.ConfigTeamControlFields
import com.aharou.feature.agent.data.ConfigTeamRoleFields
import com.aharou.feature.agent.domain.subagent.*
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.agent.domain.tool.config.teamCapabilityRequest
import com.aharou.feature.agent.domain.teamworkflow.TeamWorkflowRepository
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.testutil.TestFileAccessProvider
import io.mockk.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TeamRoleCapabilityTest {
    @After fun revoke() { TeamEditCapability.revoke() }

    @Test fun scopedCrudPersistsReparsesRejectsStaleAndReferencedDeletion() {
        val sandbox = Files.createTempDirectory("role-capability").toFile()
        try {
            fun backend(root: File): FileAccessProvider {
                val delegate = TestFileAccessProvider()
                fun path(value: String) = File(root, value.removePrefix("~/")).path
                return mockk<FileAccessProvider>(relaxed = true).also { access ->
                    every { access.checked() } returns access
                    every { access.isDirectory(any()) } answers { delegate.isDirectory(path(firstArg())) }
                    every { access.isFile(any()) } answers { delegate.isFile(path(firstArg())) }
                    every { access.exists(any()) } answers { delegate.exists(path(firstArg())) }
                    every { access.listFiles(any()) } answers { delegate.listFiles(path(firstArg())) }
                    every { access.readFile(any()) } answers { delegate.readFile(path(firstArg())) }
                    every { access.writeFile(any(), any(), any(), any()) } answers {
                        delegate.writeFile(path(firstArg()), arg(1), arg(2), arg(3))
                    }
                    every { access.delete(any()) } answers { delegate.delete(path(firstArg())) }
                }
            }
            val a = backend(File(sandbox, "a"))
            val b = backend(File(sandbox, "b"))
            every { b.forWorkspace("/a") } returns a
            val repo = AgentDefinitionRepository(GlobalDirectoryAgentSource(b), ProjectDirectoryAgentSource(b), mockk(relaxed = true), b)
            val teams = AgentTeamRepository(b)
            val workflows = TeamWorkflowRepository(b, teams)
            val controls = ConfigTeamControlFields(teams, workflows)
            val roles = ConfigTeamRoleFields(repo, b)
            val base = TeamEditCapability.issue("/a")
            fun request(action: String, path: String, value: String? = null) =
                teamCapabilityRequest(mockk(), action, TeamEditCapability.childAddress(base, path), value, "/a", controls, roles)
            fun read(path: String) = (request("get", path) as ToolResult.Success).data.jsonObject
            fun envelope(revision: JsonElement, value: JsonElement) = buildJsonObject {
                put("expectedRevision", revision); put("value", value)
            }.toString()
            val collection = read("roles")
            val form = buildJsonObject {
                put("name", "Fixture"); put("description", "role"); put("prompt", "original prompt")
                put("providerId", "provider"); put("model", "model"); put("reasoningEffort", "high"); put("mode", "AUTO")
                put("allowedTools", buildJsonArray { add("readFile") }); put("disallowedTools", buildJsonArray { add("Bash") })
                put("skills", buildJsonArray {}); put("inject", buildJsonArray { add("memory") })
                put("tags", buildJsonArray { add("fixture") }); put("keywordInjections", buildJsonArray {
                    add(buildJsonObject { put("keywords", buildJsonArray { add("test") }); put("injectContent", "content") })
                })
            }
            val unknown = buildJsonObject { put("scope", "unknown"); put("form", form) }
            assertTrue(request("add", "roles", envelope(collection.getValue("revision"), unknown)) is ToolResult.Error)
            val global = buildJsonObject { put("scope", "global"); put("form", form) }
            assertTrue(request("add", "roles", envelope(collection.getValue("revision"), global)) is ToolResult.Success)
            assertEquals("Fixture", repo.forWorkspace("/a").listInScope(AgentDefinitionScope.GLOBAL).single().name)
            val removeGlobal = buildJsonObject { put("id", "global:46697874757265") }
            assertTrue(request("remove", "roles", envelope(read("roles").getValue("revision"), removeGlobal)) is ToolResult.Success)
            val add = buildJsonObject { put("scope", "project"); put("form", form) }
            assertTrue(request("add", "roles", envelope(read("roles").getValue("revision"), add)) is ToolResult.Success)
            val field = "roles.project:46697874757265.form"
            val before = read(field)
            val changed = JsonObject(form + ("prompt" to JsonPrimitive("updated prompt")))
            val update = envelope(before.getValue("revision"), changed)
            assertTrue(request("set", field, update) is ToolResult.Success)
            assertTrue(request("set", field, update) is ToolResult.Error)
            assertTrue(request("set", field, "{\"value\":$changed}") is ToolResult.Error)
            val parsed = repo.forWorkspace("/a").listInScope(AgentDefinitionScope.PROJECT).single()
            assertEquals("updated prompt", parsed.prompt)
            assertEquals(listOf("readFile"), parsed.allowedTools)
            assertEquals(listOf("fixture"), parsed.tags)
            assertEquals("content", parsed.keywordInjections.single().injectContent)
            assertTrue(repo.listAll().isEmpty())
            assertTrue(teamCapabilityRequest(mockk(), "get", TeamEditCapability.childAddress(base, field), null, "/b", controls, roles) is ToolResult.Error)
            val teamFile = File(sandbox, "a/workspace/.aharou/teams/references.md")
            teamFile.parentFile.mkdirs()
            teamFile.writeText(AgentTeamParser.serialize(AgentTeamForm(name = "Ref", admins = listOf("Fixture"),
                members = listOf(TeamMemberRef("Fixture")), stages = listOf(AgentTeamStageForm(title = "Work",
                    members = listOf("Fixture"), tasks = listOf(AgentTeamTaskForm(agent = "Fixture", prompt = "Run")))))))
            val remove = buildJsonObject { put("id", "project:46697874757265") }
            assertTrue(request("remove", "roles", envelope(read("roles").getValue("revision"), remove)) is ToolResult.Error)
            val renamed = JsonObject(changed + ("name" to JsonPrimitive("Renamed")))
            assertTrue(request("set", field, envelope(read(field).getValue("revision"), renamed)) is ToolResult.Error)
            assertTrue(teamFile.delete())
            assertTrue(request("remove", "roles", envelope(read("roles").getValue("revision"), remove)) is ToolResult.Success)
            assertTrue(repo.forWorkspace("/a").listAll().isEmpty())
            TeamEditCapability.revoke()
            assertTrue(request("get", "roles") is ToolResult.Error)
        } finally { sandbox.deleteRecursively() }
    }
}
