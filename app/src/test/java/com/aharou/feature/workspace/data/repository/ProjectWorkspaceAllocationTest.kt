package com.aharou.feature.workspace.data.repository

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.agent.domain.container.ContainerProfileResolver
import com.aharou.feature.settings.data.repository.ExecutionMode
import com.aharou.feature.settings.data.repository.ExecutionModeHolder
import com.aharou.feature.workspace.domain.model.Workspace
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
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
class ProjectWorkspaceAllocationTest {
    private lateinit var context: Context
    private lateinit var resolver: ContainerProfileResolver
    private lateinit var repository: WorkspaceRepository
    private lateinit var source: Workspace

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        resolver = mockk()
        every { resolver.forWorkspace(any()) } returns ContainerProfile.BUILTIN_UBUNTU
        repository = WorkspaceRepository(context, ExecutionModeHolder(), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), resolver)
        source = requireNotNull(repository.createWorkspace("allocation-source-${UUID.randomUUID()}"))
        repository.selectWorkspaceByPath(source.path)
    }

    @Test
    fun allocationUsesHostPrivateRootAndDoesNotSwitchTheCurrentWorkspace() = runBlocking {
        val id = UUID.randomUUID().toString()
        val target = repository.allocateProjectWorkspace(id, source.path)
        assertEquals(File(context.filesDir, "projects/aharou-project-$id").absolutePath, target.path)
        assertEquals(id, File(target.path, ".aharou-project-owner").readText())
        assertTrue(repository.workspaces.value.any { it.path == target.path && it.available })
        assertEquals(source.path, repository.currentPath())
    }

    @Test
    fun containerAliasCannotBeUsedInsteadOfTheKnownHostPath() = runBlocking {
        val id = UUID.randomUUID().toString()
        try {
            repository.allocateProjectWorkspace(id, "/root/workspace")
            fail("container alias accepted as a host workspace")
        } catch (_: IllegalStateException) { }
        assertFalse(File(context.filesDir, "projects/aharou-project-$id").exists())
        assertEquals(source.path, repository.currentPath())
    }

    @Test
    fun sshProfileOnTheNewWorkspaceRejectsAllocationBeforeCreatingADirectory() = runBlocking {
        val id = UUID.randomUUID().toString()
        val target = File(context.filesDir, "projects/aharou-project-$id")
        every { resolver.forWorkspace(target.absolutePath) } returns
            ContainerProfile.BUILTIN_UBUNTU.copy(mode = ExecutionMode.REMOTE_SSH)
        try {
            repository.allocateProjectWorkspace(id, source.path)
            fail("SSH destination accepted")
        } catch (_: IllegalStateException) { }
        assertFalse(target.exists())
        assertEquals(source.path, repository.currentPath())
    }

    @Test
    fun existingDirectoryAndItsOwnerAreNotOverwritten() = runBlocking {
        val id = UUID.randomUUID().toString()
        val target = File(context.filesDir, "projects/aharou-project-$id")
        assertTrue(target.mkdir())
        val owner = File(target, ".aharou-project-owner").apply { writeText("existing-owner") }
        val content = File(target, "user.txt").apply { writeText("keep") }
        try {
            repository.allocateProjectWorkspace(id, source.path)
            fail("existing directory claimed")
        } catch (_: IllegalStateException) { }
        assertEquals("existing-owner", owner.readText())
        assertEquals("keep", content.readText())
        assertEquals(source.path, repository.currentPath())
    }

    @Test
    fun nonCanonicalProjectIdsCannotAllocateDirectories() = runBlocking {
        for (id in listOf("../escape", "1-1-1-1-1", "12345678-ABCD-1234-1234-123456789abc")) {
            val before = File(context.filesDir, "projects").list().orEmpty().toSet()
            try {
                repository.allocateProjectWorkspace(id, source.path)
                fail("invalid project id accepted: $id")
            } catch (_: IllegalArgumentException) { }
            assertEquals(before, File(context.filesDir, "projects").list().orEmpty().toSet())
        }
        assertEquals(source.path, repository.currentPath())
    }
}
