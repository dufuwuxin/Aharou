package com.aharou.core.config

import com.aharou.feature.agent.data.ConfigTeamControlFields
import com.aharou.feature.agent.domain.subagent.AgentTeamRepository
import com.aharou.feature.agent.domain.teamworkflow.TeamWorkflowRepository
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.agent.domain.tool.config.teamCapabilityRequest
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.FileEntry
import io.mockk.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class TeamCapabilityWorkspaceTest {
    @After fun clear() { TeamEditCapability.revoke() }
    private val source = """---
name: Fixture
admins: [Worker]
members:
  - name: Worker
    level: 1
stages:
  - title: Execute
    agent: Worker
    prompt: Work on the fixture
---
"""
    @Test fun channelWritesOnlyTheGrantedWorkspaceEvenWhenTheGlobalWorkspaceIsDifferent() {
        val files = mutableMapOf("/a" to source, "/b" to source)
        fun backend(root: String): FileAccessProvider {
            val access = mockk<FileAccessProvider>(relaxed = true)
            every { access.checked() } returns access
            every { access.isDirectory(any()) } answers { firstArg<String>() == "~/workspace/.aharou/teams" }
            every { access.isFile(any()) } returns true
            every { access.listFiles(any()) } answers { if (firstArg<String>() == "~/workspace/.aharou/teams")
                listOf(FileEntry("fixture.md", false, 100, 0, null, "rw")) else emptyList() }
            every { access.readFile(any()) } answers { files.getValue(root) }
            every { access.writeFile(any(), any(), any(), any()) } answers { files[root] = arg(1) }
            return access
        }
        val global = backend("/b")
        every { global.forWorkspace("/a") } answers { backend("/a") }
        val teams = AgentTeamRepository(global)
        val fields = ConfigTeamControlFields(teams, TeamWorkflowRepository(global, teams))
        val address = TeamEditCapability.issue("/a")
        val path = "team.stages.project:" + "Fixture".toByteArray().joinToString("") { "%02x".format(it.toInt() and 255) } + ":1.message_limit"
        val scopedAddress = TeamEditCapability.childAddress(address, path)
        val read = teamCapabilityRequest(mockk(), "get", scopedAddress, null, "/a", fields) as ToolResult.Success
        val revision = read.data.jsonObject.getValue("revision").jsonPrimitive.content
        val result = teamCapabilityRequest(mockk(), "set", scopedAddress,
            buildJsonObject { put("expectedRevision", revision); put("value", 7) }.toString(), "/a", fields)
        assertTrue(result is ToolResult.Success)
        assertNotEquals(source, files.getValue("/a"))
        assertEquals(source, files.getValue("/b"))
        assertEquals(ConfigValue.Int(7), ConfigRegistry.isolated().also { fields.forWorkspace("/a").registerInto(it) }.resolveField(path)!!.read())
    }
}
