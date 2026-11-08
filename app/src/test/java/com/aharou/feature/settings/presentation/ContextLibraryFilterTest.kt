package com.aharou.feature.settings.presentation

import com.aharou.feature.agent.domain.context.ContextCategory
import com.aharou.feature.agent.domain.context.ContextEntry
import com.aharou.feature.agent.domain.context.ContextKind
import com.aharou.feature.agent.domain.context.ContextLoad
import com.aharou.feature.agent.domain.context.ContextOrigin
import com.aharou.feature.agent.domain.memory.MemoryScope
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextLibraryFilterTest {
    private fun entry(id: String, category: ContextCategory, folder: String, scope: MemoryScope = MemoryScope.PROJECT) = ContextEntry(
        id, "标题-$id", "接口说明", "Only arm64 builds", scope, ContextKind.REFERENCE, ContextLoad.SEARCH,
        ContextOrigin.MEMORY, "source-$id", category = category, folder = folder,
    )

    @Test fun categoryAndFolderNeverMixSameNamedFolders() {
        val rules = entry("rule", ContextCategory.RULE, "构建")
        val facts = entry("fact", ContextCategory.LONG_TERM, "构建")
        assertEquals(listOf(rules), filterContextEntries(listOf(rules, facts), "", null, null, null, ContextCategory.RULE, "构建"))
    }

    @Test fun globalAndProjectWithSameTitleRemainSeparate() {
        val global = entry("global", ContextCategory.LONG_TERM, "", MemoryScope.GLOBAL)
        val project = entry("project", ContextCategory.LONG_TERM, "")
        assertEquals(listOf(project), filterContextEntries(listOf(global, project), "", MemoryScope.PROJECT, null, null))
        assertEquals(2, filterContextEntries(listOf(global, project), "", null, null, null).size)
    }

    @Test fun queryMatchesBodyAndSourceWithoutChangingOriginalList() {
        val item = entry("abc", ContextCategory.LONG_TERM, "")
        val items = listOf(item)
        assertEquals(items, filterContextEntries(items, "ARM64 source-abc", null, null, null))
        assertEquals(emptyList<ContextEntry>(), filterContextEntries(items, "ARM64 missing", null, null, null))
        assertEquals(items, listOf(item))
    }

    @Test fun loadingFilterDoesNotAssumeCategoryDefaults() {
        val item = entry("disabled-rule", ContextCategory.RULE, "").copy(loading = ContextLoad.OFF)
        assertEquals(listOf(item), filterContextEntries(listOf(item), "", null, ContextLoad.OFF, null))
        assertEquals(emptyList<ContextEntry>(), filterContextEntries(listOf(item), "", null, ContextLoad.FULL, null))
    }
}
