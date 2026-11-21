package com.aharou.feature.agent.domain.context

import com.aharou.feature.agent.domain.memory.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContextLibraryPlannerTest {
    @Test fun indexSkipsUnusedBodySanitizationWithoutChangingRequestText() {
        val body = "x".repeat(1_000_000)
        val indexed = entry("index-only", ContextLoad.INDEX).copy(content = body, description = "summary")
        val expected = ContextLibraryPlanner.plan(listOf(indexed.copy(content = "unused"))).rendered
        io.mockk.mockkObject(com.aharou.core.util.SecretMasker)
        try {
            io.mockk.every { com.aharou.core.util.SecretMasker.mask(any()) } answers { callOriginal() }
            val result = ContextLibraryPlanner.plan(listOf(indexed))
            assertEquals(expected, result.rendered)
            io.mockk.verify(exactly = 0) { com.aharou.core.util.SecretMasker.mask(body) }
        } finally { io.mockk.unmockkObject(com.aharou.core.util.SecretMasker) }
    }

    @Test fun headersExposeOnlyAdmittedHierarchyAndRetrievalIdentity() {
        val admitted = entry("visible", ContextLoad.INDEX).copy(folder = "Research/Files", requestKey = "ref.md")
        val hidden = admitted.copy(id = "hidden", enabled = false, folder = "Hidden")
        val preview = ContextLibraryPlanner.plan(listOf(admitted, hidden))
        assertTrue(preview.rendered, preview.rendered.contains("scope=GLOBAL"))
        assertTrue(preview.rendered.contains("category=LONG_TERM"))
        assertTrue(preview.rendered.contains("Research/Files"))
        assertTrue(preview.rendered.contains("ref.md"))
        assertFalse(preview.rendered.contains("Hidden"))
        assertEquals(preview.rendered, preview.renderedEntries.getValue("visible"))
    }

    @Test fun defaultRuleMetadataIsNotSentAndDisabledEntriesCannotSpendBudgets() {
        val visible = entry("visible", ContextLoad.FULL)
        val disabled = visible.copy(id = "disabled", worldbook = WorldbookRules(enabled = false), content = "hidden")
        val off = visible.copy(id = "off", loading = ContextLoad.OFF, content = "hidden")
        val expired = visible.copy(id = "expired", enabled = false, content = "hidden")
        val preview = ContextLibraryPlanner.plan(listOf(disabled, off, expired, visible))
        assertEquals(listOf(visible), preview.full)
        assertFalse(preview.rendered.contains("hidden"))
        listOf("enabled=", "trust=", "confidence=", "excludes=", "priority=", "folder=\"\"").forEach {
            assertFalse("Unexpected default metadata: $it", preview.rendered.contains(it))
        }
        val matched = visible.copy(worldbook = WorldbookRules(triggers = listOf("Kotlin"), excludes = listOf("skip")))
        assertEquals(listOf(matched), ContextLibraryPlanner.plan(listOf(matched), userText = "Kotlin").full)
        assertTrue(ContextLibraryPlanner.plan(listOf(matched), userText = "Kotlin skip").full.isEmpty())
    }

    @get:Rule val temp = TemporaryFolder()
    private fun entry(id: String, load: ContextLoad, scope: MemoryScope = MemoryScope.GLOBAL) = ContextEntry(
        id, id, "summary", "body", scope, ContextKind.REFERENCE, load, ContextOrigin.MEMORY, id
    )

    @Test fun stablePrefixSurvivesChangingDynamicInputsAndBudgetPressure() {
        val stable = (1..4).map { entry("stable$it", ContextLoad.FULL).copy(
            content = "stable-$it".repeat(90), worldbook = WorldbookRules(priority = 5 - it)) }
        val dynamic = entry("dynamic", ContextLoad.FULL).copy(dynamic = true,
            worldbook = WorldbookRules(priority = 99))
        val conditional = entry("conditional", ContextLoad.FULL).copy(
            worldbook = WorldbookRules(triggers = listOf("match-me"), priority = 100))
        val temporary = entry("temporary", ContextLoad.FULL).copy(category = ContextCategory.TEMPORARY, sessionId = "s1")
        val search = entry("retrieval", ContextLoad.SEARCH)
        val firstEntries = listOf(temporary, dynamic.copy(content = "value one"), search, conditional) + stable.reversed()
        val first = ContextLibraryPlanner.plan(firstEntries, "s1", userText = "match-me")
        assertEquals(first, ContextLibraryPlanner.plan(firstEntries, "s1", userText = "match-me"))
        val next = ContextLibraryPlanner.plan(listOf(dynamic.copy(content = "value two"), temporary, conditional, search) + stable,
            "s2", userText = "different query")
        val expectedPrefix = stable.map { first.renderedEntries.getValue(it.id) }.joinToString("\n\n")
        assertTrue(first.rendered.startsWith(expectedPrefix))
        assertTrue(next.rendered.startsWith(expectedPrefix))
        assertEquals(stable.take(3).map { it.id }, first.full.take(3).map { it.id })
        assertEquals(stable.last().id, first.indexed.first().id)
        assertTrue(first.rendered.contains("value one"))
        assertTrue(next.rendered.contains("value two"))
        assertTrue(first.renderedEntries.containsKey(conditional.id))
        assertFalse(next.renderedEntries.containsKey(conditional.id))
        assertTrue(first.renderedEntries.containsKey(temporary.id))
        assertFalse(next.renderedEntries.containsKey(temporary.id))
        assertFalse(first.renderedEntries.containsKey(search.id))
    }

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
