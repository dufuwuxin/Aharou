package com.aharou.feature.agent.presentation.component

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.aharou.R
import com.aharou.feature.agent.presentation.AgentUIMessage
import com.aharou.feature.agent.presentation.ChatMessagesState
import com.aharou.feature.agent.presentation.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = com.aharou.feature.agent.presentation.ProjectTaskConversationTestApplication::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ConversationNavigationPopupTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun loadingEarlierMessagesCallsOnlyItsCallbackAndKeepsPopupOpen() {
        var loadMoreCalls = 0
        var dismissCalls = 0
        var scrollCalls = 0
        compose.setContent {
            MaterialTheme {
                ConversationNavigationPopup(
                    sessionId = SESSION_ID,
                    messagesState = readyState(hasMore = true, includeUserMessage = false),
                    bodyVisible = true,
                    canLocate = { true },
                    onJump = { true },
                    onScrollToBottom = { scrollCalls++ },
                    onLoadMore = { loadMoreCalls++ },
                    onDismiss = { dismissCalls++ }
                )
            }
        }

        compose.onNodeWithText(compose.activity.getString(R.string.conversation_nav_load_earlier))
            .assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.conversation_nav_empty_partial))
            .assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.conversation_nav_load_earlier))
            .performClick()
        compose.onNodeWithTag("conversation_navigation_popup").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, loadMoreCalls)
            assertEquals(0, dismissCalls)
            assertEquals(0, scrollCalls)
        }
    }

    @Test fun loadingAnEarlierPageUpdatesProjectionAndKeepsItsHasMoreBoundary() {
        var selectedId: String? = null
        val newerMessage = AgentUIMessage("synthetic-newer-id", MessageRole.USER, "Synthetic newer page message", timestamp = 2L)
        val earlierMessage = AgentUIMessage(EARLIER_MESSAGE_ID, MessageRole.USER, EARLIER_MESSAGE_TEXT, timestamp = 1L)
        val messagesState = mutableStateOf(
            ChatMessagesState(SESSION_ID, listOf(newerMessage), loaded = true, hasMore = true)
        )
        compose.setContent {
            MaterialTheme {
                ConversationNavigationPopup(
                    sessionId = SESSION_ID,
                    messagesState = messagesState.value,
                    bodyVisible = true,
                    canLocate = { id -> messagesState.value.messages.any { it.id == id } },
                    onJump = { selectedId = it; true },
                    onScrollToBottom = {},
                    onLoadMore = {
                        messagesState.value = messagesState.value.copy(
                            messages = listOf(earlierMessage, newerMessage),
                            hasMore = false
                        )
                    },
                    onDismiss = {}
                )
            }
        }

        compose.onNodeWithText(EARLIER_MESSAGE_TEXT).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.conversation_nav_load_earlier))
            .performClick()
        compose.onNodeWithText(EARLIER_MESSAGE_TEXT).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.conversation_nav_load_earlier))
            .assertDoesNotExist()
        compose.onNodeWithText(EARLIER_MESSAGE_TEXT).performClick()
        compose.runOnIdle { assertEquals(EARLIER_MESSAGE_ID, selectedId) }
    }

    @Test fun completeHistoryHidesLoadButtonAndSelectedUserMessageUsesItsOriginalId() {
        var selectedId: String? = null
        compose.setContent {
            MaterialTheme {
                ConversationNavigationPopup(
                    sessionId = SESSION_ID,
                    messagesState = readyState(hasMore = false),
                    bodyVisible = true,
                    canLocate = { it == USER_MESSAGE_ID },
                    onJump = { selectedId = it; true },
                    onScrollToBottom = {},
                    onLoadMore = { error("No earlier page exists") },
                    onDismiss = {}
                )
            }
        }

        compose.onNodeWithText(compose.activity.getString(R.string.conversation_nav_load_earlier))
            .assertDoesNotExist()
        compose.onNodeWithText(USER_MESSAGE_TEXT).performClick()
        compose.runOnIdle { assertEquals(USER_MESSAGE_ID, selectedId) }
    }

    @Test fun hiddenBodyDisablesNavigationToLoadedMessage() {
        var jumpCalls = 0
        compose.setContent {
            MaterialTheme {
                ConversationNavigationPopup(
                    sessionId = SESSION_ID,
                    messagesState = readyState(hasMore = false),
                    bodyVisible = false,
                    canLocate = { true },
                    onJump = { jumpCalls++; true },
                    onScrollToBottom = {},
                    onLoadMore = {},
                    onDismiss = {}
                )
            }
        }

        compose.onNodeWithText(USER_MESSAGE_TEXT).assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, jumpCalls) }
    }

    private fun readyState(hasMore: Boolean, includeUserMessage: Boolean = true) = ChatMessagesState(
        sessionId = SESSION_ID,
        messages = if (includeUserMessage) listOf(
            AgentUIMessage(USER_MESSAGE_ID, MessageRole.USER, USER_MESSAGE_TEXT, timestamp = 1L)
        ) else emptyList(),
        loaded = true,
        hasMore = hasMore
    )

    private companion object {
        const val SESSION_ID = "synthetic-session"
        const val USER_MESSAGE_ID = "synthetic-user-message-id"
        const val USER_MESSAGE_TEXT = "Synthetic earlier user message"
        const val EARLIER_MESSAGE_ID = "synthetic-earlier-page-message-id"
        const val EARLIER_MESSAGE_TEXT = "Synthetic message from newly loaded page"
    }
}
