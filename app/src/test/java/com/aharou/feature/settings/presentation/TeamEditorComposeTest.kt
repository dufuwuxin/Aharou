package com.aharou.feature.settings.presentation

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.aharou.R
import com.aharou.feature.agent.domain.subagent.*
import com.aharou.feature.agent.presentation.ProjectTaskConversationTestApplication
import com.aharou.feature.settings.presentation.component.TeamEditorSheet
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectTaskConversationTestApplication::class, sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class TeamEditorComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val saves = mutableListOf<AgentTeamForm>()
    private var dismissed = false

    @Test fun newTeamSelectsCoordinatorOutsideRosterAndSavesClickedRouteWithStableIds() {
        open()
        field(R.string.team_editor_name).performScrollTo().performTextInput("click-team")
        chip("worker").performScrollTo().performClick()
        chip("worker").assertIsSelected()
        configureStage(1, "Build")
        text(R.string.team_editor_add_stage).performScrollTo().performClick()
        configureStage(2, "Review")
        text(R.string.team_flow_from, "").performScrollTo().performClick()
        compose.onNodeWithText("Build · worker").performClick()
        text(R.string.team_flow_to, "").performScrollTo().performClick()
        compose.onNodeWithText("Review · worker").performClick()
        text(R.string.team_flow_add).performScrollTo().assertIsEnabled().performClick()
        save()
        val first = saves.single()
        assertEquals(listOf("worker"), first.admins)
        assertEquals(listOf("worker"), first.members.map { it.name })
        val edge = TeamRoute(node(first.stages[0]), node(first.stages[1]))
        assertEquals(listOf(edge), first.routes)
        assertNull(TeamFlowGraph.validate(asTeam(first)))

        stageCard("Build").performScrollTo().performClick()
        field(R.string.team_editor_stage_title).performScrollTo().performTextReplacement("Build renamed")
        save()
        assertEquals(first.stages.map { it.id }, saves.last().stages.map { it.id })
        assertEquals(first.stages.map { it.tasks.single().id }, saves.last().stages.map { it.tasks.single().id })
        assertEquals(first.routes, saves.last().routes)
    }

    @Test fun deletingStageDropsIncidentRoutesAndLegacyRejectTargetButKeepsOtherEdges() {
        val initial = fixture()
        open(initial)
        compose.onAllNodesWithText(compose.activity.getString(R.string.team_editor_remove_stage))[0]
            .performScrollTo().performClick()
        save()
        val form = saves.single()
        assertEquals(listOf("b", "c"), form.stages.map { it.id })
        assertTrue(form.stages.all { it.rejectTo.isEmpty() })
        assertEquals(initial.routes.filter { it.from.stageId != "a" && it.to.stageId != "a" }, form.routes)
        assertNull(TeamFlowGraph.validate(asTeam(form)))
    }

    @Test fun renamingStageKeepsStableRoutesAndUpdatesLegacyRejectTarget() {
        val initial = fixture()
        open(initial)
        stageCard("Build").performScrollTo().performClick()
        field(R.string.team_editor_stage_title).performScrollTo().performTextReplacement("Build renamed")
        save()
        val form = saves.single()
        assertEquals("Build renamed", form.stages.last().rejectTo)
        assertEquals(initial.routes, form.routes)
        assertEquals(initial.stages.map { it.id }, form.stages.map { it.id })
        assertNull(TeamFlowGraph.validate(asTeam(form)))
    }

    @Test fun removingRosterMemberRemovesTheirTasksAndRoutes() {
        open(fixture())
        compose.onNodeWithTag("team-member-remove-worker").performScrollTo().performClick()
        save()
        val form = saves.single()
        assertEquals(listOf("coordinator"), form.members.map { it.name })
        assertTrue(form.stages.none { "worker" in it.members || it.tasks.any { task -> task.agent == "worker" } })
        assertTrue(form.routes.none { it.from.stageId == "a" || it.to.stageId == "a" })
        assertNull(TeamFlowGraph.validate(asTeam(form)))
    }

    @Test fun deselectingStageMemberDropsTheirTaskAndIncidentRoutes() {
        open(fixture())
        stageCard("Build").performScrollTo().performClick()
        compose.onAllNodes(hasText("worker") and isSelectable())[1]
            .performScrollTo().performClick()
        save()
        val form = saves.single()
        assertTrue(form.stages.first().tasks.isEmpty())
        assertTrue(form.routes.none { it.from.stageId == "a" || it.to.stageId == "a" })
        assertNull(TeamFlowGraph.validate(asTeam(form)))
    }

    @Test fun emptyRoutePickerExplainsHowToCreateSelectableNodes() {
        open()
        text(R.string.team_flow_nodes_required).performScrollTo().assertIsDisplayed()
        text(R.string.team_flow_from, "").performScrollTo().assertIsNotEnabled()
        text(R.string.team_flow_add).performScrollTo().assertIsNotEnabled()
    }

    @Test fun clickedForwardCycleCannotBeSaved() {
        open(fixture())
        text(R.string.team_flow_from, "").performScrollTo().performClick()
        compose.onNodeWithText("Review · coordinator").performClick()
        text(R.string.team_flow_to, "").performScrollTo().performClick()
        compose.onNodeWithText("Build · worker").performClick()
        text(R.string.team_flow_add).performScrollTo().assertIsEnabled().performClick()
        text(R.string.team_flow_invalid).performScrollTo().assertIsDisplayed()
        text(R.string.common_save).performScrollTo().assertIsNotEnabled()
        assertTrue(saves.isEmpty())
    }

    @Test fun dismissingNewTeamAfterCoordinatorSelectionDoesNotSave() {
        open()
        chip("worker").performScrollTo().performClick()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .performSemanticsAction(SemanticsActions.Dismiss) { it() }
        compose.waitUntil { dismissed }
        assertTrue(saves.isEmpty())
    }

    private fun open(initial: AgentTeam? = null) {
        val visible = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible.value) TeamEditorSheet(
                    initial = initial?.let { AgentTeamEntry(it, AgentDefinitionScope.GLOBAL) },
                    availableMembers = listOf("worker", "coordinator"),
                    saveState = AgentTeamSaveState.Idle,
                    onSave = { form, _ -> saves += form },
                    onSaved = {},
                    onDismiss = { dismissed = true; visible.value = false }
                )
            }
        }
        compose.waitForIdle()
    }

    private fun configureStage(index: Int, title: String) {
        stageCard(compose.activity.getString(R.string.team_editor_stage_title_hint, index))
            .performScrollTo().performClick()
        field(R.string.team_editor_stage_title).performScrollTo().performTextInput(title)
        compose.onAllNodes(hasText("worker") and isSelectable())[1]
            .performScrollTo().performClick()
        field(R.string.team_editor_stage_prompt).performScrollTo().performTextInput("Do $title")
        stageCard(title).performScrollTo().performClick()
    }

    private fun stageCard(title: String) = compose.onNode(hasText(title) and hasClickAction() and !hasSetTextAction())
    private fun chip(name: String) = compose.onNode(hasText(name) and isSelectable())
    private fun field(label: Int) = compose.onNode(hasSetTextAction() and hasText(compose.activity.getString(label)))
    private fun text(id: Int, vararg args: Any) = compose.onNodeWithText(compose.activity.getString(id, *args))
    private fun save() {
        text(R.string.common_save).performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
    }

    private fun node(stage: AgentTeamStageForm) = TeamFlowNode(stage.id, stage.tasks.single().id)
    private fun asTeam(form: AgentTeamForm) = AgentTeam(form.name, form.description, form.stages.map { stage ->
        TeamStage(stage.title, stage.tasks.map { TeamTask(agent = it.agent, prompt = it.prompt, id = it.id) },
            id = stage.id, isReview = stage.isReview)
    }, form.charter, admins = form.admins, members = form.members, routes = form.routes)

    private fun fixture(): AgentTeam {
        val stages = listOf("a" to "Build", "b" to "Review", "c" to "Finish").map { (id, title) ->
            TeamStage(title, listOf(TeamTask(agent = if (id == "a") "worker" else "coordinator", prompt = "Do $title", id = "task-$id")),
                id = id, isReview = id == "c", rejectTo = if (id == "c") "Build" else null)
        }
        return AgentTeam("existing", "", stages, "", admins = listOf("coordinator"),
            members = listOf(TeamMemberRef("worker"), TeamMemberRef("coordinator")), routes = listOf(
                TeamRoute(TeamFlowNode("a", "task-a"), TeamFlowNode("b", "task-b")),
                TeamRoute(TeamFlowNode("b", "task-b"), TeamFlowNode("c", "task-c")),
                TeamRoute(TeamFlowNode("c", "task-c"), TeamFlowNode("a", "task-a"), TeamRouteAction.REJECT)
            ))
    }
}
