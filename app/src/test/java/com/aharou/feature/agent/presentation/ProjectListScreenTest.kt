package com.aharou.feature.agent.presentation

import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aharou.AppLaunchPolicy
import com.aharou.R
import com.aharou.core.ui.pageEnter
import com.aharou.core.ui.pageExit
import com.aharou.feature.agent.domain.model.ChatSession
import com.aharou.feature.agent.domain.task.ProjectPlan
import com.aharou.feature.agent.presentation.component.ProjectListScreen
import com.aharou.feature.agent.presentation.component.WorkspaceSessionBrowser
import com.aharou.feature.workspace.domain.model.Workspace
import com.aharou.feature.workspace.presentation.WorkspaceViewModel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ProjectListScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val state = MutableStateFlow(ProjectTaskUiState())
    private val viewModel = mockk<ProjectTaskViewModel>()
    private val opened = mutableListOf<String>()
    private lateinit var host: LifecycleRegistry
    private lateinit var nav: NavHostController
    private lateinit var rootState: ProjectTaskUiState
    private lateinit var policy: AppLaunchPolicy
    private var launcherConsumptions = 0
    private val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    private val project = ProjectPlan(id = "created", workspace = "/owned/created", title = "First project",
        purpose = "Keep a useful project", workspaceOwnership = "owned")

    @Before fun setUp() {
        every { viewModel.state } returns state
        every { viewModel.createProject(any(), any()) } answers {
            // The real VM acquires the creating/busy owner synchronously, before launching I/O.
            state.value = state.value.copy(creating = true, busy = true, error = null)
        }
    }

    private fun mount(initialLifecycle: Lifecycle.State = Lifecycle.State.STARTED,
        initializeBeforeLauncher: Boolean = false) {
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this)
        }
        compose.runOnUiThread {
            host = owner.lifecycle
            host.currentState = initialLifecycle
        }
        policy = AppLaunchPolicy(launcher)
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    // Keep both production consumers: Activity scope and the destination scope.
                    val observed = viewModel.state.collectAsStateWithLifecycle().value
                    SideEffect { rootState = observed }
                    nav = rememberNavController()
                    LaunchedEffect(Unit) {
                        if (initializeBeforeLauncher) {
                            state.value = state.value.copy(workspace = "/initial", loading = false)
                        }
                        consumeLauncher()
                    }
                    NavHost(navController = nav, startDestination = "projects",
                        enterTransition = { pageEnter(forward = true) },
                        exitTransition = { pageExit(forward = true) },
                        popEnterTransition = { pageEnter(forward = false) },
                        popExitTransition = { pageExit(forward = false) }) {
                        composable("projects") {
                            ProjectListScreen(viewModel, false, {}, { id ->
                                opened += id
                                nav.navigate("projects/$id")
                            }, {}, {}, {})
                        }
                        composable("projects/{id}") {
                            Text(it.arguments?.getString("id").orEmpty(), Modifier.testTag("opened_project"))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, launcherConsumptions) }
    }

    private fun consumeLauncher() {
        policy.consume(launcher, "projects", {}, {
            launcherConsumptions++
            if (nav.currentDestination?.route != "projects") nav.navigate("projects") {
                launchSingleTop = true
                popUpTo("projects") { inclusive = false }
            }
        })
    }

    private fun publish(next: ProjectTaskUiState) {
        compose.runOnIdle { state.value = next }
        compose.waitForIdle()
    }

    private fun moveHost(next: Lifecycle.State) {
        compose.runOnIdle { host.currentState = next }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(next, nav.currentBackStackEntry!!.lifecycle.currentState) }
    }

    private fun initialize() = publish(state.value.copy(workspace = "/initial", loading = false))

    private fun fillAndSubmit() {
        compose.onNodeWithTag("create_project_or_session").performClick()
        compose.onNodeWithTag("create_project").assertIsEnabled().performClick()
        compose.onAllNodes(hasSetTextAction() and !hasTestTag("project_search")).assertCountEquals(2)
        compose.onAllNodes(hasSetTextAction() and !hasTestTag("project_search"))[0].performTextInput(project.title)
        compose.onAllNodes(hasSetTextAction() and !hasTestTag("project_search"))[1].performTextInput(project.purpose)
        compose.onNodeWithTag("save_project_drafts").assertIsEnabled().performClick()
        compose.onNodeWithTag("save_project_drafts").assertIsNotEnabled()
            .assertTextContains(compose.activity.getString(R.string.project_creating))
        compose.runOnIdle { assertEquals(emptyList<String>(), opened) }
    }

    private fun persistAndSelect() {
        publish(state.value.copy(createdProjectId = project.id, creating = false, projects = listOf(project)))
        publish(state.value.copy(workspace = project.workspace, selectedProjectId = project.id))
    }

    private fun assertOpenedOnce() {
        compose.onNodeWithTag("save_project_drafts").assertDoesNotExist()
        compose.onNodeWithTag("opened_project").assertTextContains(project.id)
        compose.runOnIdle {
            assertEquals(listOf(project.id), opened)
            assertEquals("projects/{id}", nav.currentDestination?.route)
        }
        verify(exactly = 1) { viewModel.createProject(project.title, project.purpose) }
    }

    private fun mountWorkspaceHome(agent: AIAgentViewModel, onOpenChat: () -> Unit = {}) {
        val workspace = Workspace("Home", "/ordinary")
        val workspaces = mockk<WorkspaceViewModel>()
        every { workspaces.current } returns MutableStateFlow<Workspace?>(workspace)
        every { workspaces.workspaces } returns MutableStateFlow(listOf(workspace))
        every { agent.sessionsByWorkspace } returns MutableStateFlow(mapOf(workspace.path to listOf(
            ChatSession("regular", "Ordinary chat", 1, 1, workspacePath = workspace.path),
            ChatSession("project-main", "Project private chat", 1, 1, workspacePath = workspace.path),
        )))
        every { agent.currentSessionId } returns MutableStateFlow<String?>(null)
        every { agent.agentStates } returns MutableStateFlow(emptyMap())
        every { agent.activeAgentSessions } returns MutableStateFlow(emptySet())
        every { agent.awaitingPermissionSessionIds } returns MutableStateFlow(emptySet())
        state.value = state.value.copy(workspace = workspace.path, loading = false, projects = listOf(project),
            indexedSessionWorkspaces = setOf(workspace.path), projectSessionIds = setOf("project-main"))
        compose.setContent {
            MaterialTheme {
                WorkspaceSessionBrowser(agent, viewModel, workspaces, false, {},
                    onOpenProject = { opened += it }, onOpenTask = {}, onOpenChat = onOpenChat)
            }
        }
    }

    @Test fun homeShowsProjectsAndOrdinaryChatsTogetherAndKeepsBothOpenActions() {
        val agent = mockk<AIAgentViewModel>()
        every { agent.selectSession("regular") } returns Unit
        var openedChats = 0
        mountWorkspaceHome(agent) { openedChats++ }

        compose.onNodeWithTag("project_home_pager").assertDoesNotExist()
        compose.onNodeWithTag("project_${project.id}").assertIsDisplayed().performClick()
        compose.onNodeWithText("Ordinary chat").assertIsDisplayed().performClick()
        compose.onNodeWithText("Project private chat").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(project.id), opened)
            assertEquals(1, openedChats)
        }
        verify(exactly = 1) { agent.selectSession("regular") }
    }

    @Test fun projectSearchIsOptionalAndClearingRestoresTheHomeLayout() {
        val agent = mockk<AIAgentViewModel>()
        mountWorkspaceHome(agent)
        val other = project.copy(id = "other", title = "Other project", workspace = "/owned/other", purpose = "Polish UI")
        publish(state.value.copy(projects = listOf(project, other)))

        compose.onNodeWithTag("project_search").assertDoesNotExist()
        compose.onNodeWithTag("project_search_open").assertIsDisplayed().performClick()
        compose.onNodeWithTag("project_search").performTextInput("  USEFUL  ")
        compose.onNodeWithTag("project_${project.id}").assertIsDisplayed()
        compose.onNodeWithTag("project_${other.id}").assertDoesNotExist()
        compose.onNodeWithText("Ordinary chat").assertIsDisplayed()
        compose.onNodeWithTag("project_search").performTextReplacement("absent")
        compose.onNodeWithTag("project_search_empty").assertIsDisplayed()
        compose.onNodeWithTag("project_search_clear").performClick()
        compose.onNodeWithTag("project_search").assertDoesNotExist()
        compose.onNodeWithTag("project_search_empty").assertDoesNotExist()
        compose.onNodeWithTag("project_${project.id}").assertIsDisplayed()
        compose.onNodeWithTag("project_home_list").performScrollToNode(hasTestTag("project_${other.id}"))
        compose.onNodeWithTag("project_${other.id}").assertIsDisplayed()
        compose.onNodeWithText("Ordinary chat").assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<String>(), opened) }
    }

    @Test fun projectAndOrdinaryChatDeleteKeepTheirOwnConfirmationsAndTargets() {
        val agent = mockk<AIAgentViewModel>()
        every { agent.deleteSession("regular") } returns Job().apply { complete() }
        every { viewModel.canDeleteProject(project) } returns true
        every { viewModel.deleteProject(project.id) } returns Unit
        mountWorkspaceHome(agent)

        compose.onNodeWithTag("project_${project.id}").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithTag("delete_project_${project.id}").performClick()
        verify(exactly = 0) { viewModel.deleteProject(any()) }
        compose.onNodeWithTag("confirm_delete_project").performClick()
        verify(exactly = 1) { viewModel.deleteProject(project.id) }
        verify(exactly = 0) { agent.deleteSession(any()) }

        compose.onNodeWithText("Ordinary chat").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithText(compose.activity.getString(R.string.common_delete)).performClick()
        verify(exactly = 1) { agent.deleteSession("regular") }
        verify(exactly = 1) { viewModel.deleteProject(any()) }
    }

    @Test fun emptyHomeProvidesAnActionableCreateWithoutStartingTheModel() {
        mount()
        initialize()
        compose.onNodeWithTag("project_home_heading").assertExists()
        compose.onNodeWithTag("project_empty_state").assertExists()
        compose.onNodeWithTag("project_empty_create").assertIsEnabled().performClick()
        compose.onAllNodes(hasSetTextAction() and !hasTestTag("project_search")).assertCountEquals(2)
        verify(exactly = 0) { viewModel.createProject(any(), any()) }
        compose.runOnIdle { assertEquals(emptyList<String>(), opened) }
    }

    @Test fun emptyHomeSubmitUsesTheExistingCreationAndCompletionFlow() {
        mount()
        initialize()
        compose.onNodeWithTag("project_empty_create").performClick()
        compose.onAllNodes(hasSetTextAction() and !hasTestTag("project_search"))[0].performTextInput(project.title)
        compose.onAllNodes(hasSetTextAction() and !hasTestTag("project_search"))[1].performTextInput(project.purpose)
        compose.onNodeWithTag("save_project_drafts").performClick()
        compose.onNodeWithTag("save_project_drafts").assertIsNotEnabled()
        persistAndSelect()
        publish(state.value.copy(busy = false))
        assertOpenedOnce()
    }

    @Test fun emptyHomeCreateIsUnavailableWhileBusyOrLoading() {
        mount()
        initialize()
        publish(state.value.copy(busy = true))
        compose.onNodeWithTag("project_empty_create").assertIsNotEnabled()
        publish(state.value.copy(busy = false, loading = true))
        compose.onNodeWithTag("project_empty_create").assertDoesNotExist()
        verify(exactly = 0) { viewModel.createProject(any(), any()) }
    }

    @Test fun firstLauncherNavigationInCreatedCollectsInitializationAfterStartedWithoutLeavingPage() {
        mount(Lifecycle.State.CREATED)
        compose.runOnIdle { assertEquals(Lifecycle.State.CREATED, nav.currentBackStackEntry!!.lifecycle.currentState) }
        moveHost(Lifecycle.State.STARTED)
        compose.onNodeWithTag("create_project_or_session").performClick()
        compose.onNodeWithTag("create_project").assertIsNotEnabled()
        initialize()
        compose.onNodeWithTag("create_project").assertIsEnabled().performClick()
        compose.onAllNodes(hasSetTextAction() and !hasTestTag("project_search")).assertCountEquals(2)
        compose.runOnIdle {
            assertEquals("/initial", rootState.workspace)
            assertEquals("projects", nav.currentDestination?.route)
            assertEquals(1, launcherConsumptions)
        }
    }

    @Test fun initializationWrittenWhileFirstDestinationIsCreatedIsReplayedOnStart() {
        mount(Lifecycle.State.CREATED)
        initialize()
        compose.runOnIdle { assertEquals("", rootState.workspace) }
        moveHost(Lifecycle.State.STARTED)
        compose.onNodeWithTag("create_project_or_session").performClick()
        compose.onNodeWithTag("create_project").assertIsEnabled().performClick()
        compose.onAllNodes(hasSetTextAction() and !hasTestTag("project_search")).assertCountEquals(2)
        compose.runOnIdle { assertEquals("/initial", rootState.workspace) }
    }

    @Test fun resumedFirstSingleTopReplaysInitializationWrittenBeforeLauncherConsumption() {
        mount(Lifecycle.State.RESUMED, initializeBeforeLauncher = true)
        compose.runOnIdle {
            assertEquals(Lifecycle.State.RESUMED, nav.currentBackStackEntry!!.lifecycle.currentState)
            assertEquals("/initial", rootState.workspace)
        }
        compose.onNodeWithTag("create_project_or_session").performClick()
        compose.onNodeWithTag("create_project").assertIsEnabled().performClick()
        compose.onAllNodes(hasSetTextAction() and !hasTestTag("project_search")).assertCountEquals(2)
        compose.runOnIdle {
            assertEquals("projects", nav.currentDestination?.route)
            assertEquals(1, launcherConsumptions)
        }
    }

    @Test fun resumedFirstSingleTopCollectsInitializationWrittenAfterLauncherConsumption() {
        mount(Lifecycle.State.RESUMED)
        compose.runOnIdle {
            assertEquals(Lifecycle.State.RESUMED, nav.currentBackStackEntry!!.lifecycle.currentState)
            assertEquals("", rootState.workspace)
        }
        compose.onNodeWithTag("create_project_or_session").performClick()
        compose.onNodeWithTag("create_project").assertIsNotEnabled()
        initialize()
        compose.onNodeWithTag("create_project").assertIsEnabled().performClick()
        compose.onAllNodes(hasSetTextAction() and !hasTestTag("project_search")).assertCountEquals(2)
        compose.runOnIdle {
            assertEquals("/initial", rootState.workspace)
            assertEquals("projects", nav.currentDestination?.route)
            assertEquals(Lifecycle.State.RESUMED, nav.currentBackStackEntry!!.lifecycle.currentState)
            assertEquals(1, launcherConsumptions)
        }
    }

    @Test fun synchronousBusyThenPersistSelectionAndReleaseClosesFormAndOpensExactlyOnce() {
        mount()
        initialize()
        fillAndSubmit()
        persistAndSelect()
        compose.onNodeWithTag("save_project_drafts").assertIsNotEnabled()
        publish(state.value.copy(creating = false, busy = false))
        assertOpenedOnce()
    }

    @Test fun failureKeepsInputsAndAllowsRetryWithoutLeavingProjects() {
        mount()
        initialize()
        fillAndSubmit()
        publish(state.value.copy(creating = false, busy = false, error = "TASK_OPERATION_FAILED"))
        compose.onAllNodes(hasSetTextAction() and !hasTestTag("project_search"))[0].assertTextContains(project.title)
        compose.onAllNodes(hasSetTextAction() and !hasTestTag("project_search"))[1].assertTextContains(project.purpose)
        compose.onNodeWithTag("save_project_drafts").assertIsEnabled()
        compose.runOnIdle {
            assertEquals(emptyList<String>(), opened)
            assertEquals("projects", nav.currentDestination?.route)
        }
        compose.onNodeWithTag("save_project_drafts").performClick()
        compose.onNodeWithTag("save_project_drafts").assertIsNotEnabled()
        persistAndSelect()
        publish(state.value.copy(creating = false, busy = false))
        compose.onNodeWithTag("save_project_drafts").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(project.id), opened) }
        verify(exactly = 2) { viewModel.createProject(project.title, project.purpose) }
    }

    @Test fun completionDuringCreatedIsConsumedOnStartWithoutTabNavigation() {
        mount()
        initialize()
        fillAndSubmit()
        moveHost(Lifecycle.State.CREATED)
        persistAndSelect()
        publish(state.value.copy(creating = false, busy = false))
        compose.runOnIdle {
            assertEquals(emptyList<String>(), opened)
            assertEquals(true, rootState.creating)
        }
        moveHost(Lifecycle.State.STARTED)
        assertOpenedOnce()
    }

    @Test fun launcherSingleTopWhileSubmittingDoesNotLoseCompletionConsumer() {
        mount()
        initialize()
        fillAndSubmit()
        compose.runOnIdle { consumeLauncher() }
        compose.waitForIdle()
        compose.onNodeWithTag("save_project_drafts").assertIsNotEnabled()
        persistAndSelect()
        publish(state.value.copy(creating = false, busy = false))
        assertOpenedOnce()
        compose.runOnIdle { assertEquals(2, launcherConsumptions) }
    }
}
