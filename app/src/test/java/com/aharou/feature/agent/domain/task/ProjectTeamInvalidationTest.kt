package com.aharou.feature.agent.domain.task

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aharou.feature.agent.data.task.ProjectTaskRepository
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Provider

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class ProjectTeamInvalidationTest {
    @Test fun teamConfigurationChangeInvalidatesTheWholeDependencyChainInOneSnapshot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val workspace = "team-invalid-" + UUID.randomUUID()
        val name = MessageDigest.getInstance("SHA-256").digest(workspace.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        val directory = File(context.filesDir, "project-tasks").apply { mkdirs() }
        File(directory, "$name.json").writeText("""{"projects":[{"id":"p","workspace":"$workspace","title":"fixture","requirements":"fixture"}]}""")
        val repo = ProjectTaskRepository(context, Provider { mockk<ProjectPlanner>() }, Provider { mockk<WorkspaceRepository>() })
        val a = repo.save(ProjectTask(id = "a", workspace = workspace, title = "a", projectId = "p", requirement = "a"))
        val b = repo.save(ProjectTask(id = "b", workspace = workspace, title = "b", projectId = "p", requirement = "b", dependencyIds = listOf(a.id)))
        val initial = repo.load(workspace)
        assertTrue(repo.invalidateTeamTasks(workspace, initial.version, setOf(a.id, b.id), false, "TEAM_CONFIG_CHANGED"))
        val invalidated = repo.load(workspace)
        assertTrue(invalidated.tasks.all { it.status == ProjectTaskStatus.READY && it.requirementsVersion == 2L && it.acceptedRequirementsVersion == null })
        assertFalse(repo.invalidateTeamTasks(workspace, initial.version, setOf(a.id, b.id), true, "STALE_TEAM"))
        assertEquals(invalidated, repo.load(workspace))
    }
}
