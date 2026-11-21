package com.aharou.feature.settings.presentation

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import com.aharou.R
import com.aharou.feature.agent.domain.context.ContextCategory
import com.aharou.feature.agent.domain.context.ContextEntry
import com.aharou.feature.agent.domain.context.ContextKind
import com.aharou.feature.agent.domain.context.ContextLoad
import com.aharou.feature.agent.domain.context.ContextOrigin
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.agent.domain.subagent.AgentDefinitionScope
import com.aharou.feature.agent.domain.subagent.AgentTeam
import com.aharou.feature.agent.domain.subagent.AgentTeamEntry
import com.aharou.feature.agent.domain.subagent.AgentTeamRepository
import com.aharou.feature.agent.domain.teamworkflow.TeamWorkflow
import com.aharou.feature.agent.domain.teamworkflow.TeamWorkflowEntry
import com.aharou.feature.agent.domain.teamworkflow.TeamWorkflowRepository
import com.aharou.feature.agent.presentation.ProjectTaskConversationTestApplication
import com.aharou.feature.settings.presentation.component.ContextLibrarySection
import com.aharou.feature.settings.presentation.component.SubAgentTeamsSection
import com.aharou.feature.settings.presentation.component.TeamLibraryBrowser
import com.aharou.feature.settings.presentation.component.TeamWorkflowsSection
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectTaskConversationTestApplication::class, sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class LibraryPagesNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun teamSearchAndScopeSurviveWorkflowNavigationAndKeepActionsReachable() {
        val global = AgentTeamEntry(AgentTeam("Global builder", "API migration", emptyList(), ""), AgentDefinitionScope.GLOBAL)
        val project = AgentTeamEntry(AgentTeam("Project builder", "UI cleanup", emptyList(), ""), AgentDefinitionScope.PROJECT)
        var created = 0
        var roles = 0
        val opened = mutableListOf<AgentTeamEntry>()
        val workflow = TeamWorkflowEntry(TeamWorkflow("Release workflow", "", emptyList(), ""), AgentDefinitionScope.GLOBAL)
        val repository = mockk<TeamWorkflowRepository>()
        every { repository.listAll() } returns listOf(workflow)
        val teams = mockk<AgentTeamRepository>()
        every { teams.listAll() } returns listOf(global, project)
        lateinit var workflows: TeamWorkflowSettingsViewModel
        compose.runOnUiThread {
            workflows = TeamWorkflowSettingsViewModel(repository, teams, kotlinx.coroutines.Dispatchers.Unconfined)
        }
        compose.setContent { MaterialTheme {
            TeamLibraryBrowser(listOf(global, project), 2, { created++ }, { roles++ }, opened::add, {}, {
                TeamWorkflowsSection(workflows)
            })
        } }
        compose.onNodeWithText(compose.activity.getString(R.string.team_new)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.team_create_member)).performClick()
        assertEquals(1, created)
        assertEquals(1, roles)
        compose.onNodeWithTag("team_library_search").performTextInput("migration")
        compose.onNodeWithText("Global builder").assertExists()
        compose.onNodeWithText("Project builder").assertDoesNotExist()
        compose.onNode(hasText(compose.activity.getString(R.string.team_scope_project)) and hasClickAction()).performClick()
        compose.onNodeWithTag("team_library_empty").assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.team_library_workflows)).performClick()
        compose.onNodeWithText("Release workflow").performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.team_workflow_edit)).assertIsDisplayed()
        assertEquals("Release workflow", workflows.state.value.editor?.originalName)
        compose.onNodeWithText(compose.activity.getString(R.string.common_cancel)).performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.team_workflow_edit)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.team_library_teams)).performClick()
        compose.onNodeWithTag("team_library_search").assertTextContains("migration")
        compose.onNodeWithTag("team_library_empty").assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.common_clear)).performClick()
        compose.onNodeWithText("Project builder").performScrollTo().performClick()
        assertEquals(listOf(project), opened)
        compose.runOnUiThread { workflows.viewModelScope.cancel() }
    }

    @Test fun teamSwipeDeleteRequiresConfirmationAndPassesTheSelectedEntry() {
        val entry = AgentTeamEntry(AgentTeam("Delete target", "", emptyList(), ""), AgentDefinitionScope.PROJECT)
        val vm = mockk<TeamSettingsViewModel>(relaxed = true)
        every { vm.teams } returns MutableStateFlow(listOf(entry))
        every { vm.members } returns MutableStateFlow(emptyList())
        every { vm.saveState } returns MutableStateFlow<AgentTeamSaveState>(AgentTeamSaveState.Idle)
        compose.setContent { MaterialTheme { SubAgentTeamsSection({}, viewModel = vm) } }
        compose.onNode(hasClickAction() and hasText("Delete target"))
            .performScrollTo().performTouchInput { swipeLeft() }
        compose.onNodeWithText(compose.activity.getString(R.string.common_delete)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.team_delete_title)).assertIsDisplayed()
        verify(exactly = 0) { vm.delete(any()) }
        compose.onNodeWithText(compose.activity.getString(R.string.common_cancel)).performClick()
        verify(exactly = 0) { vm.delete(any()) }
        compose.onNode(hasClickAction() and hasText("Delete target"))
            .performTouchInput { swipeLeft() }
        compose.onNodeWithText(compose.activity.getString(R.string.common_delete)).performClick()
        compose.onNode(hasText(compose.activity.getString(R.string.common_delete)) and hasAnyAncestor(isDialog())).performClick()
        verify(exactly = 1) { vm.delete(entry) }
    }

    @Test fun memorySearchRemainsAfterOpeningAnEntryAndReturningToTheLibrary() {
        val entry = ContextEntry("memory-id", "Target memory", "Description", "Body",
            MemoryScope.GLOBAL, ContextKind.REFERENCE, ContextLoad.INDEX, ContextOrigin.MEMORY, "memory.md",
            category = ContextCategory.LONG_TERM, folder = "Notes")
        val vm = mockk<ContextLibraryViewModel>(relaxed = true)
        every { vm.state } returns MutableStateFlow(ContextLibraryState(entries = listOf(entry), loading = false))
        compose.setContent { MaterialTheme { ContextLibrarySection(vm) } }
        compose.onNodeWithTag("context_library_search").performTextInput("Target memory")
        val memoryCard = hasText("Target memory") and hasClickAction() and !hasSetTextAction()
        compose.onNodeWithTag("context_library_content").performScrollToNode(memoryCard)
        compose.onNode(memoryCard).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.context_back_library)).performClick()
        compose.onNodeWithTag("context_library_search").assertTextContains("Target memory")
        compose.onNodeWithText(compose.activity.getString(R.string.context_graph_title)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.context_tab_library)).performClick()
        compose.onNodeWithTag("context_library_search").assertTextContains("Target memory")
    }

    @Test fun movingUpAMemoryFolderStillClearsTheSearch() {
        val entry = ContextEntry("folder-memory", "Target memory", "Description", "Body",
            MemoryScope.GLOBAL, ContextKind.REFERENCE, ContextLoad.INDEX, ContextOrigin.MEMORY, "memory.md",
            category = ContextCategory.LONG_TERM, folder = "Notes")
        val vm = mockk<ContextLibraryViewModel>(relaxed = true)
        every { vm.state } returns MutableStateFlow(ContextLibraryState(entries = listOf(entry), loading = false))
        compose.setContent { MaterialTheme { ContextLibrarySection(vm) } }
        val folder = hasText("Notes") and hasClickAction()
        compose.onNodeWithTag("context_library_content").performScrollToNode(folder)
        compose.onNode(folder).performClick()
        compose.onNodeWithTag("context_library_content").performScrollToNode(hasTestTag("context_library_search"))
        compose.onNodeWithTag("context_library_search").performTextInput("Target")
        val up = hasText(compose.activity.getString(R.string.context_folder_up)) and hasClickAction()
        compose.onNode(up).assertDoesNotExist()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("context_library_content").performScrollToNode(hasTestTag("context_library_search"))
        assertEquals("", compose.onNodeWithTag("context_library_search").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("context_library_content").performScrollToNode(up)
        compose.onNode(up).assertIsDisplayed().performClick()
        compose.onNode(up).assertDoesNotExist()
    }

    @Test fun systemBackFromEveryMemoryViewPreservesTheLibrarySearch() {
        val vm = mockk<ContextLibraryViewModel>(relaxed = true)
        every { vm.state } returns MutableStateFlow(ContextLibraryState(loading = false))
        compose.setContent { MaterialTheme { ContextLibrarySection(vm) } }
        compose.onNodeWithTag("context_library_search").performTextInput("Target memory")
        listOf(R.string.context_tab_preview, R.string.context_graph_title, R.string.context_resource_tree).forEach { tab ->
            compose.onNode(hasText(compose.activity.getString(tab)) and hasClickAction()).performClick().assertIsSelected()
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText(compose.activity.getString(R.string.context_tab_library)).assertIsSelected()
            compose.onNodeWithTag("context_library_content").performScrollToNode(hasTestTag("context_library_search"))
            compose.onNodeWithTag("context_library_search").assertTextContains("Target memory")
        }
    }
}
