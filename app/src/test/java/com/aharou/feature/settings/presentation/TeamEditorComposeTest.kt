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
        compose.onNodeWithTag("team_routes_advanced").performScrollTo().performClick()
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

    @Test fun deletingStageDropsOnlyItsStableInterfaceEdgesAndKeepsLegacyMemberTargets() {
        val initial = fixture().let { team ->
            team.copy(stages = team.stages.map { stage ->
                stage.copy(tasks = stage.tasks.map { task ->
                    if (task.id == "task-b") task.copy(interfaces = listOf(
                        TeamInterface("a/task-a", "removed node"), TeamInterface("worker", "legacy member")
                    )) else task
                })
            })
        }
        open(initial)
        compose.onAllNodesWithText(compose.activity.getString(R.string.team_editor_remove_stage))[0]
            .performScrollTo().performClick()
        save()
        val surviving = saves.single().stages.first().tasks.single().interfaces
        assertEquals(listOf(TeamInterface("worker", "legacy member")), surviving)
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
        compose.onAllNodes(hasText("worker") and isSelectable()).onLast()
            .performScrollTo().performClick()
        save()
        val form = saves.single()
        assertTrue(form.stages.first().tasks.isEmpty())
        assertTrue(form.routes.none { it.from.stageId == "a" || it.to.stageId == "a" })
        assertNull(TeamFlowGraph.validate(asTeam(form)))
    }

    @Test fun emptyRoutePickerExplainsHowToCreateSelectableNodes() {
        open()
        compose.onNodeWithTag("team_routes_advanced").performScrollTo().performClick()
        text(R.string.team_flow_nodes_required).performScrollTo().assertIsDisplayed()
        text(R.string.team_flow_from, "").performScrollTo().assertIsNotEnabled()
        text(R.string.team_flow_add).performScrollTo().assertIsNotEnabled()
    }

    @Test fun clickedForwardCycleCannotBeSaved() {
        open(fixture())
        compose.onNodeWithTag("team_routes_advanced").performScrollTo().performClick()
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

    @Test fun selectingRealRoleAddsRosterAndPreservesSavedTaskAndRoutes() {
        val initial = fixture().let { team -> team.copy(stages = team.stages.mapIndexed { index, stage ->
            if (index == 0) stage.copy(tasks = stage.tasks.map { it.copy(
                writePaths = listOf("src/a.kt"), interfaces = listOf(TeamInterface("coordinator", "contract"))
            ) }) else stage
        }) }
        open(initial, available = listOf("worker", "coordinator", "designer"), scope = AgentDefinitionScope.PROJECT)
        text(R.string.team_flow_organization_legend).assertDoesNotExist()
        stageCard("Build").performScrollTo().performClick()
        compose.onNodeWithTag("team_task_task-a").performScrollTo().performClick()
        field(R.string.team_task_write_paths).assertDoesNotExist()
        compose.onNodeWithTag("team_task_advanced_task-a").performScrollTo().performClick()
        field(R.string.team_task_write_paths).performScrollTo().assertTextContains("src/a.kt")
        compose.onNodeWithTag("team_task_advanced_task-a").performScrollTo().performClick()
        field(R.string.team_task_write_paths).assertDoesNotExist()
        compose.onNodeWithTag("team_task_role_task-a").performScrollTo().performClick()
        compose.onNodeWithTag("team_role_option_designer").performScrollTo().performClick()
        compose.onNodeWithTag("team_member_picker_confirm").performScrollTo().performClick()
        save()
        val saved = saves.single()
        val task = saved.stages.first().tasks.single()
        assertEquals("designer", task.agent)
        assertEquals("task-a", task.id)
        assertEquals("Do Build", task.prompt)
        assertEquals("src/a.kt", task.writePaths)
        assertEquals(initial.stages.first().tasks.single().interfaces, task.interfaces)
        assertEquals(listOf("designer"), saved.stages.first().members)
        assertEquals(listOf("worker", "coordinator", "designer"), saved.members.map { it.name })
        assertEquals(initial.admins, saved.admins)
        assertEquals(initial.routes, saved.routes)
        assertEquals(AgentDefinitionScope.PROJECT, saved.scope)
        assertNull(TeamFlowGraph.validate(asTeam(saved)))
    }

    @Test fun missingAndSelectorTasksSurviveUnrelatedClicksAndSave() {
        val missing = TeamTask(agent = "retired", prompt = "keep missing", id = "missing",
            writePaths = listOf("src/legacy.kt"), interfaces = listOf(TeamInterface("worker", "legacy")))
        val selector = TeamTask(department = "engineering", agentQuery = "review", prompt = "keep selector",
            id = "selector", writePaths = listOf("src/review.kt"), interfaces = listOf(TeamInterface("worker", "review")))
        open(AgentTeam("legacy", "", listOf(TeamStage("Legacy", listOf(missing, selector), id = "legacy-stage")),
            "", admins = listOf(MAIN_AGENT_ADMIN), members = listOf(TeamMemberRef("retired"), TeamMemberRef("worker"))))
        stageCard("Legacy").performScrollTo().performClick()
        // Adding another stage member must not delete historical tasks.
        compose.onAllNodes(hasText("worker") and isSelectable()).onLast().performScrollTo().performClick()
        compose.onNodeWithTag("team_task_missing").performScrollTo().assertTextContains(
            compose.activity.getString(R.string.team_task_role_unavailable, "retired"))
        compose.onNodeWithTag("team_task_selector").performScrollTo().performClick()
        compose.onNodeWithTag("team_task_role_selector").performScrollTo().performClick()
        compose.onNodeWithTag("team_role_option_retired").assertDoesNotExist()
        compose.onNodeWithTag("team_role_option_$MAIN_AGENT_ADMIN").assertDoesNotExist()
        text(R.string.common_cancel).performScrollTo().performClick()
        save()
        val form = saves.single()
        val tasks = form.stages.single().tasks
        assertEquals(listOf("missing", "selector"), tasks.map { it.id })
        assertEquals("retired", tasks[0].agent)
        assertEquals("engineering", tasks[1].department)
        assertEquals("review", tasks[1].agentQuery)
        assertEquals("", tasks[1].agent)
        assertEquals(listOf("keep missing", "keep selector"), tasks.map { it.prompt })
        assertEquals(listOf("src/legacy.kt", "src/review.kt"), tasks.map { it.writePaths })
        assertEquals(listOf(missing.interfaces, selector.interfaces), tasks.map { it.interfaces })
        assertEquals(listOf(MAIN_AGENT_ADMIN), form.admins)
    }

    @Test fun selectorOnlyTeamCanStillBeSavedWithoutPickingNamedRole() {
        open(AgentTeam("selector-team", "", listOf(TeamStage("Search", listOf(
            TeamTask(department = "engineering", agentQuery = "review", prompt = "Review", id = "search-task")
        ), id = "search-stage")), "", admins = listOf(MAIN_AGENT_ADMIN)))
        save()
        val form = saves.single()
        assertTrue(form.members.isEmpty())
        assertEquals("engineering", form.stages.single().tasks.single().department)
        assertEquals("review", form.stages.single().tasks.single().agentQuery)
        assertEquals(listOf(MAIN_AGENT_ADMIN), form.admins)
    }

    @Test fun foldedTaskButtonsReorderAndDeleteWithoutLosingRemainingRoutes() {
        val initial = fixture().let { team -> team.copy(stages = team.stages.mapIndexed { index, stage ->
            if (index == 0) stage.copy(tasks = stage.tasks + TeamTask(agent = "coordinator", prompt = "Extra", id = "extra")) else stage
        }) }
        open(initial)
        stageCard("Build").performScrollTo().performClick()
        compose.onNodeWithTag("team_task_extra").performScrollTo().performClick()
        compose.onNodeWithTag("team_task_up_extra").performScrollTo().performClick()
        save()
        assertEquals(listOf("extra", "task-a"), saves.last().stages.first().tasks.map { it.id })
        assertEquals(initial.routes, saves.last().routes)
        assertEquals(AgentDefinitionScope.GLOBAL, saves.last().scope)
        compose.onNodeWithTag("team_task_delete_extra").performScrollTo().performClick()
        save()
        assertEquals(listOf("task-a"), saves.last().stages.first().tasks.map { it.id })
        assertEquals(initial.routes, saves.last().routes)
        assertNull(TeamFlowGraph.validate(asTeam(saves.last())))
    }

    @Test fun graphSelectionEditsAndRemovesBidirectionalCollaborationThroughTheDialog() {
        open(fixture())
        compose.onNodeWithTag("team_graph_node_a/task-a").performScrollTo().performClick()
        compose.onNodeWithTag("team_graph_node_b/task-b").performScrollTo().performClick()
        text(R.string.team_relationship_collaboration).performScrollTo().performClick()
        text(R.string.team_relationship_bidirectional).performScrollTo().performClick()
        field(R.string.team_relationship_about).performScrollTo().performTextInput("contract")
        compose.onNodeWithTag("team_relation_apply").performClick()
        save()
        val added = saves.last()
        assertEquals(listOf(TeamInterface("b/task-b", "contract")), added.stages[0].tasks.single().interfaces)
        assertEquals(listOf(TeamInterface("a/task-a", "contract")), added.stages[1].tasks.single().interfaces)
        assertEquals(fixture().routes, added.routes)
        compose.onNodeWithTag("team_graph_node_a/task-a").performScrollTo().performClick()
        compose.onNodeWithTag("team_graph_node_b/task-b").performScrollTo().performClick()
        text(R.string.team_relationship_bidirectional).performScrollTo().performClick()
        compose.onNodeWithTag("team_relation_remove").performScrollTo().performClick()
        save()
        assertTrue(saves.last().stages.take(2).all { stage -> stage.tasks.all { it.interfaces.isEmpty() } })
    }

    @Test fun cancellingGraphRelationshipChangesLeavesSavedTeamUnchanged() {
        val original = fixture()
        open(original)
        compose.onNodeWithTag("team_graph_node_a/task-a").performScrollTo().performClick()
        compose.onNodeWithTag("team_graph_node_b/task-b").performScrollTo().performClick()
        text(R.string.common_cancel).performClick()
        save()
        assertEquals(original.routes, saves.single().routes)
        assertTrue(saves.single().stages.all { stage -> stage.tasks.all { it.interfaces.isEmpty() } })
    }

    private fun open(initial: AgentTeam? = null, available: List<String> = listOf("worker", "coordinator"), scope: AgentDefinitionScope = AgentDefinitionScope.GLOBAL) {
        val visible = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible.value) TeamEditorSheet(
                    initial = initial?.let { AgentTeamEntry(it, scope) },
                    availableMembers = available,
                    saveState = AgentTeamSaveState.Idle,
                    onSave = { form, _ -> saves += form },
                    onSaved = {},
                    onDismiss = { dismissed = true; visible.value = false }
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("team_stage_settings").performScrollTo().performClick()
    }

    private fun configureStage(index: Int, title: String) {
        compose.onNodeWithTag("team_stage_card_$index").performScrollTo().performClick()
        field(R.string.team_editor_stage_title).performScrollTo().performTextInput(title)
        compose.onAllNodes(hasText("worker") and isSelectable()).onLast()
            .performScrollTo().performClick()
        field(R.string.team_editor_stage_prompt).performScrollTo().performTextInput("Do $title")
        stageCard(title).performScrollTo().performClick()
    }

    private fun stageCard(title: String) = compose.onNodeWithTag("team_stage_card_$title")
    private fun chip(name: String) = compose.onNodeWithTag("team_admin_$name")
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
