package com.aharou.feature.agent.domain.context

import com.aharou.feature.agent.domain.memory.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContextLibraryPlannerTest {
    @get:Rule val temp = TemporaryFolder()
    private fun entry(id: String, load: ContextLoad, scope: MemoryScope = MemoryScope.GLOBAL) = ContextEntry(
        id, id, "summary", "body", scope, ContextKind.REFERENCE, load, ContextOrigin.MEMORY, id
    )

    @Test fun temporaryEntriesRequireTheirActualSession() {
        val temporary = entry("temp", ContextLoad.FULL).copy(category = ContextCategory.TEMPORARY, sessionId = "s1", folder = "work")
        assertTrue(ContextLibraryPlanner.plan(listOf(temporary)).full.isEmpty())
        assertTrue(ContextLibraryPlanner.plan(listOf(temporary), "s2").full.isEmpty())
        assertEquals(listOf(temporary), ContextLibraryPlanner.plan(listOf(temporary), "s1").full)
    }

    @Test fun nativeConsumerSharesThePreviewBudget() {
        val all = (1..8).map { entry("$it", ContextLoad.FULL).copy(content = "x".repeat(800)) }
        val preview = ContextLibraryPlanner.plan(all)
        val memory = ContextLibraryPlanner.plan(all, onlyOrigin = ContextOrigin.MEMORY)
        assertEquals(preview.full, memory.full)
        assertEquals(preview.indexed, memory.indexed)
        assertEquals(preview.rendered, memory.rendered)
    }

    @Test fun metadataRoundTripsAndDefaultIsIndex() {
        val file = temp.newFile("a.md")
        file.writeText(MemoryParser.format("a", "summary", "body", contextKind = ContextKind.INSTRUCTION, contextLoad = ContextLoad.FULL))
        val parsed = MemoryParser.parse(file, MemoryScope.GLOBAL)!!
        assertEquals(ContextKind.INSTRUCTION, parsed.contextKind)
        assertEquals(ContextLoad.FULL, parsed.contextLoad)
        file.writeText(MemoryParser.format("a", "summary", "body"))
        assertEquals(ContextLoad.INDEX, MemoryParser.parse(file, MemoryScope.GLOBAL)!!.contextLoad)
        assertEquals(ContextKind.REFERENCE, MemoryParser.parse(file, MemoryScope.GLOBAL)!!.contextKind)
        assertTrue(MemoryParser.parse(file, MemoryScope.GLOBAL)!!.hasContextMetadata)
        file.writeText("plain old content")
        assertEquals(ContextLoad.INDEX, MemoryParser.parse(file, MemoryScope.GLOBAL)!!.contextLoad)
        assertFalse(MemoryParser.parse(file, MemoryScope.GLOBAL)!!.hasContextMetadata)
    }

    @Test fun explicitModesAndSearchDoesNotAutoInline() {
        val all = ContextLoad.entries.map { entry(it.name, it) }
        val plan = ContextLibraryPlanner.plan(all)
        assertEquals(listOf("FULL"), plan.full.map { it.id })
        assertEquals(listOf("INDEX"), plan.indexed.map { it.id })
        assertEquals(setOf("SEARCH", "OFF"), plan.omitted.map { it.first.id }.toSet())
    }

    @Test fun identitiesIncludeScopeAndDuplicatesOnlyCollapseSameSource() {
        val a = entry("global:a", ContextLoad.FULL)
        val b = entry("project:a", ContextLoad.FULL, MemoryScope.PROJECT)
        assertEquals(listOf(a, b), ContextLibraryPlanner.plan(listOf(a, a, b)).full)
    }

    @Test fun fullBudgetsFallbackWholeEntryAndPendingNeverInlines() {
        val large = entry("large", ContextLoad.FULL).copy(content = "z".repeat(901))
        val pending = entry("pending", ContextLoad.FULL).copy(pending = true)
        val plan = ContextLibraryPlanner.plan(listOf(large, pending))
        assertTrue(plan.full.isEmpty())
        assertEquals(listOf(large, pending), plan.indexed)
        assertFalse(plan.rendered.contains("z".repeat(900)))
        assertTrue(plan.omitted.any { it.second.contains("pending") })
        val many = (1..10).map { entry("$it", ContextLoad.FULL).copy(content = "x".repeat(800)) }
        val budget = ContextLibraryPlanner.plan(many)
        assertTrue(budget.full.size < many.size)
        assertEquals(many.size, budget.full.size + budget.indexed.size)
    }

    @Test fun outputSanitizesSecretsInBodyAndIndex() {
        val a = entry("a", ContextLoad.FULL).copy(content = "api_key=sk-123456789012345678901234567890")
        val b = entry("b", ContextLoad.INDEX).copy(description = "token=abcdefgh1234567890abcdefgh")
        val rendered = ContextLibraryPlanner.plan(listOf(a, b)).rendered
        assertFalse(rendered.contains("123456789012345678901234567890"))
        assertFalse(rendered.contains("abcdefgh1234567890abcdefgh"))
    }

    @Test fun editAndOldSaveKeepContextFieldsAndVersionConflictsKeepData() {
        val file = temp.newFile("a.md")
        file.writeText(MemoryParser.format("a", "summary", "old", contextKind = ContextKind.INSTRUCTION, contextLoad = ContextLoad.FULL))
        val before = MemoryParser.parse(file, MemoryScope.GLOBAL)!!
        assertEquals(MemoryWriteResult.Success, MemoryFileStorage.saveChecked(temp.root, MemoryScope.GLOBAL, "a", "summary", "new", null, null, null, null, before.version))
        val after = MemoryParser.parse(file, MemoryScope.GLOBAL)!!
        assertEquals(ContextLoad.FULL, after.contextLoad)
        assertEquals(ContextKind.INSTRUCTION, after.contextKind)
        assertNotEquals(before.version, after.version)
        assertTrue(MemoryFileStorage.saveChecked(temp.root, MemoryScope.GLOBAL, "a", "summary", "bad", null, null, null, null, before.version) is MemoryWriteResult.VersionConflict)
        assertEquals("new", MemoryParser.parse(file, MemoryScope.GLOBAL)!!.content)
    }
    @Test fun offRoundTripsAndYamlBooleanOffRemainsOff() {
        val file = temp.newFile("off.md")
        file.writeText(MemoryParser.format("off", "summary", "private", contextLoad = ContextLoad.OFF))
        assertEquals(ContextLoad.OFF, MemoryParser.parse(file, MemoryScope.GLOBAL)!!.contextLoad)
        file.writeText(file.readText().replace("context_load: \"OFF\"", "context_load: OFF"))
        assertEquals(ContextLoad.OFF, MemoryParser.parse(file, MemoryScope.GLOBAL)!!.contextLoad)
        assertTrue(MemoryParser.parse(file, MemoryScope.GLOBAL)!!.hasContextMetadata)
    }


    @Test fun injectionAudiencesAreExclusiveAndDoNotRequireOrdinaryMemory() {
        val role = entry("role", ContextLoad.FULL).copy(category = ContextCategory.ROLE_INJECTED)
        val main = entry("main", ContextLoad.FULL).copy(category = ContextCategory.MAIN_INJECTED)
        val ordinary = entry("ordinary", ContextLoad.FULL)
        val all = listOf(main, ordinary, role)
        assertEquals(listOf(main, ordinary), ContextLibraryPlanner.plan(all).full)
        assertEquals(listOf(role), ContextLibraryPlanner.plan(all,
            injectionCategory = ContextCategory.ROLE_INJECTED, memoryAllowed = false).full)
        assertEquals(listOf(main), ContextLibraryPlanner.plan(all, memoryAllowed = false).full)
    }

    @Test fun injectionStillHonorsOffUnavailableSearchPendingAndWholeEntryBudgets() {
        val role = entry("role", ContextLoad.FULL).copy(category = ContextCategory.ROLE_INJECTED)
        val hidden = listOf(role.copy(id = "off", loading = ContextLoad.OFF),
            role.copy(id = "expired", enabled = false), role.copy(id = "search", loading = ContextLoad.SEARCH))
        val pending = role.copy(id = "pending", pending = true, content = "pending-private-body")
        val large = role.copy(id = "large", content = "z".repeat(901))
        val wrong = (1..30).map { role.copy(id = "main-$it", category = ContextCategory.MAIN_INJECTED, content = "x".repeat(800)) }
        val plan = ContextLibraryPlanner.plan(wrong + hidden + listOf(pending, large, role),
            injectionCategory = ContextCategory.ROLE_INJECTED, memoryAllowed = false)
        assertEquals(listOf(role), plan.full)
        assertEquals(listOf(pending, large), plan.indexed)
        assertFalse(plan.rendered.contains("pending-private-body"))
        assertFalse(plan.rendered.contains("z".repeat(900)))
        assertTrue(hidden.all { h -> plan.omitted.any { it.first.id == h.id } })
        val many = (1..100).map { role.copy(id = "r-$it", content = "x".repeat(800), description = "s".repeat(240)) }
        val bounded = ContextLibraryPlanner.plan(many, injectionCategory = ContextCategory.ROLE_INJECTED)
        assertTrue(bounded.full.sumOf { bounded.renderedEntries.getValue(it.id).length } <= ContextLibraryPlanner.FULL_TOTAL_CHARS)
        assertTrue(bounded.indexed.sumOf { bounded.renderedEntries.getValue(it.id).length } <= ContextLibraryPlanner.INDEX_TOTAL_CHARS)
        assertEquals(900, ContextLibraryPlanner.FULL_ENTRY_CHARS)
        assertEquals(3000, ContextLibraryPlanner.FULL_TOTAL_CHARS)
        assertEquals(6000, ContextLibraryPlanner.INDEX_TOTAL_CHARS)
    }

}
