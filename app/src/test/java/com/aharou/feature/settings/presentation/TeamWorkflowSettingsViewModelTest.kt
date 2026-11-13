package com.aharou.feature.settings.presentation

import androidx.lifecycle.viewModelScope
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigValue
import com.aharou.feature.agent.data.ConfigTeamControlFields
import com.aharou.feature.agent.domain.subagent.AgentDefinitionScope
import com.aharou.feature.agent.domain.subagent.AgentTeam
import com.aharou.feature.agent.domain.subagent.AgentTeamEntry
import com.aharou.feature.agent.domain.subagent.AgentTeamRepository
import com.aharou.feature.agent.domain.teamworkflow.TeamWorkflowForm
import com.aharou.feature.agent.domain.teamworkflow.TeamWorkflowParser
import com.aharou.feature.agent.domain.teamworkflow.TeamWorkflowRepository
import com.aharou.feature.agent.domain.teamworkflow.TeamWorkflowSaveError
import com.aharou.feature.agent.domain.teamworkflow.WorkflowTeamRefForm
import com.aharou.testutil.TestFileAccessProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class TeamWorkflowSettingsViewModelTest {
    @get:Rule val folder = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private val teams = mockk<AgentTeamRepository>()
    private val provider = TestFileAccessProvider()
    private lateinit var repository: TeamWorkflowRepository
    private val viewModels = mutableListOf<TeamWorkflowSettingsViewModel>()

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        every { teams.listAll() } returns listOf("build", "review", "ship").map {
            AgentTeamEntry(AgentTeam(it, "", emptyList(), ""), AgentDefinitionScope.GLOBAL)
        }
        repository = spyk(TeamWorkflowRepository(provider, teams))
        every { repository.workflowsRoot(any()) } answers {
            File(folder.root, firstArg<AgentDefinitionScope>().name).absolutePath
        }
        every { repository.listAll() } answers {
            fun scan(scope: AgentDefinitionScope) = File(repository.workflowsRoot(scope)).listFiles().orEmpty()
                .mapNotNull { TeamWorkflowParser.parse(provider, it.absolutePath) }
            TeamWorkflowRepository.mergeAll(scan(AgentDefinitionScope.GLOBAL), scan(AgentDefinitionScope.PROJECT))
        }
    }

    @After fun cleanup() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private fun vm() = TeamWorkflowSettingsViewModel(repository, teams).also(viewModels::add)
    private fun form(name: String = "release") = TeamWorkflowForm(name = name, description = "description", charter = "charter",
        teams = listOf(WorkflowTeamRefForm("build", lead = "lead"), WorkflowTeamRefForm("review", acceptsReject = false)))

    @Test fun createPersistsExactOrderedFormAndClosesOnlyOnSuccess() = runTest(dispatcher) {
        val vm = vm(); runCurrent(); vm.state.first { !it.loading }
        vm.startCreate(); runCurrent(); vm.state.first { !it.loading }
        vm.updateForm(form()); vm.save()
        assertTrue(vm.state.value.saving)
        vm.save() // A double tap cannot create another write while saving.
        vm.state.first { !it.saving }
        assertNull(vm.state.value.editor)
        val saved = vm.state.value.workflows.single().workflow
        assertEquals(listOf("build", "review"), saved.teams.map { it.teamName })
        assertEquals("lead", saved.teams.first().lead)
        assertFalse(saved.teams.last().acceptsReject)
        assertEquals("charter", saved.charter)
        assertTrue(File(repository.workflowsRoot(AgentDefinitionScope.GLOBAL), "release.md").isFile)
    }

    @Test fun editRenamesWithinOriginalScopeAndKeepsLayerOptions() = runTest(dispatcher) {
        assertNull(repository.save(form().copy(scope = AgentDefinitionScope.PROJECT)))
        val vm = vm(); runCurrent(); vm.state.first { !it.loading }
        vm.startEdit(vm.state.value.workflows.single()); runCurrent(); vm.state.first { !it.loading }
        vm.updateForm(vm.state.value.editor!!.form.copy(name = "renamed", scope = AgentDefinitionScope.GLOBAL,
            teams = vm.state.value.editor!!.form.teams.reversed()))
        vm.save(); vm.state.first { !it.saving }
        val saved = repository.listAll().single()
        assertEquals(AgentDefinitionScope.PROJECT, saved.scope)
        assertEquals("renamed", saved.workflow.name)
        assertEquals(listOf("review", "build"), saved.workflow.teams.map { it.teamName })
        assertFalse(File(repository.workflowsRoot(AgentDefinitionScope.PROJECT), "release.md").exists())
    }

    @Test fun invalidChainsAndDuplicateNamesStayOpenWithRepositoryErrors() = runTest(dispatcher) {
        assertNull(repository.save(form()))
        val vm = vm(); runCurrent(); vm.state.first { !it.loading }
        vm.startCreate(); runCurrent(); vm.state.first { !it.loading }
        val cases = listOf(
            form() to TeamWorkflowSaveError.NAME_CONFLICT,
            form("few").copy(teams = listOf(WorkflowTeamRefForm("build"))) to TeamWorkflowSaveError.TOO_FEW_TEAMS,
            form("duplicate").copy(teams = listOf(WorkflowTeamRefForm("build"), WorkflowTeamRefForm("BUILD"))) to TeamWorkflowSaveError.DUPLICATE_TEAM,
            form("missing").copy(teams = listOf(WorkflowTeamRefForm("build"), WorkflowTeamRefForm("unknown"))) to TeamWorkflowSaveError.UNKNOWN_TEAM,
            form("empty").copy(teams = form().teams + WorkflowTeamRefForm()) to TeamWorkflowSaveError.EMPTY_TEAM_REF,
            form("../invalid") to TeamWorkflowSaveError.INVALID_NAME
        )
        cases.forEach { (draft, error) ->
            vm.updateForm(draft); vm.save(); vm.state.first { !it.saving }
            assertEquals(error, vm.state.value.saveError)
            assertEquals(draft, vm.state.value.editor!!.form)
        }
        assertEquals(1, repository.listAll().size)
    }

    @Test fun dismissingDraftNeverWritesAndNewEditorHasNoStaleError() = runTest(dispatcher) {
        val vm = vm(); runCurrent(); vm.state.first { !it.loading }
        vm.startCreate(); vm.updateForm(form()); vm.dismissEditor()
        assertTrue(repository.listAll().isEmpty())
        vm.startCreate(); runCurrent(); vm.state.first { !it.loading }
        assertEquals(TeamWorkflowForm(), vm.state.value.editor!!.form)
        assertNull(vm.state.value.saveError)
    }

    @Test fun deleteFailureRetainsConfirmationAndOriginalListThenRetrySucceeds() = runTest(dispatcher) {
        assertNull(repository.save(form()))
        val vm = vm(); runCurrent(); vm.state.first { !it.loading }
        val entry = vm.state.value.workflows.single()
        vm.requestDelete(entry)
        every { repository.delete(any(), any()) } returns false
        vm.confirmDelete(); vm.state.first { !it.deleting }
        assertTrue(vm.state.value.deleteFailed)
        assertEquals(entry, vm.state.value.pendingDelete)
        assertEquals(listOf(entry), vm.state.value.workflows)
        every { repository.delete(any(), any()) } answers { callOriginal() }
        vm.confirmDelete(); vm.state.first { !it.deleting }
        assertNull(vm.state.value.pendingDelete)
        assertTrue(vm.state.value.workflows.isEmpty())
    }

    private fun configRegistry() = ConfigRegistry::class.java.getDeclaredConstructor()
        .apply { isAccessible = true }.newInstance().also { ConfigTeamControlFields(teams, repository).registerInto(it) }

    @Test fun configAddEditAndRemoveUseSameScopeAndPreserveLayerOptions() {
        val registry = configRegistry()
        val collection = registry.collection("workflows")!!
        val id = collection.add(ConfigValue.Obj(mapOf(
            "name" to ConfigValue.Str("release.v2 next"), "scope" to ConfigValue.Str("project"),
            "teams" to ConfigValue.Arr(listOf(
                ConfigValue.Obj(mapOf("team" to ConfigValue.Str("build"), "lead" to ConfigValue.Str("lead"))),
                ConfigValue.Obj(mapOf("team" to ConfigValue.Str("review"), "acceptsReject" to ConfigValue.Bool(false)))
            ))
        )))
        assertTrue(id.startsWith("project:")); assertFalse(id.contains('.'))
        val charter = registry.resolveField("workflows.$id.charter")!!
        charter.write(ConfigValue.Str("updated charter"))
        assertEquals(ConfigValue.Str("updated charter"), charter.read())
        val entry = repository.listAll().single()
        assertEquals(AgentDefinitionScope.PROJECT, entry.scope)
        assertEquals("updated charter", entry.workflow.charter)
        assertEquals("lead", entry.workflow.teams.first().lead)
        assertFalse(entry.workflow.teams.last().acceptsReject)
        assertEquals(ConfigValue.Str("project"), registry.resolveField("workflows.$id.scope")!!.read())
        try {
            registry.resolveField("workflows.$id.scope")!!.write(ConfigValue.Str("global"))
            fail("Scope must remain immutable")
        } catch (_: ConfigError.PermissionDenied) { }
        collection.remove(id)
        assertTrue(repository.listAll().isEmpty())
    }

    @Test fun configFailedSaveAndDeletePropagateWithoutChangingPreset() {
        assertNull(repository.save(form()))
        val registry = configRegistry()
        val collection = registry.collection("workflows")!!
        val id = collection.childIds().single()
        try {
            registry.resolveField("workflows.$id.teams")!!.write(ConfigValue.Arr(listOf(
                ConfigValue.Obj(mapOf("team" to ConfigValue.Str("build")))
            )))
            fail("Invalid chain must fail")
        } catch (_: ConfigError.InvalidValue) { }
        assertEquals(2, repository.listAll().single().workflow.layerCount)
        every { repository.delete(any(), any()) } returns false
        try { collection.remove(id); fail("Deletion must not fake success") } catch (_: ConfigError.InvalidValue) { }
        assertEquals(listOf(id), collection.childIds())
    }

    @Test fun projectOverrideDeletionRevealsExistingGlobalPreset() = runTest(dispatcher) {
        assertNull(repository.save(form()))
        assertNull(repository.save(form().copy(scope = AgentDefinitionScope.PROJECT, charter = "project")))
        val vm = vm(); runCurrent(); vm.state.first { !it.loading }
        assertEquals(AgentDefinitionScope.PROJECT, vm.state.value.workflows.single().scope)
        vm.requestDelete(vm.state.value.workflows.single()); vm.confirmDelete()
        vm.state.first { !it.deleting }
        assertEquals(AgentDefinitionScope.GLOBAL, vm.state.value.workflows.single().scope)
        assertEquals("charter", vm.state.value.workflows.single().workflow.charter)
    }

    @Test fun ioFailureAndCancellationNeverCloseDraftAsSaved() = runTest(dispatcher) {
        val vm = vm(); runCurrent(); vm.state.first { !it.loading }
        vm.startCreate(); runCurrent(); vm.state.first { !it.loading }
        vm.updateForm(form())
        every { repository.save(any(), any()) } throws IllegalStateException("unavailable")
        vm.save(); vm.state.first { !it.saving }
        assertEquals(TeamWorkflowSaveError.IO_FAILED, vm.state.value.saveError)
        assertNotNull(vm.state.value.editor)
        every { repository.save(any(), any()) } throws CancellationException("cancelled")
        vm.updateForm(form("retry")); vm.save(); vm.state.first { !it.saving }
        assertNotNull(vm.state.value.editor)
        assertNull(vm.state.value.saveError)
        assertTrue(repository.listAll().isEmpty())
    }
}
