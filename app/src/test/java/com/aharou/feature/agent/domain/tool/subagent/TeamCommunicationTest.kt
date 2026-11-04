package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.feature.agent.domain.subagent.*
import com.aharou.testutil.TestFileAccessProvider
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TeamCommunicationTest {
    @get:Rule val folder = TemporaryFolder()

    private fun tasks(policy: String = "same_stage", limit: Int = 8) = buildJsonArray {
        listOf("a" to "1", "b" to "1", "c" to "2").forEach { (id, stage) -> addJsonObject {
            put("id", id); put("_teamStage", stage); put("_teamCommunication", policy); put("_teamMessageLimit", limit)
        } }
    }

    @Test fun sameStageAllowsPeersButBlocksOtherStagesByBothIdentities() {
        val registry = TeamRunRegistry()
        val run = registry.begin("parent", tasks())
        registry.bind(run, "a", "sa"); registry.bind(run, "b", "sb"); registry.bind(run, "c", "sc")
        assertEquals("delivered", registry.route("sa", "b", "interface").state)
        assertEquals("TEAM_COMMUNICATION_SCOPE", registry.route("sa", "c", "cross stage").reason)
        assertEquals("TEAM_COMMUNICATION_SCOPE", registry.route("sa", "sc", "cross stage session").reason)
        assertTrue(registry.briefing("sa").contains("same_stage"))
        assertTrue(registry.briefing("sa").contains("剩余消息额度：7"))
    }

    @Test fun parentOnlyBlocksPeerCommunicationWithoutRemovingRoster() {
        val registry = TeamRunRegistry()
        val run = registry.begin("parent", tasks("parent_only"))
        registry.bind(run, "a", "sa"); registry.bind(run, "b", "sb")
        assertEquals("TEAM_COMMUNICATION_SCOPE", registry.route("sa", "b", "question").reason)
        assertEquals(3, registry.roster("sa").size)
    }

    @Test fun duplicatesAndBudgetUseLogicalIdentityAndSurviveRework() {
        val registry = TeamRunRegistry()
        val run = registry.begin("parent", tasks(limit = 2))
        registry.bind(run, "a", "sa"); registry.bind(run, "b", "sb")
        assertEquals("delivered", registry.route("sa", "b", "one").state)
        assertEquals("TEAM_DUPLICATE_MESSAGE", registry.route("sa", "sb", " one ").reason)
        assertEquals("delivered", registry.route("sa", "b", "two").state)
        assertEquals("TEAM_MESSAGE_LIMIT", registry.route("sa", "b", "three").reason)
        registry.reset(run, setOf("a")); registry.bind(run, "a", "new")
        assertEquals("TEAM_MESSAGE_LIMIT", registry.route("new", "b", "four").reason)
    }

    @Test fun queuedPeersConsumeBudgetButFinishedMembersCannotBeReawakened() {
        val registry = TeamRunRegistry()
        val run = registry.begin("parent", tasks())
        registry.bind(run, "a", "sa")
        assertEquals("queued", registry.route("sa", "b", "evidence").state)
        val queued = registry.bind(run, "b", "sb")
        assertTrue(queued.contains("sender=a target=b"))
        assertTrue(queued.endsWith("evidence"))
        registry.terminal("sb", "completed")
        assertEquals("TEAM_MEMBER_FINISHED", registry.route("sa", "b", "restart").reason)
        assertEquals("TEAM_MEMBER_FINISHED", registry.route("sb", "a", "after finish").reason)
    }

    @Test fun runScopeStillCannotOverrideRecipientPolicyOrEscapeRun() {
        val registry = TeamRunRegistry()
        val mixed = tasks("run").toMutableList()
        mixed[2] = JsonObject(mixed[2].jsonObject + ("_teamCommunication" to JsonPrimitive("parent_only")))
        val run = registry.begin("parent", JsonArray(mixed))
        registry.bind(run, "a", "sa"); registry.bind(run, "b", "sb"); registry.bind(run, "c", "sc")
        assertEquals("TEAM_COMMUNICATION_SCOPE", registry.route("sa", "c", "bypass target").reason)
        val other = registry.begin("parent", tasks("run"))
        registry.bind(other, "a", "other")
        assertEquals("NOT_SIBLING", registry.route("sa", "other", "escape").reason)
    }

    @Test fun explicitRunAllowsCrossStageQueueButUnknownPolicyDoesNotAllowPeers() {
        val registry = TeamRunRegistry()
        val run = registry.begin("parent", tasks("run"))
        registry.bind(run, "a", "sa")
        assertEquals("queued", registry.route("sa", "c", "handoff clarification").state)
        val unknown = registry.begin("parent", tasks("typo"))
        registry.bind(unknown, "a", "unknown")
        assertEquals("TEAM_COMMUNICATION_SCOPE", registry.route("unknown", "b", "invalid policy").reason)
    }

    @Test fun formRoundTripKeepsCommunicationAndLegacyFilesKeepRunScope() {
        val form = AgentTeamForm(name = "controls", stages = listOf(AgentTeamStageForm(
            title = "stage", communication = TeamCommunication.PARENT_ONLY, messageLimit = 3,
            communicationGuide = "Only interface blockers", tasks = listOf(AgentTeamTaskForm(agent = "worker", prompt = "deliver")))))
        val file = File(folder.root, "team.md")
        file.writeText(AgentTeamParser.serialize(form))
        val parsed = AgentTeamParser.parse(TestFileAccessProvider(), file.absolutePath)!!
        val stage = parsed.toForm(AgentDefinitionScope.GLOBAL).stages.single()
        assertEquals(TeamCommunication.PARENT_ONLY, stage.communication)
        assertEquals(3, stage.messageLimit)
        assertEquals("Only interface blockers", stage.communicationGuide)
        file.writeText(file.readText().lines().filterNot { it.trimStart().startsWith("communication:") }.joinToString("\n"))
        assertEquals(TeamCommunication.RUN, AgentTeamParser.parse(TestFileAccessProvider(), file.absolutePath)!!.stages.single().communication)
        assertEquals(TeamCommunication.SAME_STAGE, AgentTeamStageForm().communication)
    }
}
