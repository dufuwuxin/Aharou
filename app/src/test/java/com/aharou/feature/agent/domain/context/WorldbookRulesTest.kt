package com.aharou.feature.agent.domain.context

import com.aharou.feature.agent.domain.memory.Memory
import com.aharou.feature.agent.domain.memory.MemoryRepository
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.subagent.AgentDefinition
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class WorldbookRulesTest {
    private fun entry(id: String, rules: WorldbookRules = WorldbookRules()) = ContextEntry(
        id, id, "summary", "body", MemoryScope.GLOBAL, ContextKind.REFERENCE,
        ContextLoad.FULL, ContextOrigin.MEMORY, id, worldbook = rules,
    )

    @Test fun ownedMemoryIsSharedOnlyWithExplicitRolesAndKeepsInheritanceGates() {
        val rules = WorldbookRules(ownerRole = "writer", sharedWithRoles = listOf("reviewer"))
        assertEquals(rules, WorldbookRules.fromJson(rules.toJson()))
        assertNotNull(rules.omission("", WorldbookAudience.MAIN, null))
        assertNotNull(rules.omission("", WorldbookAudience.ROLE, "stranger"))
        assertNull(rules.omission("", WorldbookAudience.ROLE, "writer"))
        assertNull(rules.omission("", WorldbookAudience.ROLE, "reviewer"))
        val owned = entry("owned", rules)
        assertEquals(listOf("owned"), ContextLibraryPlanner.plan(listOf(owned),
            injectionCategory = ContextCategory.ROLE_INJECTED, roleName = "reviewer").full.map { it.id })
        assertTrue(ContextLibraryPlanner.plan(listOf(owned), injectionCategory = ContextCategory.ROLE_INJECTED,
            roleName = "reviewer", memoryAllowed = false).full.isEmpty())
        assertTrue(ContextLibraryPlanner.plan(listOf(owned), injectionCategory = ContextCategory.ROLE_INJECTED,
            roleName = "stranger").full.isEmpty())
        assertNull(WorldbookRules.fromJson("{}").ownerRole)
    }

    @Test fun matchingUsesCurrentTextAndHonorsExclusionCaseAndAll() {
        val rules = WorldbookRules(triggers = listOf("Kotlin", "Android"), excludes = listOf("ignore"), matchAll = true)
        assertNull(rules.omission("kotlin ANDROID", WorldbookAudience.MAIN, null))
        assertNotNull(rules.omission("Kotlin", WorldbookAudience.MAIN, null))
        assertNotNull(rules.omission("Kotlin Android IGNORE", WorldbookAudience.MAIN, null))
        assertNotNull(rules.omission("", WorldbookAudience.MAIN, null))
        assertNotNull(rules.copy(caseSensitive = true).omission("kotlin Android", WorldbookAudience.MAIN, null))
        assertNull(rules.copy(matchAll = false).omission("Android", WorldbookAudience.MAIN, null))
        assertNull(WorldbookRules().omission("", WorldbookAudience.MAIN, null))
        assertNotNull(WorldbookRules(excludes = listOf("secret")).omission("", WorldbookAudience.MAIN, null))
    }

    @Test fun audienceAndRoleBindingsRequireKnownExactRole() {
        val rules = WorldbookRules(roles = listOf("reviewer"), audiences = listOf(WorldbookAudience.ROLE))
        assertNull(rules.omission("", WorldbookAudience.ROLE, "reviewer"))
        assertNotNull(rules.omission("", WorldbookAudience.ROLE, null))
        assertNotNull(rules.omission("", WorldbookAudience.ROLE, "Reviewer"))
        assertNotNull(rules.omission("", WorldbookAudience.MAIN, "reviewer"))
    }

    @Test fun activationNeverBypassesLoadingAudienceSessionOrMemoryGates() {
        val rules = WorldbookRules(triggers = listOf("needle"))
        val normal = entry("normal", rules)
        val entries = listOf(normal, entry("off", rules).copy(loading = ContextLoad.OFF),
            entry("search", rules).copy(loading = ContextLoad.SEARCH), entry("expired", rules).copy(enabled = false),
            entry("disabled", rules.copy(enabled = false)),
            entry("temp", rules).copy(category = ContextCategory.TEMPORARY, sessionId = "other"),
            entry("role", rules).copy(category = ContextCategory.ROLE_INJECTED))
        val result = ContextLibraryPlanner.plan(entries, sessionId = "current", userText = "needle")
        assertEquals(listOf("normal"), result.full.map { it.id })
        assertEquals(6, result.omitted.size)
        assertTrue(ContextLibraryPlanner.plan(entries, userText = "needle", memoryAllowed = false).full.isEmpty())
        assertTrue(ContextLibraryPlanner.plan(listOf(normal)).full.isEmpty())
        assertTrue(ContextLibraryPlanner.plan(listOf(normal.copy(category = ContextCategory.TEMPORARY)), userText = "needle").full.isEmpty())
    }

    @Test fun priorityIsStableAndStillConsumesBothOriginalBudgets() {
        val entries = (0..30).map { index -> entry("e$index", WorldbookRules(priority = if (index == 30) 100 else 0))
            .copy(content = "x".repeat(800), description = "d".repeat(240)) }
        val result = ContextLibraryPlanner.plan(entries)
        assertEquals("e30", result.full.first().id)
        assertEquals(listOf("e30", "e0", "e1"), result.full.map { it.id })
        assertTrue(result.full.sumOf { result.renderedEntries.getValue(it.id).length } <= ContextLibraryPlanner.FULL_TOTAL_CHARS)
        assertTrue(result.indexed.sumOf { result.renderedEntries.getValue(it.id).length } <= ContextLibraryPlanner.INDEX_TOTAL_CHARS)
        assertTrue(result.omitted.any { it.second == "index budget exceeded" })
        assertFalse(result.rendered.contains("confidence="))
    }

    @Test fun requestSnapshotAndPreviewSelectTheSameNativeEntries() {
        val memories = mockk<MemoryRepository>()
        val rules = WorldbookRules(triggers = listOf("current"))
        val rows = listOf(Memory("hit", "summary", MemoryScope.GLOBAL, content = "body", worldbook = rules,
            contextLoad = ContextLoad.FULL), Memory("miss", "summary", MemoryScope.GLOBAL, content = "body",
            worldbook = rules.copy(triggers = listOf("history")), contextLoad = ContextLoad.FULL))
        every { memories.listMemoriesInScope(MemoryScope.GLOBAL, any()) } returns rows
        every { memories.listMemoriesInScope(MemoryScope.PROJECT, any()) } returns emptyList()
        val library = ContextLibraryRepository(memories)
        val ctx = AgentContext(null, null, "/project", null, currentUserText = "current", sessionId = "s")
        val rendered = library.withPromptSnapshot(ctx) { library.render("/project", "s", ContextOrigin.MEMORY) }
        val preview = library.preview("/project", "current", "s")
        assertEquals(preview.rendered, rendered)
        assertEquals(listOf("hit"), preview.full.map { it.title })
        assertTrue(library.withPromptSnapshot(ctx.copy(currentUserText = "")) {
            library.render("/project", "s", ContextOrigin.MEMORY)
        }.isEmpty())
        assertEquals(rendered, library.withPromptSnapshot(ctx) {
            library.withPromptSnapshot(ctx.copy(currentUserText = "history")) { library.render("/project", "s", ContextOrigin.MEMORY) }
            library.render("/project", "s", ContextOrigin.MEMORY)
        })
    }

    @Test fun rolePreviewMatchesActualRoleAndMemberMemoryDisableStillWins() {
        val memories = mockk<MemoryRepository>()
        val rules = WorldbookRules(triggers = listOf("needle"), roles = listOf("reviewer"), audiences = listOf(WorldbookAudience.ROLE))
        val rows = listOf(Memory("role", "summary", MemoryScope.GLOBAL, content = "role body", worldbook = rules,
            contextCategory = ContextCategory.ROLE_INJECTED, contextLoad = ContextLoad.FULL),
            Memory("ordinary", "summary", MemoryScope.GLOBAL, content = "ordinary body", worldbook = rules, contextLoad = ContextLoad.FULL))
        every { memories.listMemoriesInScope(MemoryScope.GLOBAL, any()) } returns rows
        every { memories.listMemoriesInScope(MemoryScope.PROJECT, any()) } returns emptyList()
        val library = ContextLibraryRepository(memories)
        val role = AgentDefinition("reviewer", "review", inject = emptySet(), prompt = "")
        val ctx = AgentContext(null, null, "/project", null, currentUserText = "needle", sessionId = "s", agentDefinition = role)
        val actual = library.withPromptSnapshot(ctx) { library.render("/project", "s", ContextOrigin.MEMORY) }
        val preview = library.preview("/project", "needle", "s", roleName = "reviewer", memoryAllowed = false)
        assertEquals(preview.rendered, actual)
        assertEquals(listOf("role"), preview.full.map { it.title })
        assertTrue(library.preview("/project", "needle", "s").full.isEmpty())
    }

    @Test fun relationRetrievalKeepsMultiwordAndAndExplainsUserMetadata() {
        val linked = entry("linked", WorldbookRules(confidence = 0.4, importance = 70,
            relations = listOf(WorldbookRelation("target-id", WorldbookRelationType.CONTRADICTS))))
        assertEquals(listOf(linked), ContextSearch.search(listOf(linked), "target-id summary"))
        assertTrue(ContextSearch.search(listOf(linked), "target-id missing").isEmpty())
        assertTrue(ContextSearch.explain(linked).contains("CONTRADICTS->target-id"))
        assertTrue(ContextSearch.explain(linked).contains("user-authored"))
    }

    @Test fun invalidMetadataIsRejectedAndDefaultsRoundTrip() {
        assertEquals(WorldbookRules(), WorldbookRules.fromJson("{}"))
        assertEquals(WorldbookRules(), WorldbookRules.fromJson(WorldbookRules().toJson()))
        for (json in listOf("{\"confidence\":1.1}", "{\"importance\":-1}", "{\"triggers\":[\"\"]}", "{\"unknown\":true}")) {
            assertTrue(runCatching { WorldbookRules.fromJson(json) }.isFailure)
        }
        assertTrue(runCatching { WorldbookRules(confidence = Double.NaN) }.isFailure)
    }
}
