package com.aharou.feature.agent.domain.task

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aharou.feature.agent.data.task.ProjectTaskRepository
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import io.mockk.coEvery
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class ProjectTaskDeletionTest {
    @Test fun ordinaryTaskDeletionRejectsDependenciesAndLateWritesAcrossReload() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = java.util.UUID.randomUUID().toString()
        val path = "/owned/$id"
        val workspaces = mockk<WorkspaceRepository>()
        coEvery { workspaces.allocateProjectWorkspace(id, "/source") } returns
            com.aharou.feature.workspace.domain.model.Workspace("owned", path)
        fun store() = ProjectTaskRepository(context, Provider { error("no planner needed") }, Provider { workspaces })
        val repo = store()
        repo.createOwnedProject("project", "purpose", "/source", id)
        val root = repo.save(ProjectTask(id = "root", projectId = id, workspace = path, title = "root", requirement = "root"))
        val child = repo.save(ProjectTask(id = "child", projectId = id, workspace = path, parentId = root.id,
            title = "child", requirement = "child"))
        val outside = repo.save(ProjectTask(id = "outside", projectId = id, workspace = path,
            title = "outside", requirement = "outside", dependencyIds = listOf(child.id)))
        val before = repo.load(path)
        try { repo.deleteTask(id, root.id, path); fail("dependency must reject") }
        catch (expected: IllegalStateException) { assertEquals("BRANCH_EXTERNAL_DEPENDENCY", expected.message) }
        assertEquals(before, store().load(path))
        repo.save(outside.copy(dependencyIds = emptyList()))
        var lateFailure: String? = null
        val lateExecution = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try { kotlinx.coroutines.awaitCancellation() }
            finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    try { repo.save(child.copy(status = ProjectTaskStatus.REVIEW)) }
                    catch (expected: IllegalStateException) { lateFailure = expected.message }
                }
            }
        }
        assertTrue(repo.deleteTask(id, root.id, path))
        lateExecution.cancelAndJoin()
        assertEquals("TASK_DELETED", lateFailure)
        assertFalse(repo.deleteTask(id, root.id, path))
        val reopened = store()
        assertEquals(listOf(outside.id), reopened.load(path).tasks.map { it.id })
        for (late in listOf(root, child)) {
            try { reopened.save(late.copy(status = ProjectTaskStatus.REVIEW)); fail("late write resurrected deleted task") }
            catch (expected: IllegalStateException) { assertEquals("TASK_DELETED", expected.message) }
        }
        assertEquals(listOf(outside.id), store().load(path).tasks.map { it.id })
        assertEquals(id, store().load(path).projects.single().id)
    }
}
