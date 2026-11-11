package com.aharou.feature.agent.domain.tool.memory

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aharou.core.config.ConfigAccess
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigRisk
import com.aharou.core.config.ConfigValue
import com.aharou.feature.agent.data.ConfigMemorySyncFields
import com.aharou.feature.agent.domain.memory.MemorySyncManager
import com.aharou.feature.agent.domain.memory.MemorySyncScheduler
import com.aharou.feature.agent.domain.memory.MemorySyncSettings
import com.aharou.feature.credentials.data.repository.FileCredentialRepository
import com.aharou.feature.settings.presentation.MemorySyncViewModel
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CloudMemorySearchToolTest {
    private lateinit var context: Context
    private lateinit var settings: MemorySyncSettings

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("aharou_memory_sync", Context.MODE_PRIVATE).edit().clear().commit()
        settings = MemorySyncSettings(context)
    }

    @Test fun defaultOffAndEmptyPersistWithoutChangingGitSyncSettings() {
        assertFalse(settings.cloudSearchEnabled)
        assertEquals("", settings.cloudSearchEndpoint)
        settings.repoUrl = "git-repository"
        settings.autoSync = false
        settings.cloudSearchEndpoint = "  https://memory.example.invalid/search  "
        settings.cloudSearchEnabled = true
        val reloaded = MemorySyncSettings(context)
        assertEquals("https://memory.example.invalid/search", reloaded.cloudSearchEndpoint)
        assertTrue(reloaded.cloudSearchEnabled)
        assertEquals("git-repository", reloaded.repoUrl)
        assertFalse(reloaded.autoSync)
        reloaded.cloudSearchEndpoint = ""
        assertEquals("", settings.cloudSearchEndpoint)
        assertTrue(settings.cloudSearchEnabled)
    }

    @Test fun invalidEndpointDoesNotOverwritePreviouslySavedEndpoint() {
        settings.cloudSearchEndpoint = "https://memory.example.invalid/search"
        listOf("http://memory.example.invalid", "https://u:p@memory.example.invalid", "https://memory.example.invalid/#x",
            "https://memory.example.invalid/?key=x", "invalid").forEach { endpoint ->
            assertThrows(IllegalArgumentException::class.java) { settings.cloudSearchEndpoint = endpoint }
            assertEquals("https://memory.example.invalid/search", settings.cloudSearchEndpoint)
        }
    }

    @Test fun configAndExistingViewModelShareStorageAndNeverStartSyncOrReadCredentials() {
        val scheduler = mockk<MemorySyncScheduler>()
        val credentials = mockk<FileCredentialRepository>()
        val manager = mockk<MemorySyncManager>()
        val viewModel = MemorySyncViewModel(manager, settings, scheduler, credentials)
        val registry = mockk<ConfigRegistry>()
        val fields = mutableListOf<ConfigField>()
        every { registry.register(any<ConfigField>()) } answers { fields += firstArg<ConfigField>(); Unit }
        ConfigMemorySyncFields(context, settings, scheduler, credentials, mockk()).registerInto(registry)
        val enabled = fields.single { it.path == "memory.cloud_search.enabled" }
        val endpoint = fields.single { it.path == "memory.cloud_search.endpoint" }
        assertEquals(ConfigAccess.READWRITE, enabled.access)
        assertEquals(ConfigRisk.NORMAL, enabled.risk)
        assertTrue(enabled.revertable)
        assertEquals(ConfigAccess.READWRITE, endpoint.access)
        assertTrue(endpoint.revertable)
        assertEquals(ConfigValue.Bool(false), enabled.read())
        endpoint.write(ConfigValue.Str("https://memory.example.invalid/query"))
        enabled.write(ConfigValue.Bool(true))
        assertEquals("https://memory.example.invalid/query", viewModel.cloudSearchEndpoint())
        assertTrue(viewModel.cloudSearchEnabled())
        assertTrue(viewModel.cloudSearchConfiguration.value.enabled)
        assertEquals("https://memory.example.invalid/query", viewModel.cloudSearchConfiguration.value.endpoint)
        assertFalse(viewModel.saveCloudSearchEndpoint("https://new.example.invalid/query", "https://stale.example.invalid/query"))
        assertEquals("https://memory.example.invalid/query", settings.cloudSearchEndpoint)
        viewModel.setCloudSearchEnabled(false)
        viewModel.saveCloudSearchEndpoint("")
        assertEquals(ConfigValue.Bool(false), enabled.read())
        assertEquals(ConfigValue.Str(""), endpoint.read())
        assertThrows(ConfigError.InvalidValue::class.java) { endpoint.write(ConfigValue.Str("http://memory.example.invalid")) }
        verify { scheduler wasNot Called }
        verify { credentials wasNot Called }
        verify { manager wasNot Called }
    }
}
