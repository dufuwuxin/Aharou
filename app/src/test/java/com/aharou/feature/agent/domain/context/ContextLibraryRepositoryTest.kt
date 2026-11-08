package com.aharou.feature.agent.domain.context

import com.aharou.feature.agent.domain.memory.MemoryFileStorage
import com.aharou.feature.agent.domain.memory.MemoryParser
import com.aharou.feature.agent.domain.memory.MemoryRepository
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.agent.domain.memory.MemoryType
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.subagent.AgentDefinition
import com.aharou.feature.agent.domain.subagent.InjectPart
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContextLibraryRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private val memories = mockk<MemoryRepository>()
    private lateinit var global: File
    private lateinit var library: ContextLibraryRepository
    private lateinit var project: File

    @Before fun setup() {
        global = temp.newFolder("memory")
        project = temp.newFolder("project")
        every { memories.listMemoriesInScope(any(), any()) } answers {
            val scope = firstArg<MemoryScope>()
            directory(scope).listFiles().orEmpty().filter { it.extension == "md" }.mapNotNull { MemoryParser.parse(it, scope) }
        }
        every { memories.writeMemory(any(), any(), any(), any(), any(), expectedVersion = any(),
            contextKind = any(), contextLoad = any(), contextCategory = any(), contextFolder = any(), contextSessionId = any()) } answers {
            MemoryFileStorage.saveChecked(directory(arg(3)), arg(3), arg(0), arg(1), arg(2),
                arg(5), arg(6), arg(7), arg(8), arg(9), arg(10), arg(11), arg(12), arg(13), arg(14))
        }
        library = ContextLibraryRepository(memories)
    }

    private fun directory(scope: MemoryScope) = if (scope == MemoryScope.GLOBAL) global else File(project, ".aharou/memory").also { it.mkdirs() }
    private fun memory(name: String, text: String, mode: ContextLoad, scope: MemoryScope = MemoryScope.GLOBAL,
                       category: ContextCategory = ContextCategory.LONG_TERM, session: String? = null) {
        File(directory(scope), "$name.md").writeText(MemoryParser.format(name, name, text, MemoryType.FACT,
            contextLoad = mode, contextCategory = category, contextSessionId = session))
    }
    private fun ctx(session: String = "s1") = AgentContext(null, null, project.path, null, sessionId = session)
    private fun rendered(session: String = "s1", ghost: Boolean = false): String = library.withPromptSnapshot(ctx(session), ghost) {
        library.render(project.path, session, ContextOrigin.MEMORY)
    }

    @Test fun offNeverOutputsAndPreviewAgrees() {
        memory("context", "hidden-payload", ContextLoad.OFF)
        assertEquals("", rendered())
        val preview = library.preview(project.path, "", "s1")
        assertTrue(preview.full.isEmpty())
        assertTrue(preview.omitted.any { it.first.title == "context" })
    }

    @Test fun nextRequestSeesEditedFile() {
        memory("context", "first-payload", ContextLoad.FULL)
        assertTrue(rendered().contains("first-payload"))
        memory("context", "next-payload", ContextLoad.FULL)
        assertTrue(rendered().contains("next-payload"))
        assertFalse(rendered().contains("first-payload"))
    }

    @Test fun identicalProjectEntryCannotBeSavedIntoAnotherWorkspace() {
        memory("same", "same-body", ContextLoad.SEARCH, scope = MemoryScope.PROJECT)
        val entry = library.list(project.path).first { it.title == "same" }
        val other = temp.newFolder("other-project")
        assertFalse(library.save(entry.copy(content = "wrong-project-change"), other.path))
        assertEquals("same-body", MemoryParser.parse(File(project, ".aharou/memory/same.md"), MemoryScope.PROJECT)!!.content)
        assertEquals(project.path, entry.workspaceRoot)
    }

    @Test fun temporaryTextOnlyAppearsInItsBoundSession() {
        memory("temporary", "private-session-payload", ContextLoad.FULL, category = ContextCategory.TEMPORARY, session = "s1")
        assertTrue(rendered("s1").contains("private-session-payload"))
        assertFalse(rendered("s2").contains("private-session-payload"))
    }

    @Test fun oldCoreAndPersonaAreNeitherListedNorRenderedAndFilesStayUntouched() {
        val files = listOf("CORE", "GLOBAL", "SOUL").map { name ->
            File(global, "$name.md").also { it.writeText("old-$name-private-body") }
        }
        assertTrue(library.list(project.path).isEmpty())
        assertEquals("", rendered())
        files.forEach { assertEquals("old-${it.nameWithoutExtension}-private-body", it.readText()) }
    }

    @Test fun newSameNamedEntriesAreNativeAndFullIsNotDuplicated() {
        listOf("CORE", "SOUL", "GLOBAL").forEach { memory(it, "native-$it-body", ContextLoad.FULL) }
        val text = rendered()
        assertEquals(3, library.list(project.path).size)
        listOf("CORE", "SOUL", "GLOBAL").forEach { assertEquals(1, Regex("native-$it-body").findAll(text).count()) }
        assertFalse(text.contains("context_load"))
    }

    @Test fun ghostAndMemberWithoutMemoryCannotReadNativeEntries() {
        memory("context", "private-native-body", ContextLoad.FULL)
        assertFalse(rendered(ghost = true).contains("private-native-body"))
        val member = mockk<AgentDefinition>()
        every { member.inject } returns emptySet()
        val memberContext = ctx().copy(agentDefinition = member)
        val denied = library.withPromptSnapshot(memberContext, false) { library.render(project.path, "s1", ContextOrigin.MEMORY) }
        assertEquals("", denied)
        every { member.inject } returns setOf(InjectPart.MEMORY)
        val allowed = library.withPromptSnapshot(memberContext, false) { library.render(project.path, "s1", ContextOrigin.MEMORY) }
        assertTrue(allowed.contains("private-native-body"))
        assertTrue(rendered().contains("private-native-body"))
    }

    @Test fun projectAndTemporaryEditsKeepVersionsAndRejectStaleWrites() {
        memory("project-entry", "before", ContextLoad.FULL, MemoryScope.PROJECT, ContextCategory.TEMPORARY, "s1")
        val before = library.list(project.path, "s1").single()
        assertFalse(library.save(before.copy(version = "", content = "bad"), project.path))
        assertTrue(library.save(before.copy(content = "after"), project.path))
        val after = library.list(project.path, "s1").single()
        assertEquals("after", after.content)
        assertEquals("s1", after.sessionId)
        assertEquals(project.path, after.workspaceRoot)
        assertNotEquals(before.version, after.version)
        assertFalse(library.save(before.copy(content = "stale"), project.path))
        assertEquals("after", library.list(project.path, "s1").single().content)
    }
    @Test fun renameAndEditSuccessConflictStaleAndWorkspaceBoundary() {
        memory("原名", "before", ContextLoad.FULL, MemoryScope.PROJECT, ContextCategory.TEMPORARY, "s1")
        val before = library.list(project.path, "s1").single()
        val renamed = before.copy(title = "新中文名称", description = "中文说明🌱", content = "after", folder = "项目/资料")
        val other = temp.newFolder("rename-other-project")
        assertFalse(library.save(renamed, other.path))
        assertTrue(library.save(renamed, project.path))
        val after = library.list(project.path, "s1").single()
        assertEquals("新中文名称", after.title)
        assertEquals("中文说明🌱", after.description)
        assertEquals("after", after.content)
        assertEquals("项目/资料", after.folder)
        assertEquals("s1", after.sessionId)
        assertFalse(File(project, ".aharou/memory/原名.md").exists())
        memory("占用名", "existing", ContextLoad.INDEX, MemoryScope.PROJECT)
        assertFalse(library.save(after.copy(title = "占用名", content = "bad"), project.path))
        assertEquals("after", library.list(project.path, "s1").first { it.title == after.title }.content)
        val file = File(project, ".aharou/memory/新中文名称.md")
        file.writeText(file.readText().replace("after", "external-change"))
        assertFalse(library.save(after.copy(title = "stale-rename", content = "bad"), project.path))
        assertFalse(File(project, ".aharou/memory/stale-rename.md").exists())
        assertFalse(library.save(before.copy(title = "old-id-rename"), project.path))
    }

}
