package com.aharou.feature.settings.data.repository

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.agent.domain.container.ContainerProfileResolver
import com.aharou.feature.settings.domain.model.FallbackTarget
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.runBlocking
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
class WorkspaceModelChainRepositoryTest {
    @Test fun workspaceRenameMovesPersistedChainAndInvalidatesBothPaths() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val chains = WorkspaceModelChainRepository(context)
        val resolver = mockk<ContainerProfileResolver>()
        every { resolver.forWorkspace(any()) } returns ContainerProfile.BUILTIN_UBUNTU
        val workspaces = WorkspaceRepository(context, ExecutionModeHolder(), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), resolver, chains)
        val name = "chain-${UUID.randomUUID()}"
        val old = requireNotNull(workspaces.createWorkspace(name))
        val chain = listOf(FallbackTarget("provider", "model"))
        chains.set(old.path, chain)
        val oldRevision = chains.revision(old.path)
        val renamed = requireNotNull(workspaces.renameWorkspace(name, "$name-renamed"))
        assertEquals(chain, WorkspaceModelChainRepository(context).get(renamed.path))
        assertTrue(chains.get(old.path).isEmpty())
        assertTrue(chains.revision(old.path) > oldRevision)
        assertTrue(chains.revision(renamed.path) > 0)
    }
    @Test fun unchangedExternalPathKeepsItsBinding() = runBlocking {
        val chains = WorkspaceModelChainRepository(ApplicationProvider.getApplicationContext())
        val path = "/external/${UUID.randomUUID()}"
        val chain = listOf(FallbackTarget("provider", "model"))
        chains.set(path, chain)
        val revision = chains.revision(path)
        chains.move(path, path)
        assertEquals(chain, chains.get(path))
        assertEquals(revision, chains.revision(path))
    }
}
