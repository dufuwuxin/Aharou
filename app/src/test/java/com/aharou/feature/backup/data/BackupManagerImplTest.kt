package com.aharou.feature.backup.data

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.AgentMessageDao
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.data.local.dao.TodoItemDao
import com.aharou.feature.settings.data.local.dao.AIProviderDao
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import com.aharou.feature.workspace.domain.model.Workspace
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupManagerImplTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var manager: BackupManagerImpl
    private lateinit var original: File
    private lateinit var cache: File
    private val providers = mockk<AIProviderDao>(relaxed = true)
    private val sessions = mockk<ChatSessionDao>(relaxed = true)
    private val messages = mockk<AgentMessageDao>(relaxed = true)
    private val todos = mockk<TodoItemDao>(relaxed = true)

    @Before fun setUp() {
        mockkObject(FileLogger)
        every { FileLogger.i(any(), any()) } returns Unit
        every { FileLogger.e(any(), any(), any()) } returns Unit
        cache = temporary.newFolder("cache")
        val workspace = temporary.newFolder("demo")
        original = File(workspace, "keep.txt").apply { writeText("original") }
        val context = mockk<Context>(relaxed = true)
        every { context.cacheDir } returns cache
        every { context.filesDir } returns temporary.root
        every { context.getString(any()) } returns "invalid backup"
        val workspaces = mockk<WorkspaceRepository>(relaxed = true)
        every { workspaces.workspaces } returns MutableStateFlow(listOf(Workspace("demo", workspace.path)))
        manager = BackupManagerImpl(
            context = context, aiProviderDao = providers,
            remoteConnectionDao = mockk(relaxed = true), chatSessionDao = sessions,
            agentMessageDao = messages, messageArchiveStore = mockk(relaxed = true), todoItemDao = todos,
            mcpConfigRepository = mockk(relaxed = true), mcpManager = mockk(relaxed = true),
            permissionRulesRepository = mockk(relaxed = true), themeSettingsRepository = mockk(relaxed = true),
            keepaliveSettingsRepository = mockk(relaxed = true), screenOnSettingsRepository = mockk(relaxed = true),
            agentSoundSettingsRepository = mockk(relaxed = true), generalSettingsRepository = mockk(relaxed = true),
            logSettingsRepository = mockk(relaxed = true), visionModelSettingsRepository = mockk(relaxed = true),
            compactionModelSettingsRepository = mockk(relaxed = true), syncSettingsRepository = mockk(relaxed = true),
            workspaceRepository = workspaces
        )
    }

    @After fun tearDown() { unmockkObject(FileLogger) }

    @Test fun invalidArchivesFailPreviewAndImportWithoutChangingExistingFile() = runTest {
        val archives = listOf(
            backupFixture("workspaces/demo/keep.txt" to "replacement"),
            backupFixture("workspaces/demo/keep.txt" to "replacement", "metadata.json" to "{}"),
            backupFixture("workspaces/demo/keep.txt" to "replacement", "metadata.json" to VALID_METADATA,
                "messages.jsonl" to "{}\n")
        )
        for (archive in archives) {
            assertTrue(manager.previewImport(archive.inputStream(), null).isFailure)
            assertTrue(manager.import(archive.inputStream(), null).isFailure)
            assertUnchanged()
        }
    }

    @Test fun cancellationPropagatesFromPreviewAndImportAndCleansStaging() = runTest {
        for (preview in listOf(true, false)) {
            val input = object : InputStream() {
                override fun read(): Int = throw CancellationException("fixture cancellation")
            }
            val error = runCatching {
                if (preview) manager.previewImport(input, null) else manager.import(input, null)
            }.exceptionOrNull()
            assertTrue(error is CancellationException)
            assertUnchanged()
        }
    }

    private fun assertUnchanged() {
        assertArrayEquals("original".toByteArray(), original.readBytes())
        assertEquals(0, cache.listFiles()!!.size)
        coVerify(exactly = 0) { providers.insertAllProviders(any()) }
        coVerify(exactly = 0) { sessions.upsertAll(any()) }
        coVerify(exactly = 0) { messages.insertAll(any()) }
        coVerify(exactly = 0) { todos.upsertAll(any()) }
    }
}
