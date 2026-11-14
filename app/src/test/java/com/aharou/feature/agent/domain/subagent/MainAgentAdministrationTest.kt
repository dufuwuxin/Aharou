package com.aharou.feature.agent.domain.subagent

import com.aharou.feature.agent.domain.tool.subagent.TeamRunner
import com.aharou.testutil.TestFileAccessProvider
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class MainAgentAdministrationTest {
    private fun team(admin: String = MAIN_AGENT_ADMIN) = AgentTeam("team", "description",
        listOf(TeamStage("build", listOf(TeamTask(agent = "builder", prompt = "implement", id = "task")), id = "stage")),
        "charter", admins = listOf(admin), members = listOf(TeamMemberRef("builder")))

    @Test fun mainAdminIsUniqueAndNeverAnExecutableRole() {
        val team = team()
        assertNull(TeamFlowGraph.validate(team))
        assertEquals(listOf("builder"), team.referencedMembers)
        assertEquals("ONE_ADMIN_REQUIRED", TeamFlowGraph.validate(team.copy(admins = listOf(MAIN_AGENT_ADMIN, "builder"))))
        assertEquals("ADMIN_NOT_MEMBER", TeamFlowGraph.validate(team("outsider")))
        assertEquals("RESERVED_MEMBER", TeamFlowGraph.validate(team.copy(members = team.members + TeamMemberRef(MAIN_AGENT_ADMIN))))
        val plan = TeamRunner.build(team, "goal", listOf(AgentDefinition("builder", "builder", prompt = "build", filePath = "builder.md")), 3)
        assertTrue(plan is TeamRunner.Plan.Ready)
        val pipeline = (plan as TeamRunner.Plan.Ready).pipeline.toString()
        assertTrue(pipeline.contains("builder"))
        assertFalse(pipeline.contains(MAIN_AGENT_ADMIN))
        assertEquals(1, plan.taskCount)
    }

    @Test fun bothLegacyAndMainYamlRoundTripWithoutChangingAdminsField() {
        for (admin in listOf("builder", MAIN_AGENT_ADMIN)) {
            val source = team(admin)
            val file = File.createTempFile("team-admin-", ".md")
            try {
                file.writeText(AgentTeamParser.serialize(source.toForm(AgentDefinitionScope.GLOBAL)))
                val parsed = requireNotNull(AgentTeamParser.parse(TestFileAccessProvider(), file.path))
                assertEquals(source.admins, parsed.admins)
                assertEquals(source.members, parsed.members)
                assertEquals(source.charter, parsed.charter)
                assertEquals(source.stages.single().tasks.single().agent, parsed.stages.single().tasks.single().agent)
            } finally { file.delete() }
        }
    }

    @Test fun legacyAdminOutsideRosterStillRejectedOnSave() {
        val repo = AgentTeamRepository(TestFileAccessProvider())
        assertEquals(AgentTeamSaveError.ADMIN_NOT_MEMBER,
            repo.save(team("outsider").toForm(AgentDefinitionScope.GLOBAL)))
    }
}
