package com.aharou.feature.agent.domain.context

import com.aharou.core.memory.AharouMemoryStore
import com.aharou.core.memory.MemoryUsageStore
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.agent.domain.memory.GlobalMemorySource
import com.aharou.feature.agent.domain.memory.MemoryParser
import com.aharou.feature.agent.domain.memory.MemoryRepository
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.agent.domain.memory.MemorySource
import com.aharou.feature.settings.data.repository.ExecutionModeHolder
import com.aharou.feature.workspace.domain.ProjectAharouRoot
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContextNativeDataPathTest {
    @get:Rule val temp = TemporaryFolder()

    private fun library(): ContextLibraryRepository {
        val store = mockk<AharouMemoryStore>()
        every { store.dir } returns temp.root
        return ContextLibraryRepository(MemoryRepository(GlobalMemorySource(store), mockk<ExecutionModeHolder>(),
            mockk<ContainerInstaller>(), mockk<ProjectAharouRoot>(), mockk<MemoryUsageStore>(relaxed = true)))
    }

    private fun create(library: ContextLibraryRepository, name: String, folder: String = "") = library.create(
        name, "中文说明🌱", "原正文", MemoryScope.GLOBAL, ContextKind.REFERENCE, ContextLoad.INDEX, null,
        folder = folder)

    @Test fun nativeLogNamesRemainVisibleThroughRealRepositoryAndRenameEditRoundTrip() {
        File(temp.root, "LOG-20250101.md").writeText("automatic journal")
        val library = library()
        assertTrue(create(library, "LOG-用户说明"))
        val before = library.list(null).single()
        assertEquals("LOG-用户说明", before.title)
        val title = "LOG-新 名称🌱 café"
        assertTrue(library.save(before.copy(title = title, content = "改名正文"), null))
        val renamed = library.list(null).single()
        assertEquals(title, renamed.title)
        assertTrue(library.save(renamed.copy(content = "再次编辑正文"), null))
        val after = library.list(null).single()
        assertEquals(title, after.title)
        assertEquals("中文说明🌱", after.description)
        assertEquals("再次编辑正文", after.content)
        assertEquals(title, MemoryParser.parse(MemorySource.resolveMemoryFile(temp.root, title), MemoryScope.GLOBAL)!!.name)
    }

    @Test fun invalidFolderCreateAndSaveNeverWriteOrHideTheOriginal() {
        val library = library()
        assertTrue(create(library, "entry", "资料/接口"))
        val entry = library.list(null).single()
        val file = MemorySource.resolveMemoryFile(temp.root, entry.title)
        val before = file.readText()
        listOf("/资料", "资料/", "资料//接口", "../资料", "资料/..", "资料/./接口", "资料\\接口", "资料/ 损坏", "资料/\n").forEach { path ->
            assertFalse(path, create(library, "invalid", path))
            assertFalse(path, library.save(entry.copy(folder = path, content = "must not write"), null))
            assertEquals(before, file.readText())
            assertEquals("资料/接口", library.list(null).single().folder)
        }
        assertTrue(library.save(entry.copy(folder = "新资料/接口/认证"), null))
        assertEquals("新资料/接口/认证", library.list(null).single().folder)
    }
}
