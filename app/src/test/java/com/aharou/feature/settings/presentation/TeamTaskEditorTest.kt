package com.aharou.feature.settings.presentation

import com.aharou.feature.agent.domain.subagent.*
import com.aharou.feature.settings.presentation.component.moveTeamTask
import com.aharou.feature.settings.presentation.component.taskEditorStageForSave
import com.aharou.testutil.TestFileAccessProvider
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class TeamTaskEditorTest {
    @Test fun sharedPromptCreatesMissingSelectedTasksButPreservesCustomDuplicatesAndStableIds() {
        val stage = AgentTeamStageForm(members = listOf("worker"))
        assertEquals(TeamCommunication.RUN, stage.communication)
        assertEquals(8, stage.messageLimit)
        assertEquals("always", stage.condition)
        assertTrue(AgentTeamForm().routes.isEmpty())
        val created = com.aharou.feature.settings.presentation.component.editSharedTeamPrompt(stage, "shared")
        assertEquals("worker", created.tasks.single().agent)
        assertEquals("shared", created.tasks.single().prompt)
        val updated = com.aharou.feature.settings.presentation.component.editSharedTeamPrompt(created, "shared next")
        assertEquals(created.tasks.single().id, updated.tasks.single().id)
        val custom = updated.tasks.single().copy(id = "custom", prompt = "independent")
        val duplicated = updated.copy(tasks = updated.tasks + custom)
        val result = com.aharou.feature.settings.presentation.component.editSharedTeamPrompt(duplicated, "shared final")
        assertEquals(2, result.tasks.size)
        assertEquals(listOf("shared final", "independent"), result.tasks.map { it.prompt })
        assertEquals(duplicated.tasks.map { it.id }, result.tasks.map { it.id })
    }
    @Test fun historicalRoleAndDepartmentSelectorRoundTripWithoutReplacement() {
        val missing = AgentTeamTaskForm(agent = "retired", prompt = "legacy", id = "missing", writePaths = "src/old.kt")
        val selector = AgentTeamTaskForm(department = "engineering", agentQuery = "review", prompt = "review",
            id = "selector", writePaths = "src/review.kt", interfaces = listOf(TeamInterface("retired", "check")))
        val form = AgentTeamForm(name = "Legacy", scope = AgentDefinitionScope.PROJECT,
            admins = listOf(MAIN_AGENT_ADMIN), members = listOf(TeamMemberRef("retired")),
            stages = listOf(taskEditorStageForSave(AgentTeamStageForm(title = "Legacy", id = "stage",
                members = listOf("retired"), tasks = listOf(missing, selector)))))
        val access = io.mockk.mockk<com.aharou.feature.workspace.domain.FileAccessProvider>(relaxed = true)
        val serialized = mutableMapOf<String, String>()
        io.mockk.every { access.isDirectory(any()) } returns true
        io.mockk.every { access.listFiles(any()) } returns emptyList()
        io.mockk.every { access.isFile(any()) } answers { serialized.containsKey(firstArg()) }
        io.mockk.every { access.writeFile(any(), any(), any(), any()) } answers { serialized[firstArg()] = arg(1) }
        assertNull(AgentTeamRepository(access).save(form))
        val directory = Files.createTempDirectory("legacy-task-editor").toFile()
        try {
            val file = java.io.File(directory, "legacy.md")
            file.writeText(serialized.values.single())
            val loaded = AgentTeamParser.parse(TestFileAccessProvider(), file.path)!!.toForm(AgentDefinitionScope.PROJECT)
            assertEquals(form.stages.single().tasks, loaded.stages.single().tasks)
            assertEquals(form.admins, loaded.admins)
            assertEquals(form.scope, loaded.scope)
        } finally { directory.deleteRecursively() }
    }

    @Test fun editedAndReorderedTasksRoundTripWithoutLosingIdsInterfacesOrRoutes() {
        val first = AgentTeamTaskForm(id = "first", agent = "worker", prompt = "first prompt", writePaths = "src/a.kt",
            interfaces = listOf(TeamInterface("reviewer", "contract")))
        val second = AgentTeamTaskForm(id = "second", agent = "reviewer", prompt = "review", writePaths = "src/b.kt")
        val edited = first.copy(agent = "reviewer", prompt = "changed prompt", writePaths = "src/c.kt, src/d.kt")
        val tasks = moveTeamTask(listOf(edited, second), 0, 1)
        assertEquals(listOf("second", "first"), tasks.map { it.id })
        assertEquals(tasks, moveTeamTask(tasks, 0, -1))
        val directory = Files.createTempDirectory("task-editor").toFile()
        try {
            val provider = TestFileAccessProvider()
            val form = AgentTeamForm(name = "Fixture", admins = listOf("reviewer"),
                members = listOf(TeamMemberRef("worker"), TeamMemberRef("reviewer")),
                stages = listOf(AgentTeamStageForm(id = "stage", title = "Work", members = listOf("reviewer"),
                    prompt = "bulk remains unchanged", tasks = tasks).let(::taskEditorStageForSave)))
            val file = java.io.File(directory, "fixture.md")
            assertEquals("", form.stages.single().prompt)
            val access = io.mockk.mockk<com.aharou.feature.workspace.domain.FileAccessProvider>(relaxed = true)
            val serialized = mutableMapOf<String, String>()
            io.mockk.every { access.isDirectory(any()) } returns true
            io.mockk.every { access.listFiles(any()) } returns emptyList()
            io.mockk.every { access.isFile(any()) } answers { serialized.containsKey(firstArg()) }
            io.mockk.every { access.writeFile(any(), any(), any(), any()) } answers { serialized[firstArg()] = arg(1) }
            val repository = AgentTeamRepository(access)
            val aggregated = form.copy(stages = form.stages.map { stage -> stage.copy(
                interfaces = listOf(TeamInterfaceEdgeForm("reviewer", "reviewer", "contract"), TeamInterfaceEdgeForm("reviewer", "worker", "separate"))
            ) })
            assertNull(repository.save(aggregated))
            file.writeText(serialized.values.single())
            val loaded = AgentTeamParser.parse(provider, file.path)!!.toForm(AgentDefinitionScope.PROJECT)
            assertEquals(listOf("second", "first"), loaded.stages.single().tasks.map { it.id })
            val reloaded = loaded.stages.single().tasks.last()
            assertEquals("reviewer", reloaded.agent)
            assertEquals("changed prompt", reloaded.prompt)
            assertEquals(listOf("src/c.kt", "src/d.kt"), reloaded.writePaths.split(',').map { it.trim() })
            assertEquals(first.interfaces, reloaded.interfaces)
            assertTrue(loaded.stages.single().tasks.first().interfaces.isEmpty())
            serialized.clear()
            assertNull(repository.save(loaded))
            file.writeText(serialized.values.single())
            val secondLoad = AgentTeamParser.parse(provider, file.path)!!.toForm(AgentDefinitionScope.PROJECT)
            assertEquals(loaded.stages.single().tasks.map { it.interfaces }, secondLoad.stages.single().tasks.map { it.interfaces })
            serialized.clear()
            assertNull(repository.save(loaded.copy(stages = loaded.stages.map { stage -> stage.copy(
                tasks = stage.tasks.map { it.copy(interfaces = emptyList()) }, interfaces = emptyList()
            ) })))
            file.writeText(serialized.values.single())
            assertTrue(AgentTeamParser.parse(provider, file.path)!!.stages.single().tasks.all { it.interfaces.isEmpty() })
            assertEquals("always", loaded.stages.single().condition)
            assertTrue(loaded.routes.isEmpty())
            assertEquals("stage", loaded.stages.single().id)
        } finally { directory.deleteRecursively() }
    }
}
