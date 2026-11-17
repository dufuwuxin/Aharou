package com.aharou.feature.agent.presentation.component

import com.aharou.feature.agent.presentation.*
import org.junit.Assert.*
import org.junit.Test

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aharou.feature.agent.domain.tool.ExecutionHistoryEvent
import com.aharou.feature.agent.domain.tool.ExecutionHistoryTree

@RunWith(RobolectricTestRunner::class)
@Config(application = com.aharou.feature.agent.presentation.ProjectTaskConversationTestApplication::class, sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ExecutionHistoryTreeSectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun fileGroupStartsCollapsedAndReceiptJumpUsesTheOriginalMessageId() {
        var jumped: String? = null
        val event = ExecutionHistoryEvent("key", 1, "editFile", "call", "assistant", "receipt", "user",
            "src/A.kt", "file", "error")
        compose.setContent { MaterialTheme {
            ExecutionHistoryTreeSection("s", SessionExecutionHistory("s", ExecutionHistoryTree(listOf(event)), true, true),
                true, { true }, { jumped = it; true }, Modifier.height(600.dp))
        } }
        compose.onNodeWithText("工具回执 / 查看原始修改：receipt").assertDoesNotExist()
        compose.onNodeWithText("src/A.kt", substring = true).performClick()
        compose.onNodeWithText("工具回执 / 查看原始修改：receipt").performClick()
        compose.runOnIdle { assertEquals("receipt", jumped) }
    }

    @Test fun projectBodyAndCollapsedToolsOpenTheExactStoredSourceWithoutTimelineVisibility() {
        val rows = listOf(
            com.aharou.feature.agent.data.local.entity.AgentMessageEntity("u", "s", "USER", "requirement", 1),
            com.aharou.feature.agent.data.local.entity.AgentMessageEntity("a", "s", "ASSISTANT", "", 2,
                toolCallsJson = "[{\"id\":\"c\",\"name\":\"editFile\",\"arguments\":{\"path\":\"source.kt\"}}]"),
            com.aharou.feature.agent.data.local.entity.AgentMessageEntity("receipt", "s", "TOOL", "original edit receipt", 3,
                toolCallId = "c", toolName = "editFile"))
        val history = projectSessionExecutionHistory("s", rows, false)
        val selectedSession = androidx.compose.runtime.mutableStateOf("s")
        val loaded = androidx.compose.runtime.mutableStateOf(true)
        compose.setContent { MaterialTheme {
            ExecutionHistoryTreeSection(selectedSession.value, history.copy(loaded = loaded.value), false,
                { false }, { error("Hidden timeline must not be used") }, Modifier.height(600.dp))
        } }
        compose.onNodeWithText("source.kt", substring = true).performClick()
        compose.onNodeWithText("工具回执 / 查看原始修改：receipt").performClick()
        compose.onNodeWithText("对应对话与工具原文").assertExists()
        compose.onNodeWithText("original edit receipt").assertExists()
        compose.runOnIdle { loaded.value = false }
        compose.onNodeWithText("original edit receipt").assertDoesNotExist()
        compose.runOnIdle { selectedSession.value = "other"; loaded.value = true }
        compose.onNodeWithText("original edit receipt").assertDoesNotExist()
    }

    @Test fun missingAnchorShowsAnExplicitErrorRatherThanSuccess() {
        val event = ExecutionHistoryEvent("key", 1, "readFile", "call", null, null, null,
            "missing.kt", "file", "pending")
        compose.setContent { MaterialTheme {
            ExecutionHistoryTreeSection("s", SessionExecutionHistory("s", ExecutionHistoryTree(listOf(event)), true),
                false, { false }, { error("Must not jump") }, Modifier.height(600.dp))
        } }
        compose.onNodeWithText("missing.kt", substring = true).performClick()
        compose.onNodeWithText("用户来源：—").performClick()
        compose.onNodeWithText("无法跳转：来源不在当前会话的可见消息中，请回到对应会话或加载更早历史。").assertExists()
    }

    @Test fun jumpRequiresLoadedMatchingScopeVisibleBodyAndActualAnchor() {
        assertTrue(executionTreeJumpAllowed("s", "s", true, true, "a") { it == "a" })
        assertFalse(executionTreeJumpAllowed("s", "other", true, true, "a") { true })
        assertFalse(executionTreeJumpAllowed("s", "s", false, true, "a") { true })
        assertFalse(executionTreeJumpAllowed("s", "s", true, false, "a") { true })
        assertFalse(executionTreeJumpAllowed("s", "s", true, true, null) { true })
        assertFalse(executionTreeJumpAllowed("s", "s", true, true, "a") { false })
    }
}
