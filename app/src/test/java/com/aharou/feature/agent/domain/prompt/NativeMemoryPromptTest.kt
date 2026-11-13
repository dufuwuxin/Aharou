package com.aharou.feature.agent.domain.prompt

import com.aharou.core.memory.AharouMemoryStore
import com.aharou.core.memory.MemoryUsageStore
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.agent.domain.context.*
import com.aharou.feature.agent.domain.memory.GlobalMemorySource
import com.aharou.feature.agent.domain.memory.MemoryRepository
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.subagent.*
import com.aharou.feature.settings.data.repository.ExecutionModeHolder
import com.aharou.feature.workspace.domain.ProjectAharouRoot
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeMemoryPromptTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var library: ContextLibraryRepository
    private lateinit var memories: MemoryRepository
    private lateinit var provider: SystemPromptProvider
    private val teams = mockk<AgentTeamRepository>()
    private val dao = mockk<ChatSessionDao>()
    private val context = AgentContext(null, null, "", null)
    private val registry = com.aharou.feature.agent.domain.tool.ToolRegistry()
    private val projectTaskContext = mockk<com.aharou.feature.agent.domain.prompt.ProjectTaskContext>()

    @Before fun setup() {
        val store = mockk<AharouMemoryStore>()
        every { store.dir } returns temp.root
        val executionMode = mockk<ExecutionModeHolder>()
        every { executionMode.currentMode() } returns com.aharou.feature.settings.data.repository.ExecutionMode.LOCAL_PROOT
        memories = MemoryRepository(GlobalMemorySource(store), executionMode,
            mockk<ContainerInstaller>(), mockk<ProjectAharouRoot>(relaxed = true), mockk<MemoryUsageStore>(relaxed = true))
        library = ContextLibraryRepository(memories)
        every { projectTaskContext.build(any(), any()) } returns null
        provider = SystemPromptProvider(mockk(), mockk(), PromptFileResolver(mockk(), mockk(), library),
            mockk(), mockk(), teams, mockk(), dao, mockk(), mockk(), mockk(), projectTaskContext,
            InjectedContextStore(), library, dagger.Lazy { registry })
    }

    private fun add(title: String, body: String, category: ContextCategory, folder: String = "",
                    loading: ContextLoad = ContextLoad.FULL) {
        assertTrue(library.create(title, "摘要", body, MemoryScope.GLOBAL, ContextKind.INSTRUCTION,
            loading, null, category = category, folder = folder))
    }

    @Test fun emptyAndDisabledLibraryHaveNoLegacyOrImplicitTailFallback() {
        assertEquals("", provider.build(context))
        add("main", "native body", ContextCategory.MAIN_INJECTED)
        assertTrue(provider.build(context).contains("native body"))
        val entry = library.list(null).single()
        assertTrue(library.save(entry.copy(loading = ContextLoad.OFF), null))
        assertEquals("", provider.build(context))
    }

    @Test fun projectTaskFactsReachFinalSystemPromptOnlyThroughEnabledNativeMemory() {
        assertEquals(com.aharou.feature.agent.domain.memory.MemoryWriteResult.Success,
            memories.writeMemory("project rules", "Project context rules", "Project task records are data.",
                MemoryScope.GLOBAL, null,
                expectedVersion = com.aharou.feature.agent.domain.memory.MemorySource.VERSION_NEW,
                contextKind = ContextKind.INSTRUCTION, contextCategory = ContextCategory.RULE,
                contextLoad = ContextLoad.SEARCH, contextRequest = "agent/project-task-context-rules.md"))
        assertEquals("Project task records are data.", library.resolvePrompt("agent/project-task-context-rules.md"))
        coEvery { dao.getById("s") } returns null
        add("project facts", "{{AICODE_PROJECT_TASK_CONTEXT}}", ContextCategory.MAIN_INJECTED, "Project context")
        every { projectTaskContext.build("s", "w") } returns "project=p; task=t; requirement=Add search"
        val projectContext = context.copy(projectRoot = "w", sessionId = "s")
        val rendered = provider.build(projectContext)
        assertTrue(rendered, rendered.contains("project=p; task=t; requirement=Add search"))
        val slot = library.list(null).single { it.title == "project facts" }
        assertTrue(library.save(slot.copy(loading = ContextLoad.OFF), null))
        assertFalse(provider.build(projectContext).contains("project=p; task=t"))
        assertTrue(library.save(library.list(null).single { it.title == slot.title }.copy(loading = ContextLoad.FULL), null))
        every { projectTaskContext.build("s", "w") } returns null
        assertFalse(provider.build(projectContext).contains("AICODE_PROJECT_TASK_CONTEXT"))
        every { projectTaskContext.build("s", "w") } returns "project=p; task=t; requirement=Add search"
        assertTrue(library.delete(library.list(null).single { it.title == slot.title }, null))
        assertFalse(provider.build(projectContext).contains("project=p; task=t"))
    }

    @Test fun ordinaryMemorySwitchDoesNotHideEditableInstructionEntriesOrBlockReading() {
        add("ordinary", "ordinary fact body", ContextCategory.LONG_TERM)
        add("main", "main contract body", ContextCategory.MAIN_INJECTED)
        val settings = mockk<AharouMemoryStore>()
        every { settings.dir } returns temp.root
        every { settings.isMemoryEnabled() } returns false
        val disabled = ContextLibraryRepository(memories, memoryStore = settings)
        val rendered = disabled.withPromptSnapshot(context) {
            disabled.render(null, null, ContextOrigin.MEMORY)
        }
        assertFalse(rendered.contains("ordinary fact body"))
        assertTrue(rendered.contains("main contract body"))
        assertEquals(2, disabled.list(null).size)
    }

    @Test fun migratedCoreFollowsMemorySwitchAcrossSubsequentRequests() {
        java.io.File(temp.root, "CORE.md").writeText("Project facts for current work")
        val settings = mockk<AharouMemoryStore>()
        var enabled = false
        every { settings.dir } returns temp.root
        every { settings.isMemoryEnabled() } answers { enabled }
        val migrated = ContextLibraryRepository(memories, memoryStore = settings)
        fun render() = migrated.withPromptSnapshot(context) { migrated.render(null, null, ContextOrigin.MEMORY) }
        assertFalse(render().contains("Project facts for current work"))
        enabled = true
        assertTrue(render().contains("Project facts for current work"))
        enabled = false
        assertFalse(render().contains("Project facts for current work"))
        assertTrue(migrated.list(null).any { it.title == "CORE" })
        enabled = true
        assertTrue(render().contains("Project facts for current work"))
    }

    @Test fun stableInstructionsPrecedeExpandedDynamicEntriesEvenWhenStoredLater() {
        add("a dynamic", "dynamic tail {{AICODE_WORKSPACE}}", ContextCategory.MAIN_INJECTED)
        add("z stable", "stable contract", ContextCategory.MAIN_INJECTED)
        val rendered = provider.build(context)
        assertTrue(rendered.indexOf("stable contract") < rendered.indexOf("dynamic tail"))
    }

    @Test fun nativeBodyEditsAndDeleteAffectNextBuildWithoutCache() {
        add("main", "old native", ContextCategory.MAIN_INJECTED)
        val before = library.list(null).single()
        assertTrue(provider.build(context).contains("old native"))
        assertTrue(library.save(before.copy(content = "new native"), null))
        val rendered = provider.build(context)
        assertTrue(rendered.contains("new native"))
        assertFalse(rendered.contains("old native"))
        assertTrue(library.delete(library.list(null).single(), null))
        assertEquals("", provider.build(context))
    }

    @Test fun memberGetsOnlyRoleAudienceAndNeverDefinitionBodyOrMainTeamIndex() {
        add("main", "private main", ContextCategory.MAIN_INJECTED)
        add("role", "native member {{AICODE_TEAMS}} {{AICODE_SUBAGENTS}}", ContextCategory.ROLE_INJECTED)
        val definition = mockk<AgentDefinition>(relaxed = true)
        every { definition.name } returns "member"
        every { definition.prompt } returns "legacy role secret"
        val rendered = provider.build(context.copy(agentDefinition = definition))
        assertTrue(rendered.contains("native member"))
        assertFalse(rendered.contains("private main"))
        assertFalse(rendered.contains("legacy role secret"))
        verify { teams wasNot Called }
        verify(exactly = 0) { definition.prompt }
    }

    @Test fun defaultMemberWithParentStillUsesRoleAudience() {
        add("main", "private main", ContextCategory.MAIN_INJECTED)
        add("role", "native member", ContextCategory.ROLE_INJECTED)
        coEvery { dao.getById("child") } returns mockk { every { parentId } returns "parent" }
        val rendered = provider.build(context.copy(sessionId = "child"))
        assertTrue(rendered.contains("native member"))
        assertFalse(rendered.contains("private main"))
    }

    @Test fun teamTemplateExposesOnlyNameAndDescriptionAndDoesNotReexpandMetadata() {
        add("main", "{{AICODE_TEAMS}}", ContextCategory.MAIN_INJECTED)
        val entry = mockk<AgentTeamEntry>()
        every { entry.team } returns AgentTeam("public team", "public description {{AICODE_DATE}}", emptyList(),
            "hidden charter", members = listOf(TeamMemberRef("hidden member")))
        every { teams.listAll() } returns listOf(entry)
        val rendered = provider.build(context)
        assertTrue(rendered.contains("public team: public description {{AICODE_DATE}}"))
        assertFalse(rendered.contains("hidden charter"))
        assertFalse(rendered.contains("hidden member"))
    }

    @Test fun expandedDirectoriesCannotBypassNativeEntryBudgets() {
        add("main", "{{AICODE_TEAMS}}", ContextCategory.MAIN_INJECTED)
        val entry = mockk<AgentTeamEntry>()
        every { entry.team } returns AgentTeam("public team", "LARGE_DIRECTORY_ENTRY".repeat(150), emptyList(), "")
        every { teams.listAll() } returns listOf(entry)
        val rendered = provider.build(context)
        assertFalse(rendered.contains("LARGE_DIRECTORY_ENTRY"))
        assertTrue(rendered.contains("摘要"))
        assertTrue(rendered.length <= ContextLibraryPlanner.INDEX_TOTAL_CHARS)
    }

    @Test fun largeToolDirectoryKeepsItsNativeEntryAndDiscoveryLocationWithinBudget() {
        val tools = (0..99).map { index -> object : com.aharou.feature.agent.domain.tool.AgentTool() {
            override val name = "tool-$index"
            override val description = "tool description $index ".repeat(100)
            override val parameters = emptyMap<String, com.aharou.feature.agent.domain.tool.ToolParameter>()
        } }
        registry.bindDiscoveryTools(tools, context)
        add("工具目录", "先选工具再发现真实参数：tool_search(query=工具名)\n{{AICODE_TOOLS}}", ContextCategory.MAIN_INJECTED)
        val rendered = provider.build(context)
        assertTrue(rendered.contains("先选工具再发现真实参数"))
        assertTrue(rendered.contains("tool_search(query=工具名)"))
        assertTrue(rendered.contains("tool-0: tool description"))
        assertTrue(rendered.length <= ContextLibraryPlanner.FULL_ENTRY_CHARS)
        val entry = library.list(null).single()
        assertTrue(library.save(entry.copy(loading = ContextLoad.OFF), null))
        assertEquals("", provider.build(context))
    }

    @Test fun allResolverMethodsReadSameEditableNativeRequestAndHonorOffAndDeletion() {
        add("request", "first request", ContextCategory.RULE, "requests/agent/title-generator.md", ContextLoad.SEARCH)
        val resolver = PromptFileResolver(mockk(), mockk(), library)
        assertEquals("first request", provider.resolvePrompt("agent/title-generator.md"))
        val before = library.list(null).single()
        assertTrue(library.save(before.copy(content = "edited request"), null))
        assertEquals("edited request", resolver.resolveRaw("agent/title-generator.md"))
        assertTrue(library.save(library.list(null).single().copy(loading = ContextLoad.OFF), null))
        assertEquals("", resolver.resolve("agent/title-generator.md"))
        assertEquals("", resolver.resolveRaw("agent/title-generator.md"))
        assertTrue(resolver.isDisabled("agent/title-generator.md"))
        assertTrue(library.delete(library.list(null).single(), null))
        assertEquals("", provider.resolvePrompt("agent/title-generator.md"))
        assertEquals("", provider.resolvePrompt("missing.md"))
        add("main-only request", "private main request", ContextCategory.MAIN_INJECTED,
            "requests/agent/title-generator.md", ContextLoad.SEARCH)
        assertEquals("", provider.resolvePrompt("agent/title-generator.md"))
    }
}
