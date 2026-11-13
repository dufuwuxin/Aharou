package com.aharou.feature.agent.domain.teamworkflow

import com.aharou.feature.agent.domain.subagent.AgentDefinitionScope
import com.aharou.feature.agent.domain.subagent.AgentTeam
import com.aharou.feature.agent.domain.subagent.AgentTeamEntry
import com.aharou.feature.agent.domain.subagent.AgentTeamRepository
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.SafeFileAccessProvider
import com.aharou.testutil.TestFileAccessProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import java.io.File
import java.nio.charset.Charset
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TeamWorkflowPersistenceTest {
    @get:Rule val temp = TemporaryFolder()
    private val files = TestFileAccessProvider()
    private val teams = mockk<AgentTeamRepository> {
        every { listAll() } returns listOf("build", "review").map {
            AgentTeamEntry(AgentTeam(it, "", emptyList(), ""), AgentDefinitionScope.GLOBAL)
        }
    }
    private val form = TeamWorkflowForm(name = "release",
        teams = listOf(WorkflowTeamRefForm("build"), WorkflowTeamRefForm("review")))

    private fun repository(access: FileAccessProvider): TeamWorkflowRepository {
        val repo = spyk(TeamWorkflowRepository(access, teams))
        every { repo.workflowsRoot(any()) } returns temp.root.absolutePath
        every { repo.listAll() } answers {
            temp.root.listFiles().orEmpty().filter { it.extension == "md" }
                .mapNotNull { TeamWorkflowParser.parse(files, it.absolutePath) }
                .map { TeamWorkflowEntry(it, AgentDefinitionScope.GLOBAL) }
        }
        return repo
    }

    @Test fun silentWriteFailureIsNotReportedAsSavedThroughTheSafeWrapper() {
        val denied = object : FileAccessProvider by files {
            override fun checked(): FileAccessProvider = this
            override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) = Unit
        }
        val repo = repository(SafeFileAccessProvider(denied))
        assertEquals(TeamWorkflowSaveError.IO_FAILED, repo.save(form))
        assertFalse(File(temp.root, "release.md").exists())
    }

    @Test fun silentDeleteFailureKeepsPresetAndFailedRenameRemovesOnlyTheNewFile() {
        assertNull(repository(files).save(form))
        val denied = object : FileAccessProvider by files {
            override fun checked(): FileAccessProvider = this
            override fun delete(path: String) {
                if (!path.endsWith("/release.md")) files.delete(path)
            }
        }
        val repo = repository(SafeFileAccessProvider(denied))
        assertFalse(repo.delete("release", AgentDefinitionScope.GLOBAL))
        assertEquals(TeamWorkflowSaveError.IO_FAILED, repo.save(form.copy(name = "renamed"), "release"))
        assertTrue(File(temp.root, "release.md").isFile)
        assertFalse(File(temp.root, "renamed.md").exists())
    }
}
