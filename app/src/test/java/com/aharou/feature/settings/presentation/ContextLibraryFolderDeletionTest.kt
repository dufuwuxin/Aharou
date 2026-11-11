package com.aharou.feature.settings.presentation

import android.content.ContextWrapper
import androidx.lifecycle.viewModelScope
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.domain.context.ContextCategory
import com.aharou.feature.agent.domain.context.ContextFolderStore
import com.aharou.feature.agent.domain.context.ContextLibraryRepository
import com.aharou.feature.agent.domain.memory.MemoryFileStorage
import com.aharou.feature.agent.domain.memory.MemoryParser
import com.aharou.feature.agent.domain.memory.MemoryRepository
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ContextLibraryFolderDeletionTest {
    @get:Rule val temp = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()
    private lateinit var folders: ContextFolderStore
    private lateinit var vm: ContextLibraryViewModel
    private var workspace = "/workspace/a"

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        folders = ContextFolderStore(object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = temp.root
        })
        val memories = mockk<MemoryRepository>()
        every { memories.listMemoriesInScope(any(), any()) } answers {
            val scope = firstArg<MemoryScope>()
            val root = secondArg<String?>()
            directory(scope, root).listFiles().orEmpty().filter { it.extension == "md" }
                .mapNotNull { MemoryParser.parse(it, scope) }
        }
        val workspaces = mockk<WorkspaceRepository>()
        every { workspaces.currentPath() } answers { workspace }
        val sessions = mockk<ChatSessionDao>()
        coEvery { sessions.getRootSessionsByWorkspaceOnce(any()) } returns emptyList()
        vm = ContextLibraryViewModel(ContextLibraryRepository(memories), workspaces, folders, sessions)
    }

    @After fun cleanup() {
        vm.viewModelScope.cancel()
        MemoryFileStorage.resetFolderJournal()
        Dispatchers.resetMain()
    }

    private fun directory(scope: MemoryScope, root: String?) = File(temp.root,
        if (scope == MemoryScope.GLOBAL) "global" else "projects/${root.orEmpty().substringAfterLast('/')}").also { it.mkdirs() }

    private fun put(name: String, scope: MemoryScope, folder: String) = File(directory(scope, workspace), "$name.md").also {
        it.writeText(MemoryParser.format(name, "说明", "$name-body", contextCategory = ContextCategory.RULE,
            contextFolder = folder, contextRequest = "agent/$name.md"))
    }

    private suspend fun snapshot(): ContextLibraryState {
        vm.refresh()
        return withContext(Dispatchers.IO) { withTimeout(5_000) { vm.state.first { !it.loading } } }
    }

    private suspend fun awaitWrite() = withContext(Dispatchers.IO) {
        withTimeout(5_000) { vm.state.first { !it.writing } }
    }

    @Test fun confirmationCallbackMovesNativeEntriesAndDeletesNestedAndEmptyFolders() = runTest(dispatcher) {
        val file = put("nested", MemoryScope.PROJECT, "资料/子/孙")
        val global = put("global", MemoryScope.GLOBAL, "资料/子")
        assertTrue(folders.create(ContextCategory.RULE, "资料/子/孙", workspace))
        val before = snapshot()
        var callbacks = 0
        vm.deleteFolder(ContextCategory.RULE, "资料/子", MemoryScope.PROJECT, before) { callbacks++ }
        awaitWrite()
        assertEquals(1, callbacks)
        assertEquals("资料", MemoryParser.parse(file, MemoryScope.PROJECT)!!.contextFolder)
        assertEquals("nested-body", MemoryParser.parse(file, MemoryScope.PROJECT)!!.content)
        assertEquals("agent/nested.md", MemoryParser.parse(file, MemoryScope.PROJECT)!!.contextRequest)
        assertEquals("资料/子", MemoryParser.parse(global, MemoryScope.GLOBAL)!!.contextFolder)
        assertFalse(folders.listScope(ContextCategory.RULE, MemoryScope.PROJECT, workspace).contains("资料/子"))
        assertTrue(folders.create(ContextCategory.RULE, "空/子", workspace))
        vm.deleteFolder(ContextCategory.RULE, "空", MemoryScope.PROJECT, snapshot()) { callbacks++ }
        awaitWrite()
        assertEquals(2, callbacks)
        assertFalse(folders.listScope(ContextCategory.RULE, MemoryScope.PROJECT, workspace).any { it == "空" || it.startsWith("空/") })
    }

    @Test fun switchedWorkspaceAndStaleMarkerVersionKeepStateAndSuppressSuccessCallback() = runTest(dispatcher) {
        val file = put("entry", MemoryScope.PROJECT, "资料/子")
        assertTrue(folders.create(ContextCategory.RULE, "资料/子", workspace))
        val before = snapshot()
        val bytes = file.readText()
        var callbacks = 0
        workspace = "/workspace/b"
        vm.deleteFolder(ContextCategory.RULE, "资料", MemoryScope.PROJECT, before) { callbacks++ }
        assertTrue(awaitWrite().error)
        assertEquals(bytes, file.readText())
        workspace = "/workspace/a"
        assertTrue(folders.create(ContextCategory.RULE, "资料/新目录", workspace))
        vm.deleteFolder(ContextCategory.RULE, "资料", MemoryScope.PROJECT, before) { callbacks++ }
        assertTrue(awaitWrite().error)
        assertEquals(0, callbacks)
        assertEquals(bytes, file.readText())
        assertTrue(folders.listScope(ContextCategory.RULE, MemoryScope.PROJECT, workspace).contains("资料/新目录"))
    }

    @Test fun legacyInjectionFoldersMergeInStateEntriesAndMarkersAcrossReads() = runTest(dispatcher) {
        val categories = listOf(ContextCategory.ROLE_INJECTED, ContextCategory.MAIN_INJECTED)
        for (category in categories) {
            File(directory(MemoryScope.GLOBAL, workspace), "${category.name}.md").writeText(MemoryParser.format(
                category.name, "说明", "$category-body", contextCategory = category, contextFolder = "内置默认"))
        }
        val marker = File(temp.root, "aharou-global/memory/.context-folders.json")
        requireNotNull(marker.parentFile).mkdirs()
        marker.writeText("""{"__global__":{"ROLE_INJECTED":["内置默认"],"INJECTED":["内置默认"]}}""")
        for (run in 1..2) {
            val state = snapshot()
            for (category in categories) {
                val expected = if (category == ContextCategory.ROLE_INJECTED) "角色注入" else "主代理注入"
                assertEquals(listOf(expected), state.folders[category])
                assertEquals(listOf(expected), state.entries.filter { it.category == category }.map { it.folder })
            }
            assertFalse(marker.readText().contains("内置默认"))
        }
    }

    @Test fun topLevelEmptyFolderCreatedThroughViewModelCanBeDeleted() = runTest(dispatcher) {
        snapshot()
        var created = 0
        vm.createFolder(ContextCategory.RULE, "文化节") { created++ }
        awaitWrite()
        val before = snapshot()
        assertEquals(1, created)
        assertTrue(before.folders[ContextCategory.RULE].orEmpty().contains("文化节"))
        var deleted = 0
        vm.deleteFolder(ContextCategory.RULE, "文化节", MemoryScope.PROJECT, before) { deleted++ }
        awaitWrite()
        assertEquals(1, deleted)
        assertFalse(folders.listScope(ContextCategory.RULE, MemoryScope.PROJECT, workspace).contains("文化节"))
    }
}
