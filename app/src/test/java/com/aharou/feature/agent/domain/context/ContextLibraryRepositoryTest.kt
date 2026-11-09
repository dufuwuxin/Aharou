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
            contextKind = any(), contextLoad = any(), contextCategory = any(), contextFolder = any(), contextSessionId = any(), contextRequest = any()) } answers {
            MemoryFileStorage.saveChecked(directory(arg(3)), arg(3), arg(0), arg(1), arg(2),
                arg(5), arg(6), arg(7), arg(8), arg(9), arg(10), arg(11), arg(12), arg(13), arg(14), arg(15))
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
    private fun rendered(session: String = "s1"): String = library.withPromptSnapshot(ctx(session)) {
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

    @Test fun memberWithoutMemoryCannotReadOrdinaryEntries() {
        memory("context", "private-native-body", ContextLoad.FULL)
        val member = mockk<AgentDefinition>()
        every { member.inject } returns emptySet()
        val memberContext = ctx().copy(agentDefinition = member)
        val denied = library.withPromptSnapshot(memberContext) { library.render(project.path, "s1", ContextOrigin.MEMORY) }
        assertEquals("", denied)
        every { member.inject } returns setOf(InjectPart.MEMORY)
        val allowed = library.withPromptSnapshot(memberContext) { library.render(project.path, "s1", ContextOrigin.MEMORY) }
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


    @Test fun everyRoleReceivesOnlyRoleInjectionEvenWithoutMemoryAndMainOnlyReceivesMain() {
        memory("role", "role-private-body", ContextLoad.FULL, category = ContextCategory.ROLE_INJECTED)
        memory("main", "main-private-body", ContextLoad.FULL, category = ContextCategory.MAIN_INJECTED)
        memory("normal", "ordinary-private-body", ContextLoad.FULL)
        listOf("builder", "reviewer").forEach { name ->
            val member = mockk<AgentDefinition>()
            every { member.name } returns name
            every { member.inject } returns emptySet()
            val result = library.withPromptSnapshot(ctx().copy(agentDefinition = member)) {
                library.render(project.path, "s1", ContextOrigin.MEMORY, memberName = name)
            }
            assertTrue(result.contains("role-private-body"))
            assertFalse(result.contains("main-private-body"))
            assertFalse(result.contains("ordinary-private-body"))
            every { member.inject } returns setOf(InjectPart.MEMORY)
            val enabled = library.withPromptSnapshot(ctx().copy(agentDefinition = member)) {
                library.render(project.path, "s1", ContextOrigin.MEMORY, memberName = name)
            }
            assertTrue(enabled.contains("ordinary-private-body"))
            assertFalse(enabled.contains("main-private-body"))
        }
        assertTrue(rendered().contains("main-private-body"))
        assertFalse(rendered().contains("role-private-body"))
    }

    @Test fun ordinaryBlockSwitchAndSnapshotPlanCacheDoNotDisableExplicitInjection() {
        memory("role", "role-payload", ContextLoad.FULL, category = ContextCategory.ROLE_INJECTED)
        memory("main", "main-payload", ContextLoad.FULL, category = ContextCategory.MAIN_INJECTED)
        memory("ordinary", "ordinary-payload", ContextLoad.FULL)
        library.withPromptSnapshot(ctx()) {
            val disabled = library.render(project.path, "s1", ContextOrigin.MEMORY, ordinaryMemoryEnabled = false)
            assertTrue(disabled.contains("main-payload"))
            assertFalse(disabled.contains("role-payload"))
            assertFalse(disabled.contains("ordinary-payload"))
            assertTrue(library.render(project.path, "s1", ContextOrigin.MEMORY).contains("ordinary-payload"))
            assertEquals(disabled, library.render(project.path, "s1", ContextOrigin.MEMORY, ordinaryMemoryEnabled = false))
            ""
        }
    }

    @Test fun nestedPreviewRoleAndFailureRestoreMainSnapshotWithoutVersionPollution() {
        memory("main", "first-main-payload", ContextLoad.FULL, category = ContextCategory.MAIN_INJECTED)
        memory("role", "first-role-payload", ContextLoad.FULL, category = ContextCategory.ROLE_INJECTED)
        val member = mockk<AgentDefinition>()
        every { member.inject } returns emptySet()
        library.withPromptSnapshot(ctx()) {
            val initial = library.render(project.path, "s1", ContextOrigin.MEMORY)
            val before = library.list(project.path).first { it.title == "main" }.version
            memory("main", "next-main-payload", ContextLoad.FULL, category = ContextCategory.MAIN_INJECTED)
            try {
                library.withPromptSnapshot(ctx().copy(agentDefinition = member)) {
                    assertTrue(library.render(project.path, "s1", ContextOrigin.MEMORY).contains("first-role-payload"))
                    val preview = library.preview(project.path, "", "s1").rendered
                    assertTrue(preview.contains("next-main-payload"))
                    assertFalse(preview.contains("first-role-payload"))
                    assertTrue(library.render(project.path, "s1", ContextOrigin.MEMORY).contains("first-role-payload"))
                    throw IllegalStateException("test-snapshot-restoration")
                }
            } catch (e: IllegalStateException) { assertEquals("test-snapshot-restoration", e.message) }
            assertEquals(initial, library.render(project.path, "s1", ContextOrigin.MEMORY))
            assertEquals(before, library.list(project.path).first { it.title == "main" }.version)
            ""
        }
        assertTrue(rendered().contains("next-main-payload"))
        assertFalse(rendered().contains("first-role-payload"))
    }

    @Test fun injectionOffExpiryAndProjectScopeRemainEffectiveAndLegacyStaysMainOnly() {
        memory("off-role", "off-private", ContextLoad.OFF, category = ContextCategory.ROLE_INJECTED)
        File(global, "expired.md").writeText(MemoryParser.format("expired", "expired", "expired-private",
            expiresAt = 1L, contextLoad = ContextLoad.FULL, contextCategory = ContextCategory.ROLE_INJECTED))
        memory("project-main", "project-main-private", ContextLoad.FULL, MemoryScope.PROJECT, ContextCategory.MAIN_INJECTED)
        File(global, "legacy.md").writeText(MemoryParser.format("legacy", "legacy", "legacy-main-private",
            contextLoad = ContextLoad.FULL, contextCategory = ContextCategory.MAIN_INJECTED)
            .replace("context_category: MAIN_INJECTED", "context_category: INJECTED"))
        val role = library.render(project.path, "s1", ContextOrigin.MEMORY, memberName = "any-role")
        assertEquals("", role)
        val main = rendered()
        assertTrue(main.contains("legacy-main-private"))
        assertTrue(main.contains("project-main-private"))
        val other = temp.newFolder("injection-other")
        every { memories.listMemoriesInScope(MemoryScope.PROJECT, other.path) } returns emptyList()
        val otherMain = library.withPromptSnapshot(ctx().copy(projectRoot = other.path)) {
            library.render(other.path, "s1", ContextOrigin.MEMORY)
        }
        assertFalse(otherMain.contains("project-main-private"))
        assertTrue(otherMain.contains("legacy-main-private"))
    }


    @Test fun roleInjectionDoesNotUnlockAnotherSessionsTemporaryMemory() {
        memory("role", "role-shared-payload", ContextLoad.FULL, category = ContextCategory.ROLE_INJECTED)
        memory("temporary", "session-one-only", ContextLoad.FULL, category = ContextCategory.TEMPORARY, session = "s1")
        val member = mockk<AgentDefinition>()
        every { member.inject } returns setOf(InjectPart.MEMORY)
        listOf("s1", "s2").forEach { session ->
            val text = library.withPromptSnapshot(ctx(session).copy(agentDefinition = member)) {
                library.render(project.path, session, ContextOrigin.MEMORY)
            }
            assertTrue(text.contains("role-shared-payload"))
            assertEquals(session == "s1", text.contains("session-one-only"))
        }
    }

    @Test fun failedTopLevelRoleSnapshotIsRemovedBeforeFollowingMainRender() {
        memory("role", "role-only-payload", ContextLoad.FULL, category = ContextCategory.ROLE_INJECTED)
        memory("main", "main-only-payload", ContextLoad.FULL, category = ContextCategory.MAIN_INJECTED)
        val member = mockk<AgentDefinition>()
        every { member.inject } returns emptySet()
        try {
            library.withPromptSnapshot(ctx().copy(agentDefinition = member)) {
                assertTrue(library.render(project.path, "s1", ContextOrigin.MEMORY).contains("role-only-payload"))
                throw IllegalStateException("top-level-snapshot-test")
            }
        } catch (e: IllegalStateException) { assertEquals("top-level-snapshot-test", e.message) }
        val direct = library.render(project.path, "s1", ContextOrigin.MEMORY)
        assertTrue(direct.contains("main-only-payload"))
        assertFalse(direct.contains("role-only-payload"))
    }

    @Test fun defaultChildWithoutDefinitionStillUsesRoleAudience() {
        memory("role", "role-only-body", ContextLoad.FULL, category = ContextCategory.ROLE_INJECTED)
        memory("main", "main-only-body", ContextLoad.FULL, category = ContextCategory.MAIN_INJECTED)
        assertNull(ctx().agentDefinition)
        val child = library.withPromptSnapshot(ctx(), roleSession = true) {
            library.render(project.path, "s1", ContextOrigin.MEMORY)
        }
        assertTrue(child.contains("role-only-body"))
        assertFalse(child.contains("main-only-body"))
        val main = rendered()
        assertTrue(main.contains("main-only-body"))
        assertFalse(main.contains("role-only-body"))
    }


    @Test fun folderDeleteRejectsStaleSnapshotAndMovesOnlySelectedScopeAndCategory() {
        fun put(name: String, scope: MemoryScope, category: ContextCategory, folder: String) {
            File(directory(scope), "$name.md").writeText(MemoryParser.format(name, "说明", "$name-body",
                contextCategory = category, contextFolder = folder, contextRequest = "agent/$name.md"))
        }
        put("nested", MemoryScope.PROJECT, ContextCategory.RULE, "资料/子/孙")
        put("global", MemoryScope.GLOBAL, ContextCategory.RULE, "资料/子")
        put("prefix", MemoryScope.PROJECT, ContextCategory.RULE, "资料/子库")
        put("category", MemoryScope.PROJECT, ContextCategory.LONG_TERM, "资料/子")
        val before = library.list(project.path)
        var markerRemoved = false
        put("nested", MemoryScope.PROJECT, ContextCategory.RULE, "资料/子/孙/新")
        assertFalse(library.deleteFolder(ContextCategory.RULE, "资料/子", MemoryScope.PROJECT, project.path, before) { markerRemoved = true; true })
        assertFalse(markerRemoved)
        assertTrue(library.deleteFolder(ContextCategory.RULE, "资料/子", MemoryScope.PROJECT, project.path, library.list(project.path)) { markerRemoved = true; true })
        assertTrue(markerRemoved)
        val after = library.list(project.path)
        assertEquals("资料", after.single { it.title == "nested" }.folder)
        assertEquals("nested-body", after.single { it.title == "nested" }.content)
        assertEquals("agent/nested.md", after.single { it.title == "nested" }.requestKey)
        assertEquals("资料/子", after.single { it.title == "global" }.folder)
        assertEquals("资料/子库", after.single { it.title == "prefix" }.folder)
        assertEquals("资料/子", after.single { it.title == "category" }.folder)
        assertFalse(library.deleteFolder(ContextCategory.RULE, "资料", MemoryScope.PROJECT, "/other", after) { true })
    }

    @Test fun failedMarkerDeletionRestoresOriginalBytesAndEmptyFolderCanDelete() {
        memory("entry", "body", ContextLoad.OFF)
        val file = File(global, "entry.md")
        file.writeText(file.readText().replace("---\nbody", "context_folder: \"目录/子\"\n---\nbody"))
        val before = file.readText()
        assertFalse(library.deleteFolder(ContextCategory.LONG_TERM, "目录", MemoryScope.GLOBAL, project.path, library.list(project.path)) { false })
        assertEquals(before, file.readText())
        assertTrue(library.deleteFolder(ContextCategory.RULE, "空目录", MemoryScope.GLOBAL, project.path, library.list(project.path)) { true })
    }

}
