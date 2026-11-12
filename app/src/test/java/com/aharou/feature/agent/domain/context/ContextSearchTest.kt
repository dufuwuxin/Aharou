package com.aharou.feature.agent.domain.context

import com.aharou.feature.agent.domain.memory.MemoryScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ContextSearchTest {
    private fun entry(id: String, title: String = "Other", description: String = "Summary",
                      content: String = "Body", keywords: List<String> = emptyList()) = ContextEntry(
        id, title, description, content, MemoryScope.PROJECT, ContextKind.REFERENCE,
        ContextLoad.SEARCH, ContextOrigin.MEMORY, "source-$id", keywords = keywords,
    )

    @Test fun exactTitleThenKeywordThenSummaryThenBody() {
        val body = entry("body", content = "arm64 ".repeat(100))
        val summary = entry("summary", description = "Use arm64")
        val keyword = entry("keyword", keywords = listOf("arm64"))
        val title = entry("title", title = "ARM64")
        assertEquals(listOf(title, keyword, summary, body),
            ContextSearch.search(listOf(body, summary, keyword, title), "arm64"))
    }

    @Test fun tagsAndConfiguredWeightsChangeRankingWithoutTreatingConfidenceAsTruth() {
        val keyword = entry("keyword", keywords = listOf("needle"))
        val tag = entry("tag").copy(worldbook = WorldbookRules(tags = listOf("needle"), tagWeight = 2.0))
        assertEquals(listOf(tag, keyword), ContextSearch.search(listOf(keyword, tag), "needle"))
        val disabledWeight = keyword.copy(worldbook = WorldbookRules(keywordWeight = 0.0))
        assertTrue(ContextSearch.search(listOf(disabledWeight), "needle").isEmpty())
        val highImportance = keyword.copy(id = "important", worldbook = WorldbookRules(importance = 100, confidence = 0.1))
        assertEquals(listOf(highImportance, keyword), ContextSearch.search(listOf(keyword, highImportance), "needle"))
    }

    @Test fun allTermsMustMatchButMayComeFromDifferentFields() {
        val match = entry("match", title = "arm64", keywords = listOf("offline"))
        val partial = entry("partial", title = "arm64")
        assertEquals(listOf(match), ContextSearch.search(listOf(partial, match), " ARM64  offline "))
        assertTrue(ContextSearch.search(listOf(match), "arm64 missing").isEmpty())
    }

    @Test fun blankQueryAndTiesPreserveOriginalOrderWithoutMutatingInput() {
        val first = entry("first", content = "arm64")
        val second = entry("second", content = "arm64")
        val input = listOf(second, first)
        assertEquals(input, ContextSearch.search(input, "  "))
        assertEquals(input, ContextSearch.search(input, "arm64"))
        assertEquals(listOf(second, first), input)
    }

    @Test fun sourcePathsAreOnlyMatchedWhenExplicitlyRequested() {
        val item = entry("private-path")
        assertTrue(ContextSearch.search(listOf(item), "source-private-path").isEmpty())
        assertEquals(listOf(item), ContextSearch.search(listOf(item), "source-private-path", includeSource = true))
    }

    @Test fun matchingIsIndependentOfDeviceLocaleAndDoesNotChangeManagementVisibility() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val item = entry("disabled", title = "INDEX").copy(enabled = false, loading = ContextLoad.OFF)
            assertEquals(listOf(item), ContextSearch.search(listOf(item), "index"))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
