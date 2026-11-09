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
    private lateinit var provider: SystemPromptProvider
    private val teams = mockk<AgentTeamRepository>()
    private val dao = mockk<ChatSessionDao>()
    private val context = AgentContext(null, null, "", null)

    @Before fun setup() {
        val store = mockk<AharouMemoryStore>()
        every { store.dir } returns temp.root
        library = ContextLibraryRepository(MemoryRepository(GlobalMemorySource(store), mockk<ExecutionModeHolder>(),
            mockk<ContainerInstaller>(), mockk<ProjectAharouRoot>(), mockk<MemoryUsageStore>(relaxed = true)))
        provider = SystemPromptProvider(mockk(), mockk(), PromptFileResolver(mockk(), mockk(), library),
            mockk(), mockk(), teams, mockk(), dao, mockk(), mockk(), mockk(), mockk(),
            InjectedContextStore(), library)
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
