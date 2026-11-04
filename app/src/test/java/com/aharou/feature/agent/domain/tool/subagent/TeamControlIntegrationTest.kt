package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigValue
import com.aharou.feature.agent.data.ConfigTeamControlFields
import com.aharou.feature.agent.domain.notification.*
import com.aharou.feature.agent.domain.subagent.*
import com.aharou.testutil.TestFileAccessProvider
import io.mockk.every
import io.mockk.spyk
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TeamControlIntegrationTest {
    @get:Rule val folder = TemporaryFolder()

    private fun tasks() = buildJsonArray {
        listOf("a", "b").forEach { id -> addJsonObject {
            put("id", id); put("_teamStage", "1"); put("_teamCommunication", "same_stage")
        } }
    }

    private fun message() = PendingNotification(AgentNotificationKind.AGENT_MESSAGE, "sa", "worker",
        NotificationOutcome.COMPLETED, message = "evidence", fromSibling = true)

    @Test fun completionAfterQueueingCannotTriggerAnotherTeamRound() {
        val registry = TeamRunRegistry()
        val center = AgentNotificationCenter(registry)
        val run = registry.begin("parent", tasks())
        registry.bind(run, "a", "sa"); registry.bind(run, "b", "sb")
        center.enqueue("sb", message())
        assertEquals(1, center.peek("sb").size)
        assertFalse(registry.siblingMayWake("sa", "sb"))
        registry.terminal("sb", "completed")
        assertTrue(center.peek("sb").isEmpty())
        assertTrue(center.drain("sb").isEmpty())
        center.enqueue("sb", message().copy(fromSibling = false, fromParent = true))
        assertEquals(1, center.peek("sb").size)
        assertTrue(registry.siblingMayWake("standalone-a", "standalone-b"))
    }

    @Test fun retiredGenerationCannotDeliverQueuedMessagesToNewTaskSession() {
        val registry = TeamRunRegistry()
        val center = AgentNotificationCenter(registry)
        val run = registry.begin("parent", tasks())
        registry.bind(run, "a", "sa"); registry.bind(run, "b", "sb")
        center.enqueue("sb", message())
        registry.reset(run, setOf("a")); registry.bind(run, "a", "new-a")
        assertTrue(center.peek("sb").isEmpty())
        assertFalse(registry.siblingMayWake("sa", "sb"))
    }

    @Test fun actualRepositoryAndConfigSavePreserveTaskLeasesForDottedNames() {
        val provider = TestFileAccessProvider()
        val file = File(folder.root, "team.md")
        val initial = AgentTeamForm(name = "发布 release.v2 next", members = listOf(TeamMemberRef("worker.a"), TeamMemberRef("worker.b")),
            stages = listOf(AgentTeamStageForm(title = "implement", members = listOf("worker.a", "worker.b"), prompt = "deliver",
                tasks = listOf(AgentTeamTaskForm(agent = "worker.a", prompt = "deliver", writePaths = "src/a.kt"),
                    AgentTeamTaskForm(agent = "worker.b", prompt = "deliver", writePaths = "src/b.kt")))))
        file.writeText(AgentTeamParser.serialize(initial))
        val repository = spyk(AgentTeamRepository(provider))
        every { repository.listAll() } answers {
            listOf(AgentTeamEntry(AgentTeamParser.parse(provider, file.absolutePath)!!, AgentDefinitionScope.GLOBAL))
        }
        var team = repository.listAll().single().team
        val edited = team.toForm(AgentDefinitionScope.GLOBAL)
        assertNull(repository.save(edited.copy(stages = edited.stages.map { it.copy(communication = TeamCommunication.PARENT_ONLY) }), team.name))
        team = repository.listAll().single().team
        assertEquals(listOf(listOf("src/a.kt"), listOf("src/b.kt")), team.stages.single().tasks.map { it.writePaths })
        repeat(2) {
            assertNull(repository.save(team.toForm(AgentDefinitionScope.GLOBAL), team.name))
            team = repository.listAll().single().team
            assertEquals(2, team.stages.single().tasks.size)
            assertEquals(listOf(listOf("src/a.kt"), listOf("src/b.kt")), team.stages.single().tasks.map { it.writePaths })
        }
        val registry = ConfigRegistry::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        ConfigTeamControlFields(repository).registerInto(registry)
        val collection = registry.collection("team.stages")!!
        val id = collection.childIds().single()
        assertFalse(id.contains('.'))
        assertEquals("global:e58f91e5b8832072656c656173652e7632206e657874:1", id)
        val field = registry.resolveField("team.stages.$id.message_limit")!!
        field.write(ConfigValue.Int(5))
        assertEquals(ConfigValue.Int(5), field.read())
        team = repository.listAll().single().team
        assertEquals(listOf(listOf("src/a.kt"), listOf("src/b.kt")), team.stages.single().tasks.map { it.writePaths })
        assertTrue(team.stages.single().tasks.all { it.prompt == "deliver" })
        assertEquals(TeamCommunication.PARENT_ONLY, team.stages.single().communication)
    }

    @Test fun keywordInputKeepsMultiwordPhrasesAcrossEditing() {
        val words = listOf("git status", "单元 测试")
        val parsed = com.aharou.feature.agent.domain.skill.SkillParser.parseKeywordInput(words.joinToString(", "))
        assertEquals(words, parsed)
        assertEquals(words, com.aharou.feature.agent.domain.skill.SkillParser.parseKeywordInput("git status，单元 测试\ngit status"))
    }

    @Test fun runnerCarriesPolicyGuideAndBudgetInBothTeamAndWorkflowPlans() {
        val definition = AgentDefinition("worker", "", prompt = "role")
        val stage = TeamStage("implement", listOf(TeamTask(agent = "worker", prompt = "deliver")),
            communication = TeamCommunication.PARENT_ONLY, messageLimit = 3, communicationGuide = "Only blockers")
        val team = AgentTeam("team", "", listOf(stage), "charter")
        val plan = TeamRunner.build(team, "goal", listOf(definition), 2) as TeamRunner.Plan.Ready
        val task = plan.pipeline.first().jsonObject
        assertEquals("parent_only", task.getValue("_teamCommunication").jsonPrimitive.content)
        assertEquals(3, task.getValue("_teamMessageLimit").jsonPrimitive.int)
        assertTrue(task.getValue("prompt").jsonPrimitive.content.contains("Only blockers"))
        val ref = com.aharou.feature.agent.domain.teamworkflow.WorkflowTeamRef(teamName = team.name)
        val workflow = com.aharou.feature.agent.domain.teamworkflow.TeamWorkflow("workflow", "", listOf(ref), "")
        val workflowPlan = TeamRunner.buildWorkflowLayer(workflow, 1, ref, team, "goal", "", listOf(definition), 2) as TeamRunner.Plan.Ready
        assertEquals("parent_only", workflowPlan.pipeline.first().jsonObject.getValue("_teamCommunication").jsonPrimitive.content)
    }
}
