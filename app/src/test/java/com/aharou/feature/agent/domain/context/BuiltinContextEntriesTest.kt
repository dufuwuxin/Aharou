package com.aharou.feature.agent.domain.context

import com.aharou.core.memory.AharouMemoryStore
import com.aharou.core.memory.MemoryUsageStore
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.agent.domain.memory.GlobalMemorySource
import com.aharou.feature.agent.domain.memory.MemoryCompactor
import com.aharou.feature.agent.domain.memory.Memory
import com.aharou.feature.agent.domain.memory.MemoryParser
import com.aharou.feature.agent.domain.memory.MemoryRepository
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.agent.domain.prompt.PromptFileResolver
import com.aharou.feature.agent.domain.workflow.AgentWorkflow
import com.aharou.feature.settings.data.repository.ExecutionModeHolder
import com.aharou.feature.workspace.domain.ProjectAharouRoot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BuiltinContextEntriesTest {
    @get:Rule val temp = TemporaryFolder()

    private val asset: File get() = listOf(File("src/main/assets/context-library/builtin.json"),
        File("app/src/main/assets/context-library/builtin.json")).first { it.isFile }
    private fun seeds() = BuiltinContextEntries(File(temp.root, ".context-defaults-v1")) { asset.readText() }
    private fun memories(): MemoryRepository {
        val store = mockk<AharouMemoryStore>()
        every { store.dir } returns temp.root
        return MemoryRepository(GlobalMemorySource(store), mockk<ExecutionModeHolder>(), mockk<ContainerInstaller>(),
            mockk<ProjectAharouRoot>(), mockk<MemoryUsageStore>(relaxed = true))
    }

    @Test fun manifestUsesNativeCategoriesAndFitsFullBodyBudgets() {
        val defaults = seeds().defaults()
        assertTrue(defaults.isNotEmpty())
        assertTrue(defaults.filter { it.loading == ContextLoad.FULL }.all {
            it.category.isInjection && it.content.length <= ContextLibraryPlanner.FULL_ENTRY_CHARS
        })
        assertEquals(25, defaults.count { it.requestKey != null })
        assertTrue(defaults.filter { it.requestKey != null }.all { it.folder == "辅助请求" && it.loading == ContextLoad.SEARCH })
        assertFalse(defaults.any { it.folder == "内置默认" || it.folder.startsWith("requests/") })
        for (category in listOf(ContextCategory.MAIN_INJECTED, ContextCategory.ROLE_INJECTED)) {
            val entries = defaults.filter { it.category == category }.map {
                ContextEntry(it.id, it.title, it.description, it.content, MemoryScope.GLOBAL,
                    ContextKind.INSTRUCTION, it.loading, ContextOrigin.MEMORY, it.id, category = it.category)
            }
            assertEquals(entries.size, ContextLibraryPlanner.plan(entries, injectionCategory = category).full.size)
        }
    }

    @Test fun seedListEditOffRenameAndDeleteSurviveRepositoryRecreation() {
        val memory = memories()
        val first = ContextLibraryRepository(memory, seeds())
        val entry = first.list(null).first { it.category == ContextCategory.MAIN_INJECTED }
        assertTrue(entry.editable)
        assertTrue(entry.version.isNotBlank())
        assertTrue(first.save(entry.copy(content = "用户已改", loading = ContextLoad.OFF), null))
        val second = ContextLibraryRepository(memory, seeds())
        val edited = second.list(null).single { it.id == entry.id }
        assertEquals("用户已改", edited.content)
        assertEquals(ContextLoad.OFF, edited.loading)
        assertTrue(second.save(edited.copy(title = "用户重命名"), null))
        val renamed = second.list(null).single { it.title == "用户重命名" }
        assertTrue(second.delete(renamed, null))
        val third = ContextLibraryRepository(memory, seeds())
        assertFalse(third.list(null).any { it.title == entry.title || it.title == renamed.title })
        assertEquals(seeds().defaults().size - 1, third.list(null).size)
    }

    @Test fun existingNativeUserEntryAlwaysWinsAndStaleVersionCannotOverwriteIt() {
        val memory = memories()
        val plain = ContextLibraryRepository(memory)
        val title = seeds().defaults().first().title
        assertTrue(plain.create(title, "用户说明", "先存在的正文", MemoryScope.GLOBAL,
            ContextKind.INSTRUCTION, ContextLoad.OFF, null, category = ContextCategory.MAIN_INJECTED))
        val seeded = ContextLibraryRepository(memory, seeds())
        val before = seeded.list(null).single { it.title == title }
        assertEquals("先存在的正文", before.content)
        assertEquals(ContextLoad.OFF, before.loading)
        assertTrue(seeded.save(before.copy(content = "新正文"), null))
        assertFalse(seeded.save(before.copy(content = "过期覆盖"), null))
        assertEquals("新正文", seeded.list(null).single { it.title == title }.content)
    }

    @Test fun automaticMaintenanceCannotMergeOrCompressNativeInstructions() = runBlocking {
        val repository = mockk<MemoryRepository>()
        val instruction = Memory("instruction", "description", MemoryScope.GLOBAL,
            content = "instruction body".repeat(65), contextKind = ContextKind.INSTRUCTION,
            contextCategory = ContextCategory.MAIN_INJECTED)
        val request = instruction.copy(name = "request", contextCategory = ContextCategory.RULE,
            contextFolder = "requests/agent/title-generator.md")
        val ordinary = instruction.copy(name = "ordinary", content = "ordinary fact ".repeat(75),
            contextCategory = ContextCategory.LONG_TERM, contextKind = ContextKind.REFERENCE)
        val compressed = "ordinary fact ".repeat(30).trim()
        val resolver = mockk<PromptFileResolver>()
        val workflow = mockk<AgentWorkflow>()
        every { repository.listMemories(null) } returns listOf(instruction, request, ordinary)
        every { resolver.resolve("agent/memory-compress.md") } returns "Compress the memory."
        coEvery { workflow.completeOnce(any(), any(), any()) } returns compressed
        every { repository.archiveContent(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns true
        every { repository.saveMemory(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns true
        val compactor = MemoryCompactor(repository, resolver, mockk(), workflow, mockk(), mockk(), mockk())
        assertEquals(listOf(ordinary), compactor.compactionCandidates(null, MemoryScope.GLOBAL))
        assertEquals(1, compactor.compressOversizedIfDue(null, MemoryScope.GLOBAL))
        coVerify(exactly = 1) {
            workflow.completeOnce("Compress the memory.",
                "name: ordinary\ndescription: description\n${ordinary.content}", null)
        }
        verify(exactly = 2) { repository.listMemories(null) }
        verify(exactly = 1) {
            repository.archiveContent(ordinary.name, ordinary.description, ordinary.content, ordinary.scope,
                null, ordinary.type, ordinary.keywords, ordinary.expiresAt, ordinary.pending)
            repository.saveMemory(ordinary.name, ordinary.description, compressed, ordinary.scope,
                null, ordinary.type, ordinary.expiresAt, ordinary.pending, ordinary.keywords)
            resolver.resolve("agent/memory-compress.md")
        }
        confirmVerified(repository, resolver, workflow)
    }

    @Test fun corruptReceiptNeverReinstallsOrCrashesExistingLibrary() {
        val memory = memories()
        val library = ContextLibraryRepository(memory, seeds())
        val entry = library.list(null).first()
        assertTrue(library.delete(entry, null))
        File(temp.root, ".context-defaults-v1").writeText("corrupt")
        val existing = ContextLibraryRepository(memory, seeds()).list(null)
        assertEquals(seeds().defaults().size - 1, existing.size)
        assertFalse(existing.any { it.id == entry.id })
    }

    @Test fun firstUpgradeAdoptsRenamedAndDeletedLegacyDefaultsWithoutReceipt() {
        val memory = memories()
        val default = seeds().defaults()
        val request = default.first { it.requestKey != null }
        val removed = default.first { it.category == ContextCategory.MAIN_INJECTED }
        File(temp.root, "renamed-request.md").writeText(MemoryParser.format("改名请求", "自定义", "用户正文",
            contextCategory = ContextCategory.RULE, contextLoad = ContextLoad.OFF,
            contextFolder = "requests/${request.requestKey}"))
        val injection = default.first { it.category == ContextCategory.ROLE_INJECTED }
        File(temp.root, "renamed-injection.md").writeText(MemoryParser.format("改名注入", "自定义", "保留正文",
            contextCategory = injection.category, contextLoad = ContextLoad.OFF, contextFolder = "内置默认"))
        val entries = ContextLibraryRepository(memory, seeds()).list(null)
        assertFalse(entries.any { it.title == request.title || it.title == injection.title || it.title == removed.title })
        assertEquals(2, entries.size)
        assertEquals(request.requestKey, entries.single { it.title == "改名请求" }.requestKey)
        assertEquals("辅助请求", entries.single { it.title == "改名请求" }.folder)
        assertEquals(ContextLoad.OFF, entries.single { it.title == "改名注入" }.loading)
        assertEquals(setOf(request.id, injection.id, removed.id),
            File(temp.root, ".context-defaults-v1").readLines().drop(1).toSet())
        val bytes = File(temp.root, "renamed-request.md").readText()
        ContextLibraryRepository(memory, seeds()).list(null)
        assertEquals(bytes, File(temp.root, "renamed-request.md").readText())
    }

    @Test fun ruleOnlyLegacyUpgradeWritesReceiptWithoutReseedingAfterRecreation() {
        val memory = memories()
        val request = seeds().defaults().single { it.id == "title-request" }
        File(temp.root, "rule-only-request.md").writeText(MemoryParser.format("仅存规则", "自定义", "用户正文",
            contextCategory = ContextCategory.RULE, contextLoad = ContextLoad.OFF,
            contextFolder = "requests/${request.requestKey}"))
        val first = ContextLibraryRepository(memory, seeds()).list(null)
        assertEquals(listOf("仅存规则"), first.map { it.title })
        assertTrue(first.none { it.category.isInjection })
        val second = ContextLibraryRepository(memory, seeds()).list(null)
        assertEquals(first.map { it.title }, second.map { it.title })
        assertTrue(second.none { it.category.isInjection })
        assertEquals(setOf("main-foundation", "member-foundation", "title-request"),
            File(temp.root, ".context-defaults-v1").readLines().drop(1).toSet())
    }

    @Test fun legacyLayoutMigrationPreservesCustomBodyOffRenameDeletionAndReceipt() {
        val memory = memories()
        val library = ContextLibraryRepository(memory, seeds())
        val initial = library.list(null)
        val request = initial.first { it.requestKey != null }
        val removed = initial.first { it.category == ContextCategory.MAIN_INJECTED }
        assertTrue(library.delete(removed, null))
        val file = File(request.sourceLabel)
        file.writeText(com.aharou.feature.agent.domain.memory.MemoryParser.format(
            com.aharou.feature.agent.domain.memory.MemoryParser.parse(file, MemoryScope.GLOBAL)!!.copy(
                name = "已改名请求", content = "用户正文\n保持", contextLoad = ContextLoad.OFF,
                contextFolder = "requests/" + request.requestKey, contextRequest = null)))
        val custom = File(temp.root, "custom.md")
        custom.writeText(com.aharou.feature.agent.domain.memory.MemoryParser.format("custom", "自定义", "custom-body",
            contextCategory = ContextCategory.RULE, contextFolder = "requests/agent/custom.md"))
        val receipt = File(temp.root, ".context-defaults-v1").readText()
        val migrated = ContextLibraryRepository(memory, seeds()).list(null)
        val edited = migrated.single { it.title == "已改名请求" }
        assertEquals(request.requestKey, edited.requestKey)
        assertEquals("辅助请求", edited.folder)
        assertEquals("用户正文\n保持", edited.content)
        assertEquals(ContextLoad.OFF, edited.loading)
        assertEquals("agent/custom.md", migrated.single { it.title == "custom" }.requestKey)
        assertFalse(migrated.any { it.title == removed.title })
        assertEquals(receipt, File(temp.root, ".context-defaults-v1").readText())
        val bytes = file.readText()
        ContextLibraryRepository(memory, seeds()).list(null)
        assertEquals(bytes, file.readText())
    }

    @Test fun requestRouteSurvivesFolderMoveRenameAndRepositoryRecreation() {
        val memory = memories()
        val library = ContextLibraryRepository(memory, seeds())
        val request = library.list(null).first { it.requestKey != null }
        assertTrue(library.save(request.copy(title = "改名", folder = "自定义/位置", content = "route-body"), null))
        assertEquals("route-body", ContextLibraryRepository(memory, seeds()).resolvePrompt(request.requestKey!!))
        val edited = library.list(null).single { it.title == "改名" }
        assertTrue(library.save(edited.copy(loading = ContextLoad.OFF), null))
        assertEquals("", library.resolvePrompt(request.requestKey!!))
    }

}
