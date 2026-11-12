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
    private val executionIds = setOf("main-execution-contract", "member-execution-contract", "execution-source-map")
    private val legacyRecordedIds = setOf("main-foundation", "member-foundation", "title-request",
        "main-tool-directory", "member-tool-directory")
    private fun seeds() = BuiltinContextEntries(File(temp.root, ".context-defaults-v1")) { asset.readText() }
    private fun legacyReceipt() = File(temp.root, ".context-defaults-v1").apply {
        writeText("version=1-legacy\n" + legacyRecordedIds.joinToString("\n") + "\n")
    }
    private fun memories(): MemoryRepository {
        val store = mockk<AharouMemoryStore>()
        every { store.dir } returns temp.root
        return MemoryRepository(GlobalMemorySource(store), ExecutionModeHolder(), mockk<ContainerInstaller>(),
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
        val request = default.single { it.id == "title-request" }
        val removed = default.single { it.id == "main-foundation" }
        File(temp.root, "renamed-request.md").writeText(MemoryParser.format("改名请求", "自定义", "用户正文",
            contextCategory = ContextCategory.RULE, contextLoad = ContextLoad.OFF,
            contextFolder = "requests/${request.requestKey}"))
        val injection = default.single { it.id == "member-foundation" }
        File(temp.root, "renamed-injection.md").writeText(MemoryParser.format("改名注入", "自定义", "保留正文",
            contextCategory = injection.category, contextLoad = ContextLoad.OFF, contextFolder = "内置默认"))
        val entries = ContextLibraryRepository(memory, seeds()).list(null)
        assertFalse(entries.any { it.title == request.title || it.title == injection.title || it.title == removed.title })
        assertEquals(7, entries.size)
        assertEquals(request.requestKey, entries.single { it.title == "改名请求" }.requestKey)
        assertEquals("辅助请求", entries.single { it.title == "改名请求" }.folder)
        assertEquals(ContextLoad.OFF, entries.single { it.title == "改名注入" }.loading)
        assertEquals(setOf(request.id, injection.id, removed.id, "main-tool-directory", "member-tool-directory") + executionIds,
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
        val addedTitles = seeds().defaults().filter { it.id in executionIds }.map { it.title }.toSet()
        assertEquals(setOf("仅存规则", "主代理工具目录", "成员工具目录") + addedTitles, first.map { it.title }.toSet())
        assertEquals(4, first.count { it.category.isInjection })
        val second = ContextLibraryRepository(memory, seeds()).list(null)
        assertEquals(first.map { it.title }, second.map { it.title })
        assertEquals(4, second.count { it.category.isInjection })
        assertEquals(legacyRecordedIds + executionIds,
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
        assertEquals("", library.resolvePrompt(request.requestKey))
    }

    @Test fun legacyToolDirectoryIsAddedOnceWithoutRestoringDeletedOrDisabledEntries() {
        val memory = memories()
        File(temp.root, ".context-defaults-v1").writeText("version=1-legacy\nmain-foundation\nmember-foundation\ntitle-request\n")
        val library = ContextLibraryRepository(memory, seeds())
        val first = library.list(null)
        val addedTitles = seeds().defaults().filter { it.id in executionIds }.map { it.title }.toSet()
        assertEquals(setOf("主代理工具目录", "成员工具目录") + addedTitles, first.map { it.title }.toSet())
        val entry = first.single { it.title == "主代理工具目录" }
        assertTrue(library.save(entry.copy(content = "用户目录", loading = ContextLoad.OFF), null))
        val other = library.list(null).single { it.title == "成员工具目录" }
        assertTrue(library.delete(other, null))
        val next = ContextLibraryRepository(memory, seeds()).list(null)
        assertEquals(4, next.size)
        assertEquals(addedTitles + entry.title, next.map { it.title }.toSet())
        assertEquals("用户目录", next.single { it.id == entry.id }.content)
        assertEquals(ContextLoad.OFF, next.single { it.id == entry.id }.loading)
        assertEquals("version=1-legacy", File(temp.root, ".context-defaults-v1").readLines().first())
    }

    @Test fun legacyReceiptAddsOnlyExecutionContractsWithoutRefreshingOldBodies() {
        val memory = memories()
        val plain = ContextLibraryRepository(memory)
        val defaults = seeds().defaults()
        val old = defaults.single { it.id == "main-foundation" }
        assertTrue(plain.create(old.title, "用户说明", "旧正文保留", MemoryScope.GLOBAL,
            ContextKind.INSTRUCTION, ContextLoad.OFF, null, category = old.category))
        val file = File(plain.list(null).single().sourceLabel)
        val bytes = file.readText()
        val receipt = legacyReceipt()
        val first = ContextLibraryRepository(memory, seeds()).list(null)
        val additions = defaults.filter { it.id in executionIds }
        assertEquals(setOf(old.title) + additions.map { it.title }, first.map { it.title }.toSet())
        assertEquals(bytes, file.readText())
        assertEquals(ContextLoad.OFF, first.single { it.title == old.title }.loading)
        for (expected in additions) {
            val actual = first.single { it.title == expected.title }
            assertEquals(expected.content, actual.content)
            assertEquals(expected.category, actual.category)
            assertEquals(expected.loading, actual.loading)
            assertEquals(expected.folder, actual.folder)
            assertNull(actual.requestKey)
        }
        assertEquals(ContextCategory.MAIN_INJECTED, additions.single { it.id == "main-execution-contract" }.category)
        assertEquals(ContextCategory.ROLE_INJECTED, additions.single { it.id == "member-execution-contract" }.category)
        assertTrue(additions.filter { it.id != "execution-source-map" }.all { it.loading == ContextLoad.FULL })
        assertEquals(ContextCategory.RULE, additions.single { it.id == "execution-source-map" }.category)
        assertEquals(ContextLoad.SEARCH, additions.single { it.id == "execution-source-map" }.loading)
        assertEquals("version=1-legacy", receipt.readLines().first())
        assertEquals(legacyRecordedIds + executionIds, receipt.readLines().drop(1).toSet())
        val recorded = receipt.readText()
        assertEquals(first, ContextLibraryRepository(memory, seeds()).list(null))
        assertEquals(recorded, receipt.readText())
    }

    @Test fun legacyExecutionContractsRespectUserNameOffRenameAndDeletion() {
        val memory = memories()
        val defaults = seeds().defaults()
        val main = defaults.single { it.id == "main-execution-contract" }
        val member = defaults.single { it.id == "member-execution-contract" }
        val sourceMap = defaults.single { it.id == "execution-source-map" }
        val plain = ContextLibraryRepository(memory)
        assertTrue(plain.create(main.title, "自定义说明", "用户已有契约", MemoryScope.GLOBAL,
            ContextKind.INSTRUCTION, ContextLoad.OFF, null, category = main.category))
        val existing = plain.list(null).single()
        val bytes = File(existing.sourceLabel).readText()
        val receipt = legacyReceipt()
        val library = ContextLibraryRepository(memory, seeds())
        val first = library.list(null)
        assertEquals(setOf(main.title, member.title, sourceMap.title), first.map { it.title }.toSet())
        assertEquals(bytes, File(existing.sourceLabel).readText())
        assertEquals(existing, first.single { it.title == main.title })
        assertTrue(library.save(first.single { it.title == member.title }.copy(
            title = "用户改名契约", content = "用户改写正文", loading = ContextLoad.OFF), null))
        assertTrue(library.delete(first.single { it.title == sourceMap.title }, null))
        val recorded = receipt.readText()
        val next = ContextLibraryRepository(memory, seeds()).list(null)
        assertEquals(setOf(main.title, "用户改名契约"), next.map { it.title }.toSet())
        assertEquals("用户已有契约", next.single { it.title == main.title }.content)
        assertEquals("用户改写正文", next.single { it.title == "用户改名契约" }.content)
        assertTrue(next.all { it.loading == ContextLoad.OFF })
        assertEquals(legacyRecordedIds + executionIds, receipt.readLines().drop(1).toSet())
        assertEquals(recorded, receipt.readText())
    }

    @Test fun realManifestExecutionContractsReachOnlyTheirAudienceAndRespectOff() {
        val library = ContextLibraryRepository(memories(), seeds())
        val defaults = seeds().defaults()
        val main = defaults.single { it.id == "main-execution-contract" }
        val member = defaults.single { it.id == "member-execution-contract" }
        val sourceMap = defaults.single { it.id == "execution-source-map" }
        val context = com.aharou.feature.agent.domain.model.AgentContext(null, null, temp.root.path, null, sessionId = "main")
        val mainText = library.withPromptSnapshot(context) { library.render(temp.root.path, "main", ContextOrigin.MEMORY) }
        assertTrue(mainText.contains(main.content))
        assertFalse(mainText.contains(member.content))
        assertFalse(mainText.contains(sourceMap.content))
        val role = mockk<com.aharou.feature.agent.domain.subagent.AgentDefinition>()
        every { role.name } returns "fixture-member"
        every { role.tags } returns emptyList()
        every { role.inject } returns emptySet()
        val roleContext = context.copy(agentDefinition = role, sessionId = "member")
        val memberText = library.withPromptSnapshot(roleContext) {
            library.render(temp.root.path, "member", ContextOrigin.MEMORY, memberName = role.name)
        }
        assertTrue(memberText.contains(member.content))
        assertFalse(memberText.contains(main.content))
        val installed = library.list(null).single { it.title == main.title }
        assertTrue(library.save(installed.copy(loading = ContextLoad.OFF), null))
        val afterOff = library.withPromptSnapshot(context) { library.render(temp.root.path, "main", ContextOrigin.MEMORY) }
        assertFalse(afterOff.contains(main.content))
        assertTrue(library.search("执行机制来源与适配", null).any { it.title == sourceMap.title })
    }

    @Test fun finalSystemPromptExpandsNativeEntriesAndKeepsExecutionAudience() {
        val library = ContextLibraryRepository(memories(), seeds())
        val defaults = seeds().defaults()
        val main = defaults.single { it.id == "main-execution-contract" }
        val member = defaults.single { it.id == "member-execution-contract" }
        val skills = mockk<com.aharou.feature.agent.domain.skill.SkillRepository>(relaxed = true)
        val teams = mockk<com.aharou.feature.agent.domain.subagent.AgentTeamRepository>(relaxed = true)
        val rules = mockk<com.aharou.feature.agent.domain.rule.RuleRepository>(relaxed = true)
        val profiles = mockk<com.aharou.feature.agent.domain.container.ContainerProfileResolver>()
        val profile = mockk<com.aharou.feature.agent.domain.container.ContainerProfile>()
        every { profiles.forWorkspace(any()) } returns profile
        every { profile.mode } returns com.aharou.feature.settings.data.repository.ExecutionMode.LOCAL_PROOT
        val store = mockk<com.aharou.feature.agent.domain.prompt.InjectedContextStore>(relaxed = true)
        val provider = com.aharou.feature.agent.domain.prompt.SystemPromptProvider(
            mockk(), skills, mockk(), mockk(), mockk(), teams, rules, mockk(), mockk(),
            profiles, mockk(), mockk(), store, library)
        val context = com.aharou.feature.agent.domain.model.AgentContext(null, null, temp.root.path, null)
        val text = provider.build(context)
        assertTrue(text.contains(main.content))
        assertFalse(text.contains(member.content))
        assertTrue(text.contains(temp.root.path))
        assertFalse(text.contains("{{AICODE_"))
        assertFalse(text.contains("README SHA-256="))
        verify(exactly = 1) { store.record(null, text) }
        val role = mockk<com.aharou.feature.agent.domain.subagent.AgentDefinition>()
        every { role.name } returns "fixture-member"
        every { role.tags } returns emptyList()
        every { role.inject } returns emptySet()
        val roleText = provider.build(context.copy(agentDefinition = role))
        assertTrue(roleText.contains(member.content))
        assertFalse(roleText.contains(main.content))
        assertFalse(roleText.contains("{{AICODE_"))
        verify(exactly = 1) { store.record(null, roleText) }
    }

    @Test fun corruptLegacyReceiptPreservesLibraryAndDoesNotInstallExecutionContracts() {
        val memory = memories()
        val plain = ContextLibraryRepository(memory)
        assertTrue(plain.create("用户规则", "说明", "保留正文", MemoryScope.GLOBAL,
            ContextKind.INSTRUCTION, ContextLoad.OFF, null, category = ContextCategory.RULE))
        val existing = plain.list(null).single()
        val bytes = File(existing.sourceLabel).readText()
        val receipt = File(temp.root, ".context-defaults-v1")
        for (corrupt in listOf("corrupt", "version=1-legacy\nunknown-default\n")) {
            receipt.writeText(corrupt)
            assertEquals(listOf(existing), ContextLibraryRepository(memory, seeds()).list(null))
            assertEquals(bytes, File(existing.sourceLabel).readText())
            assertEquals(corrupt, receipt.readText())
        }
    }

}
