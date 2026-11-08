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

    @Test fun searchFindsTitleSummaryAndBodyAcrossFoldersAndCategoriesEvenAtLibraryTop() {
        val title = entry("needle", ContextCategory.RULE, "规则/构建")
        val summary = entry("summary", ContextCategory.MAIN_INJECTED, "主代理/偏好").copy(description = "needle-summary")
        val body = entry("body", ContextCategory.ROLE_INJECTED, "角色/资料").copy(content = "padding ".repeat(70) + "needle-body at end")
        val all = listOf(title, summary, body)
        assertEquals(all, contextVisibleEntries(all, "needle", ContextCategory.LONG_TERM, null, null, null))
        assertEquals(all, contextVisibleEntries(all, "needle", ContextCategory.LONG_TERM, "unrelated", null, null))
        assertEquals(emptyList<ContextEntry>(), contextVisibleEntries(all, "", ContextCategory.LONG_TERM, null, null, null))
        assertEquals(listOf(title), contextVisibleEntries(all, "", ContextCategory.RULE, "规则/构建", null, null))
        val snippet = contextSearchSnippet(body, "needle-body")
        org.junit.Assert.assertTrue(snippet.contains("needle-body"))
        org.junit.Assert.assertTrue(snippet.length <= 182)
        assertEquals("角色/资料", body.folder)
        assertEquals("source-body", body.sourceLabel)
        assertEquals(listOf(summary), contextVisibleEntries(all, "needle-summary", ContextCategory.RULE, null, MemoryScope.PROJECT, ContextLoad.SEARCH))
    }

}
