package com.aharou.feature.settings.presentation

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import com.aharou.R
import com.aharou.feature.agent.domain.subagent.AgentDefinitionScope
import com.aharou.feature.agent.domain.subagent.AgentTeam
import com.aharou.feature.agent.domain.subagent.AgentTeamEntry
import com.aharou.feature.agent.domain.subagent.AgentTeamRepository
import com.aharou.feature.agent.domain.teamworkflow.TeamWorkflowParser
import com.aharou.feature.agent.domain.teamworkflow.TeamWorkflowRepository
import com.aharou.feature.agent.domain.teamworkflow.TeamWorkflowSaveError
import com.aharou.feature.agent.presentation.ProjectTaskConversationTestApplication
import com.aharou.feature.settings.presentation.component.TeamWorkflowsSection
import com.aharou.testutil.TestFileAccessProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import java.io.File
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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
class TeamWorkflowSettingsComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val folder = TemporaryFolder()
    private val provider = TestFileAccessProvider()
    private val teams = mockk<AgentTeamRepository>()
    private lateinit var repository: TeamWorkflowRepository
    private lateinit var viewModel: TeamWorkflowSettingsViewModel

    @Before fun setup() {
        every { teams.listAll() } returns listOf("build", "review").map {
            AgentTeamEntry(AgentTeam(it, "", emptyList(), ""), AgentDefinitionScope.GLOBAL)
        }
        // Match the VM fixture: isolate paths and scanning, but retain real validation and writes.
        repository = spyk(TeamWorkflowRepository(provider, teams))
        every { repository.workflowsRoot(any()) } answers {
            File(folder.root, firstArg<AgentDefinitionScope>().name).absolutePath
        }
        every { repository.listAll() } answers {
            fun scan(scope: AgentDefinitionScope) = File(repository.workflowsRoot(scope)).listFiles().orEmpty()
                .mapNotNull { TeamWorkflowParser.parse(provider, it.absolutePath) }
            TeamWorkflowRepository.mergeAll(scan(AgentDefinitionScope.GLOBAL), scan(AgentDefinitionScope.PROJECT))
        }
        compose.runOnUiThread { viewModel = TeamWorkflowSettingsViewModel(repository, teams, kotlinx.coroutines.Dispatchers.Unconfined) }
    }

    @After fun cleanup() {
        if (::viewModel.isInitialized) compose.runOnUiThread { viewModel.viewModelScope.cancel() }
    }

    @Test fun editorClicksKeepAnInvalidChainOpenThenPersistAndDisplayTheCorrectedPreset() {
        val name = "click-release"
        val file = File(repository.workflowsRoot(AgentDefinitionScope.GLOBAL), "$name.md")
        compose.setContent { MaterialTheme { TeamWorkflowsSection(viewModel) } }
        awaitRepositoryIdle()
        assertEquals(listOf("build", "review"), viewModel.state.value.availableTeams)
        assertTrue(repository.listAll().isEmpty())

        compose.onNodeWithText(compose.activity.getString(R.string.team_workflow_new)).performClick()
        awaitRepositoryIdle()
        nameField().performScrollTo().performTextInput(name)
        chooseTeam(1, "build")
        saveThroughEditor()

        assertEquals(TeamWorkflowSaveError.TOO_FEW_TEAMS, viewModel.state.value.saveError)
        assertNotNull(viewModel.state.value.editor)
        assertEquals(name, viewModel.state.value.editor!!.form.name)
        assertEquals(listOf("build", ""), viewModel.state.value.editor!!.form.teams.map { it.team })
        compose.onNodeWithText(compose.activity.getString(R.string.team_workflow_error_few_teams))
            .performScrollTo().assertIsDisplayed()
        nameField().performScrollTo().assertIsDisplayed().assertTextContains(name)
        assertFalse(file.exists())
        assertTrue(repository.listAll().isEmpty())

        chooseTeam(2, "review")
        saveThroughEditor()

        assertNull(viewModel.state.value.saveError)
        assertNull(viewModel.state.value.editor)
        assertTrue(file.isFile)
        val parsed = TeamWorkflowParser.parse(provider, file.absolutePath)
        assertNotNull(parsed)
        assertEquals(name, parsed!!.name)
        assertEquals(listOf("build", "review"), parsed.teams.map { it.teamName })
        assertEquals(file.absolutePath, parsed.filePath)
        val saved = repository.listAll().single()
        assertEquals(AgentDefinitionScope.GLOBAL, saved.scope)
        assertEquals(parsed, saved.workflow)
        assertEquals(listOf(saved), viewModel.state.value.workflows)
        nameField().assertDoesNotExist()
        compose.onNodeWithText(name).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.team_workflow_summary,
            compose.activity.getString(R.string.team_scope_global), "build → review")).assertIsDisplayed()
    }

    private fun nameField() = compose.onNode(
        hasSetTextAction() and hasText(compose.activity.getString(R.string.team_editor_name))
    )

    private fun chooseTeam(layer: Int, team: String) {
        compose.onNodeWithText(compose.activity.getString(R.string.team_workflow_layer, layer))
            .performScrollTo().performClick()
        compose.onNodeWithText(team).performClick()
    }

    private fun saveThroughEditor() {
        compose.onNodeWithText(compose.activity.getString(R.string.common_save))
            .performScrollTo().assertIsEnabled().performClick()
        awaitRepositoryIdle()
    }

    private fun awaitRepositoryIdle() {
        // The controlled file backend runs on the injected test dispatcher, not a real IO pool.
        compose.waitUntil { !viewModel.state.value.loading && !viewModel.state.value.saving }
        assertFalse(viewModel.state.value.loadFailed)
    }
}
