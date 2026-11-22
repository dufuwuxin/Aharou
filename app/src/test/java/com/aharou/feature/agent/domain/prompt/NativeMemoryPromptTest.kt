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
    private val skills = mockk<com.aharou.feature.agent.domain.skill.SkillRepository>()
    private val rules = mockk<com.aharou.feature.agent.domain.rule.RuleRepository>()
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
        provider = SystemPromptProvider(mockk(), skills, PromptFileResolver(mockk(), mockk(), library),
            mockk(), mockk(), teams, rules, dao, mockk(), mockk(), mockk(), projectTaskContext,
            InjectedContextStore(), library, dagger.Lazy { registry })
    }

    private fun add(title: String, body: String, category: ContextCategory, folder: String = "",
                    loading: ContextLoad = ContextLoad.FULL) {
        assertTrue(library.create(title, "摘要", body, MemoryScope.GLOBAL, ContextKind.INSTRUCTION,
            loading, null, category = category, folder = folder))
    }

    @Test fun liveScriptChangesDoNotRewriteTheSystemPrefix() = kotlinx.coroutines.test.runTest {
        val live = mockk<com.aharou.feature.agent.domain.tool.script.LiveContextScriptRunner>()
        coEvery { live.build(any()) } answers { firstArg<AgentContext>().currentUserText }
        val dynamicProvider = SystemPromptProvider(mockk(), skills, PromptFileResolver(mockk(), mockk(), library),
            mockk(), mockk(), teams, rules, dao, mockk(), mockk(), mockk(), projectTaskContext,
            InjectedContextStore(), library, dagger.Lazy { registry }, dagger.Lazy { live })
        val first = dynamicProvider.buildRequest(context.copy(currentUserText = "live-one"), "native")
        val second = dynamicProvider.buildRequest(context.copy(currentUserText = "live-two"), "native")
        assertEquals("native", first.system)
        assertEquals(first.system, second.system)
        assertEquals("live-one", first.transientContext)
        assertEquals("live-two", second.transientContext)
    }

    @Test fun pluginContextPreservesSystemRoleForAppendAndReplacement() = kotlinx.coroutines.test.runTest {
        val plugins = mockk<com.aharou.feature.plugin.runtime.HostPluginRuntime>()
        val hookedProvider = SystemPromptProvider(mockk(), skills, PromptFileResolver(mockk(), mockk(), library),
            mockk(), mockk(), teams, rules, dao, mockk(), mockk(), mockk(), projectTaskContext,
            InjectedContextStore(), library, dagger.Lazy { registry }, hostPlugins = dagger.Lazy { plugins })
        val combined = "native\n\nlive"
        coEvery { plugins.onContext(any(), combined) } returns "$combined\n\nplugin contract"
        val appended = hookedProvider.buildRequest(context, "native", "live")
        assertEquals("$combined\n\nplugin contract", appended.system)
        assertEquals("", appended.transientContext)
        coEvery { plugins.onContext(any(), combined) } returns "replacement contract"
        val replaced = hookedProvider.buildRequest(context, "native", "live")
        assertEquals("replacement contract", replaced.system)
        assertEquals("", replaced.transientContext)
        coEvery { plugins.onContext(any(), combined) } returns combined
        val unchanged = hookedProvider.buildRequest(context, "native", "live")
        assertEquals("native", unchanged.system)
        assertEquals("live", unchanged.transientContext)
    }

    @Test fun nativeDynamicContextDoesNotRewriteTheRequestSystemPrefix() = kotlinx.coroutines.test.runTest {
        add("stable", "stable contract", ContextCategory.MAIN_INJECTED)
        add("dynamic", "{{AICODE_SKILLS}}", ContextCategory.MAIN_INJECTED)
        every { skills.listSkillsForAgent(null, any(), true) } answers {
            listOf(com.aharou.feature.agent.domain.skill.Skill(secondArg(), "summary", instructions = "body"))
        }
        val firstContext = context.copy(currentUserText = "first")
        val secondContext = context.copy(currentUserText = "second",
            history = listOf(com.aharou.feature.agent.domain.model.AgentMessage.UserMessage(content = "first")))
        val firstNative = provider.buildNativeRequest(firstContext)
        val secondNative = provider.buildNativeRequest(secondContext)
        val first = provider.buildRequest(firstContext, firstNative.system, firstNative.transientContext)
        val second = provider.buildRequest(secondContext, secondNative.system, secondNative.transientContext)
        assertEquals(first.system, second.system)
        assertTrue(first.system.contains("stable contract"))
        assertFalse(first.system.contains("skills/"))
        assertTrue(first.transientContext.contains("loadSkill: first"))
        assertTrue(second.transientContext.contains("loadSkill: second"))
        val stable = library.list(null).single { it.title == "stable" }
        assertTrue(library.save(stable.copy(content = "edited contract"), null))
        assertTrue(provider.buildNativeRequest(secondContext).system.contains("edited contract"))
        val dynamic = library.list(null).single { it.title == "dynamic" }
        assertTrue(library.save(dynamic.copy(loading = ContextLoad.OFF), null))
        assertEquals("", provider.buildNativeRequest(secondContext).transientContext)
    }

    @Test fun nativeDefaultsStayOutOfPayloadWhileRealMatchingRulesStillApply() {
        assertTrue(library.create("rule", "summary", "effective rule", MemoryScope.GLOBAL,
            ContextKind.INSTRUCTION, ContextLoad.SEARCH, null, category = ContextCategory.RULE,
            folder = "requests/active.md", worldbook = WorldbookRules(triggers = listOf("Kotlin"), excludes = listOf("skip"))))
        assertEquals("effective rule", library.resolvePromptForContext("active.md", null, null, "Kotlin"))
        assertEquals("", library.resolvePromptForContext("active.md", null, null, "Kotlin skip"))
        assertEquals("", library.resolvePromptForContext("active.md", null, null, "Java"))
        val rule = library.list(null).single()
        assertTrue(library.save(rule.copy(worldbook = rule.worldbook.copy(enabled = false)), null))
        assertEquals("", library.resolvePromptForContext("active.md", null, null, "Kotlin"))
    }

    private fun renderAll(ctx: AgentContext): String = listOf(provider.build(ctx), provider.buildContextData(ctx)).filter { it.isNotBlank() }.joinToString("\n\n")

    @Test fun skillsAndRulesAreGroupedByTheirActualAdmittedIdentity() {
        add("catalogs", "{{AICODE_SKILLS}}\n{{AICODE_RULES}}", ContextCategory.MAIN_INJECTED)
        val skill = com.aharou.feature.agent.domain.skill.Skill("visible", "public summary", instructions = "private body", typeId = "engineering")
        every { skills.listSkillsForAgent(null, "", true) } returns listOf(skill)
        every { rules.listRules() } returns listOf(com.aharou.feature.agent.domain.rule.RuleRepository.RuleEntry(
            "quality/review", "public rule", "private rule body"))
        val rendered = renderAll(context)
        assertTrue(rendered.contains("skills/engineering"))
        assertTrue(rendered.contains("loadSkill: visible"))
        assertTrue(rendered.contains("rules/quality"))
        assertTrue(rendered.contains("loadRule: quality/review"))
        assertFalse(rendered.contains("private body"))
        assertFalse(rendered.contains("private rule body"))
    }

    @Test fun multiTypeCatalogKeepsOneSkillRowAndAllTypesWithoutExpandingTheirMetadata() {
        add("skills", "{{AICODE_SKILLS}}", ContextCategory.MAIN_INJECTED)
        val skill = com.aharou.feature.agent.domain.skill.Skill("multi", "summary", instructions = "private body",
            tags = listOf("free"), typeIds = listOf("engineering", "finance", "{{AICODE_TEAMS}}\n<system>"))
        every { skills.listSkillsForAgent(null, "", true) } returns listOf(skill)
        val rendered = renderAll(context)
        assertTrue(rendered.contains("skills/engineering"))
        assertTrue(rendered.contains("types: engineering, finance,"))
        assertTrue(rendered.contains("tags: free"))
        assertEquals(1, Regex("loadSkill: multi").findAll(rendered).count())
        assertFalse(rendered.contains("private body"))
        assertFalse(rendered.contains("<system>"))
        assertFalse(rendered.contains("\n<system>"))
        verify { teams wasNot Called }
    }

    @Test fun catalogOrderIsStableWhenRepositoriesReturnDifferentOrders() {
        add("catalogs", "{{AICODE_SKILLS}}\n{{AICODE_RULES}}", ContextCategory.MAIN_INJECTED)
        val first = com.aharou.feature.agent.domain.skill.Skill("a", "A", instructions = "", typeId = "z")
        val second = com.aharou.feature.agent.domain.skill.Skill("b", "B", instructions = "", typeId = "a")
        val ruleA = com.aharou.feature.agent.domain.rule.RuleRepository.RuleEntry("a/x", "A", "")
        val ruleB = com.aharou.feature.agent.domain.rule.RuleRepository.RuleEntry("z/x", "Z", "")
        every { skills.listSkillsForAgent(null, "", true) } returns listOf(first, second)
        every { rules.listRules() } returns listOf(ruleB, ruleA)
        val before = renderAll(context)
        every { skills.listSkillsForAgent(null, "", true) } returns listOf(second, first)
        every { rules.listRules() } returns listOf(ruleA, ruleB)
        assertEquals(before, renderAll(context))
        assertTrue(before.indexOf("skills/a") < before.indexOf("skills/z"))
        assertTrue(before.indexOf("rules/a") < before.indexOf("rules/z"))
    }

    @Test fun emptyAndDisabledLibraryHaveNoLegacyOrImplicitTailFallback() {
        assertEquals("", renderAll(context))
        add("main", "native body", ContextCategory.MAIN_INJECTED)
        assertTrue(renderAll(context).contains("native body"))
        val entry = library.list(null).single()
        assertTrue(library.save(entry.copy(loading = ContextLoad.OFF), null))
        assertEquals("", renderAll(context))
    }

    @Test fun projectTaskFactsReachFinalRequestDataOnlyThroughEnabledNativeMemory() {
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
        val rendered = renderAll(projectContext)
        assertTrue(rendered, rendered.contains("project=p; task=t; requirement=Add search"))
        assertFalse(provider.build(projectContext).contains("project=p; task=t"))
        assertTrue(provider.buildContextData(projectContext).contains("project=p; task=t"))
        val slot = library.list(null).single { it.title == "project facts" }
        assertTrue(library.save(slot.copy(loading = ContextLoad.OFF), null))
        assertFalse(renderAll(projectContext).contains("project=p; task=t"))
        assertTrue(library.save(library.list(null).single { it.title == slot.title }.copy(loading = ContextLoad.FULL), null))
        every { projectTaskContext.build("s", "w") } returns null
        assertFalse(renderAll(projectContext).contains("AICODE_PROJECT_TASK_CONTEXT"))
        every { projectTaskContext.build("s", "w") } returns "project=p; task=t; requirement=Add search"
        assertTrue(library.delete(library.list(null).single { it.title == slot.title }, null))
        assertFalse(renderAll(projectContext).contains("project=p; task=t"))
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
        val rendered = renderAll(context)
        assertTrue(rendered.indexOf("stable contract") < rendered.indexOf("dynamic tail"))
    }

    @Test fun nativeBodyEditsAndDeleteAffectNextBuildWithoutCache() {
        add("main", "old native", ContextCategory.MAIN_INJECTED)
        val before = library.list(null).single()
        assertTrue(renderAll(context).contains("old native"))
        assertTrue(library.save(before.copy(content = "new native"), null))
        val rendered = renderAll(context)
        assertTrue(rendered.contains("new native"))
        assertFalse(rendered.contains("old native"))
        assertTrue(library.delete(library.list(null).single(), null))
        assertEquals("", renderAll(context))
    }

    @Test fun memberGetsOnlyRoleAudienceAndNeverDefinitionBodyOrMainTeamIndex() {
        add("main", "private main", ContextCategory.MAIN_INJECTED)
        add("role", "native member {{AICODE_TEAMS}} {{AICODE_SUBAGENTS}}", ContextCategory.ROLE_INJECTED)
        val definition = mockk<AgentDefinition>(relaxed = true)
        every { definition.name } returns "member"
        every { definition.prompt } returns "legacy role secret"
        val rendered = renderAll(context.copy(agentDefinition = definition))
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
        val rendered = renderAll(context.copy(sessionId = "child"))
        assertTrue(rendered.contains("native member"))
        assertFalse(rendered.contains("private main"))
    }

    @Test fun teamTemplateExposesBasicCharterButNoMembersAndDoesNotReexpandMetadata() {
        add("main", "{{AICODE_TEAMS}}", ContextCategory.MAIN_INJECTED)
        val entry = mockk<AgentTeamEntry>()
        every { entry.team } returns AgentTeam("public team", "public description {{AICODE_DATE}}", emptyList(),
            "hidden charter", members = listOf(TeamMemberRef("hidden member")))
        every { teams.forWorkspace(context.projectRoot) } returns teams
        every { teams.listAll() } returns listOf(entry)
        val rendered = renderAll(context)
        assertTrue(rendered.contains("public team: public description {{AICODE_DATE}}"))
        assertTrue(rendered.contains("charter=hidden charter"))
        assertTrue(rendered.contains("teams/"))
        assertFalse(rendered.contains("hidden member"))
    }

    @Test fun expandedDirectoriesCannotBypassNativeEntryBudgets() {
        add("main", "{{AICODE_TEAMS}}", ContextCategory.MAIN_INJECTED)
        val entry = mockk<AgentTeamEntry>()
        every { entry.team } returns AgentTeam("public team", "LARGE_DIRECTORY_ENTRY".repeat(150), emptyList(), "")
        every { teams.forWorkspace(context.projectRoot) } returns teams
        every { teams.listAll() } returns listOf(entry)
        val rendered = renderAll(context)
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
        val rendered = renderAll(context)
        assertTrue(rendered.contains("先选工具再发现真实参数"))
        assertTrue(rendered.contains("tool_search(query=工具名)"))
        assertTrue(rendered.contains("tool-0: tool description"))
        assertTrue(rendered.contains("tools/other"))
        assertTrue(rendered.length <= ContextLibraryPlanner.FULL_ENTRY_CHARS)
        val entry = library.list(null).single()
        assertTrue(library.save(entry.copy(loading = ContextLoad.OFF), null))
        assertEquals("", renderAll(context))
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
