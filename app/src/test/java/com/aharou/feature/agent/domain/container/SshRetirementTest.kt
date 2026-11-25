package com.aharou.feature.agent.domain.container

import android.app.Application
import com.aharou.feature.settings.data.repository.ContainerSettingsRepository
import com.aharou.feature.settings.data.repository.ExecutionMode
import com.aharou.feature.settings.data.repository.ExecutionModeHolder
import com.aharou.feature.settings.data.repository.ExecutionModeRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class SshRetirementTest {
    @Test fun legacyRemoteModeWritesAndReadsAsLocal() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repository = ExecutionModeRepository(context)
        val holder = ExecutionModeHolder()

        holder.setMode(ExecutionMode.REMOTE_SSH)
        repository.setExecutionMode(ExecutionMode.REMOTE_SSH)

        assertEquals(ExecutionMode.LOCAL_PROOT, holder.currentMode())
        assertEquals(ExecutionMode.LOCAL_PROOT, holder.mode.value)
        assertEquals(ExecutionMode.LOCAL_PROOT, repository.executionModeFlow.first())
    }

    @Test fun remoteProfileAndLegacyWorkspaceOverrideCannotResolve() = runBlocking {
        val localDefault = ContainerProfile(
            id = "local-default",
            name = "Local default",
            rootfsSource = RootfsSource.LocalFile("file:///local-default.tar.gz"),
            shellPath = null,
            isBuiltin = false
        )
        val localOverride = ContainerProfile(
            id = "local-workspace",
            name = "Local workspace",
            rootfsSource = RootfsSource.LocalFile("file:///local-workspace.tar.gz"),
            shellPath = null,
            isBuiltin = false
        )
        val remote = ContainerProfile(
            id = "legacy-ssh-profile",
            name = "Legacy SSH",
            rootfsSource = RootfsSource.RemoteSsh("legacy-connection", "/srv/work"),
            shellPath = null,
            isBuiltin = false,
            mode = ExecutionMode.REMOTE_SSH
        )
        val activeId = MutableStateFlow(localOverride.id)
        val settings = mockk<ContainerSettingsRepository>()
        every { settings.activeProfileIdFlow } returns activeId
        every { settings.customProfilesFlow } returns MutableStateFlow(listOf(localDefault, localOverride, remote))
        every { settings.workspaceProfilesFlow } returns MutableStateFlow(
            mapOf("/workspace/local" to localOverride.id, "/workspace/legacy" to remote.id)
        )

        val resolver = ContainerProfileResolver(settings, mockk(relaxed = true))
        withTimeout(2_000) {
            while (resolver.defaultProfile().id != localOverride.id ||
                resolver.forWorkspace("/workspace/local").id != localOverride.id
            ) delay(10)
        }

        assertEquals(localOverride, resolver.defaultProfile())
        assertEquals(localOverride, resolver.forWorkspace("/workspace/local"))

        activeId.value = remote.id
        withTimeout(2_000) {
            while (resolver.defaultProfile().id != localDefault.id) delay(10)
        }

        assertEquals(localDefault, resolver.defaultProfile())
        assertEquals(localDefault, resolver.forWorkspace("/workspace/legacy"))
        assertEquals(localDefault, resolver.forWorkspace("/workspace/without-override"))
    }
}
