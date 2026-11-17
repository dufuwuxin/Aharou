package com.aharou.feature.settings.presentation

import com.aharou.R
import com.aharou.feature.settings.presentation.component.SubAgentsList
import com.aharou.feature.agent.domain.subagent.AgentDefinitionScope
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.aharou.feature.agent.presentation.ProjectTaskConversationTestApplication
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectTaskConversationTestApplication::class, sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SubAgentSelectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun longPressAndTapShowSelectionAndExitRestoresDetailClick() {
        var opened = 0
        val entries = listOf("alpha", "beta").map { name -> SubAgentUiEntry(name, "description", AgentDefinitionScope.PROJECT,
            false, null, null, null, null, emptyList(), emptyList(), inject = emptySet(), prompt = "", filePath = null) }
        compose.setContent { MaterialTheme { SubAgentsList("Project", entries, {}, { opened++ }, { _, _ -> }) } }
        val first = compose.onNode(hasText("alpha") and hasClickAction())
        val second = compose.onNode(hasText("beta") and hasClickAction())
        first.performTouchInput { longClick() }
        first.assertIsSelected()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.subagents_selected)).assertExists()
        second.performClick().assertIsSelected()
        first.performClick().assertIsNotSelected()
        assertEquals(0, opened)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.common_cancel)).performClick()
        first.performClick()
        assertEquals(1, opened)
    }
}
