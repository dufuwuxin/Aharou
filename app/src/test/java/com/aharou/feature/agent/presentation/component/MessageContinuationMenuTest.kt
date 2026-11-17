package com.aharou.feature.agent.presentation.component

import com.aharou.feature.agent.presentation.*
import org.junit.Assert.*
import org.junit.Test

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = com.aharou.feature.agent.presentation.ProjectTaskConversationTestApplication::class, sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class MessageContinuationMenuTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun actualMenuClickDismissesAndInvokesContinuationForEmptyAssistant() {
        var dismissed = false
        var calls = 0
        compose.setContent { MaterialTheme {
            MessageActionsBottomSheet(AgentUIMessage("a", MessageRole.ASSISTANT, ""),
                onDismiss = { dismissed = true }, onEditClick = {}, onCopyClick = {}, onDeleteClick = {},
                onContinueClick = { calls++ })
        } }
        compose.onNodeWithText("继续执行").performClick()
        compose.runOnIdle { assertTrue(dismissed); assertEquals(1, calls) }
    }

    @Test fun emptyLatestMessageHasRealMoreButtonAndContinuationEntry() {
        var calls = 0
        val message = AgentUIMessage("empty", MessageRole.ASSISTANT, "")
        val items = buildChatItems(listOf(AgentUIMessage("user", MessageRole.USER, "task"), message), emptyMap())
        assertTrue(items.any { it.message.id == message.id && it.turnHeader == null })
        assertTrue(message.rendersActionRow())
        compose.setContent { MaterialTheme {
            var showMenu by remember { mutableStateOf(false) }
            AgentMessageItem(message, showActions = true, onMoreClick = { showMenu = true })
            if (showMenu) MessageActionsBottomSheet(message, onDismiss = { showMenu = false },
                onEditClick = {}, onCopyClick = {}, onDeleteClick = {}, onContinueClick = { calls++ })
        } }
        compose.onNodeWithContentDescription("更多选项").performClick()
        compose.onNodeWithText("继续执行").performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }

    @Test fun actualMenuHidesContinuationWithoutAnEligibleCallback() {
        compose.setContent { MaterialTheme {
            MessageActionsBottomSheet(AgentUIMessage("a", MessageRole.USER, ""), {}, {}, {}, {})
        } }
        compose.onNodeWithText("继续执行").assertDoesNotExist()
    }

    @Test fun emptyAssistantAndUserCanResumeButRunningOrOldSelectionCannot() {
        val last = AgentUIMessage("a", MessageRole.ASSISTANT, "")
        val state = ChatMessagesState("s", listOf(last), true)
        assertTrue(showContinuationAction(state, "s", last.id, false))
        assertFalse(showContinuationAction(state, "s", last.id, true))
        assertFalse(showContinuationAction(state, "s", "old", false))
        assertFalse(showContinuationAction(state, "other", last.id, false))
        assertFalse(showContinuationAction(state.copy(loaded = false), "s", last.id, false))
        assertTrue(showContinuationAction(state.copy(messages = listOf(last.copy(role = MessageRole.USER))), "s", "a", false))
    }
}
