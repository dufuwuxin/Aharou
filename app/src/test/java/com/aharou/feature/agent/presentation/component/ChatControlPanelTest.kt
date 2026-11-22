package com.aharou.feature.agent.presentation.component

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aharou.feature.agent.domain.model.ReasoningEffort
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = com.aharou.feature.agent.presentation.ProjectTaskConversationTestApplication::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ChatControlPanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun effortPanelUsesDiscreteSliderAndEmbedsOpenAiServiceTier() {
        val provider = AIProviderConfig(id = "openai", name = "OpenAI", type = ProviderType.OPENAI,
            apiKey = "", baseUrl = "https://api.openai.com", defaultModel = "gpt-6.1")
        var selectedEffort = ReasoningEffort.DEFAULT
        var selectedTier: String? = "fast"
        compose.setContent {
            MaterialTheme {
                ReasoningEffortSelector(
                    effort = selectedEffort,
                    onChange = { selectedEffort = it },
                    enabled = true,
                    serviceTierSelector = {
                        OpenAIServiceTierSelector(provider, enabled = true) { selectedTier = it }
                    },
                )
            }
        }

        compose.onNodeWithText("默认", substring = true).performClick()
        compose.onNodeWithTag("reasoning_effort_slider").assertExists()
        compose.onNodeWithText("速度档位: Fast（快速）").assertExists()
        compose.onNodeWithText("速度档位: Fast（快速）").performClick()
        compose.onNodeWithText("默认（不指定档位）").performClick()
        compose.runOnIdle {
            assertEquals(ReasoningEffort.DEFAULT, selectedEffort)
            assertEquals(null, selectedTier)
        }
    }
}
