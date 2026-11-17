package com.aharou.feature.vault

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.aharou.feature.vault.presentation.VaultKeywordEditor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class VaultKeywordEditorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun separateCardsSaveIndependentLiteralValuesWithoutSplittingPunctuation() {
        var saved: List<Pair<String, String>>? = null
        compose.setContent { MaterialTheme { VaultKeywordEditor(listOf("first" to "原文一"), {}, { saved = it }) } }
        compose.onNodeWithTag("keyword_add").performScrollTo().performClick()
        compose.onNodeWithTag("keyword_value_1").performScrollTo().performTextInput("第二条,包含逗号\n和换行")
        compose.onNodeWithTag("keyword_save").performScrollTo().performClick()
        assertEquals(listOf("原文一", "第二条,包含逗号\n和换行"), requireNotNull(saved).map { it.second })
        assertEquals(2, requireNotNull(saved).map { it.first }.distinct().size)
    }
    @Test fun removingLastCardCanClearSelectionWithoutSavingOriginalCredentialDeletion() {
        var saved: List<Pair<String, String>>? = null
        compose.setContent { MaterialTheme { VaultKeywordEditor(listOf("existing" to "保留凭据"), {}, { saved = it }) } }
        compose.onNodeWithTag("keyword_remove_0").performScrollTo().performClick()
        compose.onNodeWithTag("keyword_save").performScrollTo().performClick()
        assertTrue(requireNotNull(saved).isEmpty())
    }
}
