package com.aharou.feature.settings.presentation

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aharou.R
import com.aharou.core.translation.OfflineTranslation
import com.aharou.core.translation.TranslationEntryPoint
import com.aharou.core.translation.TranslationModelState
import com.aharou.feature.agent.domain.context.ContextCategory
import com.aharou.feature.agent.domain.context.ContextEntry
import com.aharou.feature.agent.domain.context.ContextKind
import com.aharou.feature.agent.domain.context.ContextLoad
import com.aharou.feature.agent.domain.context.ContextOrigin
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.settings.presentation.component.ContextLibrarySection
import dagger.hilt.android.EntryPointAccessors
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ContextLibraryLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun isolateTranslationEntryPoint() {
        val translation = mockk<OfflineTranslation>(relaxed = true)
        every { translation.enabled } returns MutableStateFlow(false)
        every { translation.modelState } returns MutableStateFlow(TranslationModelState.READY)
        val entryPoint = mockk<TranslationEntryPoint>()
        every { entryPoint.translation() } returns translation
        mockkStatic(EntryPointAccessors::class)
        every { EntryPointAccessors.fromApplication(any(), TranslationEntryPoint::class.java) } returns entryPoint
    }

    @After fun clearTranslationEntryPoint() = unmockkStatic(EntryPointAccessors::class)

    @Test fun rootEntriesAreDirectlyEditableAndCategoryAppearsOnlyOnce() {
        showLibrary(entry("root-entry", ContextCategory.LONG_TERM), entry("rule-entry", ContextCategory.RULE))
        compose.onNodeWithText(text(R.string.context_workspace_heading)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.context_folder_root_title)).assertDoesNotExist()
        awaitText("root-entry")
        compose.onNodeWithText("rule-entry").assertDoesNotExist()
        compose.onNodeWithText("root-entry").performClick()
        compose.onNodeWithText(text(R.string.context_edit_title)).assertExists()
    }

    @Test fun graphRuleShortcutOpensRulesEvenWhenLibraryWasOnLongTerm() {
        showLibrary(entry("root-entry", ContextCategory.LONG_TERM), entry("rule-entry", ContextCategory.RULE))
        compose.onNodeWithText(text(R.string.context_graph_title)).performClick()
        compose.onNodeWithText(text(R.string.context_worldbook_edit_rules)).performClick()
        awaitText("rule-entry")
        compose.onNodeWithText("root-entry").assertDoesNotExist()
        compose.onNodeWithTag("context_library_search").assertExists()
    }

    private fun showLibrary(vararg entries: ContextEntry) {
        val viewModel = mockk<ContextLibraryViewModel>(relaxed = true)
        every { viewModel.state } returns MutableStateFlow(ContextLibraryState(entries = entries.toList(), loading = false)).asStateFlow()
        compose.setContent { MaterialTheme { ContextLibrarySection(viewModel) } }
    }

    private fun awaitText(value: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun entry(title: String, category: ContextCategory) = ContextEntry(
        title, title, "summary", "body", MemoryScope.GLOBAL, ContextKind.REFERENCE,
        ContextLoad.FULL, ContextOrigin.MEMORY, title, category = category,
    )

    private fun text(resource: Int) = compose.activity.getString(resource)
}
