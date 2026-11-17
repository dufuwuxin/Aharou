package com.aharou.feature.settings.presentation

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aharou.feature.agent.domain.context.*
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.settings.presentation.component.ContextResourceTree
import com.aharou.feature.settings.presentation.component.contextResourceRows
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(application = com.aharou.feature.agent.presentation.ProjectTaskConversationTestApplication::class, sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ContextResourceTreeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun entry(id: String, scope: MemoryScope = MemoryScope.GLOBAL) = ContextEntry(id, id,
        "summary", "private body", scope, ContextKind.REFERENCE, ContextLoad.INDEX, ContextOrigin.MEMORY, id,
        folder = "资料/接口")

    @Test fun collapsedRootsAndFilteredSearchPreserveScopeAndParentFolders() {
        val all = listOf(entry("global"), entry("project", MemoryScope.PROJECT))
        val collapsed = contextResourceRows(all, emptySet(), "")
        assertEquals(2, collapsed.size)
        assertTrue(collapsed.all { it.depth == 0 && it.entry == null })
        val search = contextResourceRows(all, emptySet(), "project")
        assertEquals(listOf("project"), search.mapNotNull { it.entry?.id })
        assertEquals(listOf("资料", "资料/接口"), search.mapNotNull { it.folder })
        assertTrue(search.all { it.entries.all { entry -> entry.scope == MemoryScope.PROJECT } })
    }

    @Test fun actualTreeExpandsAndOpensTheOriginalEntry() {
        val entry = entry("接口说明")
        var selected: ContextEntry? = null
        compose.setContent { MaterialTheme { ContextResourceTree(listOf(entry)) { selected = it } } }
        compose.onNodeWithText("接口说明", substring = true).assertDoesNotExist()
        compose.onNodeWithText("全局", substring = true).performClick()
        compose.onNodeWithText("资料", substring = true).performClick()
        compose.onNodeWithText("接口", substring = true).performClick()
        compose.onNodeWithText("接口说明", substring = true).performClick()
        compose.runOnIdle { assertEquals(entry, selected) }
    }
}
