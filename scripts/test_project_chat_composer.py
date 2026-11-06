#!/usr/bin/env python3
"""Source-contract regression only; this does not exercise Compose clicks or IME."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/aharou"
COMPONENT = JAVA / "feature/agent/presentation/component"


def source(name):
    path = COMPONENT / name
    return path.read_text() if path.exists() else ""


class ProjectChatComposerContract(unittest.TestCase):
    def test_same_chat_consumer_and_default_compatibility(self):
        panel = source("AIChatPanel.kt")
        self.assertIn("bodyOverride:", panel)
        self.assertIn("floatingComposer: Boolean = false", panel)
        self.assertEqual(1, panel.count("ChatInputBar("))
        for consumer in ("::requestUploadImage", "::requestUploadFile", "::requestTakePhoto",
                         "setSessionProviderModel", "setSessionMode", "PermissionOverlayContent(",
                         "AskUserQuestionPanel(", "PlanApprovalPanel("):
            self.assertIn(consumer, panel)

    def test_routes_reuse_header_and_root_binding(self):
        screen = source("ProjectChatScreen.kt")
        for contract in ("AIChatPanel(", "ensureProjectSession(", "messages.loaded",
                         "messages.sessionId == sessionId", "currentSession == sessionId",
                         "ProjectTaskConversation(", "flowSessionIds", "task.sessionIds"):
            self.assertIn(contract, screen)
        main = (JAVA / "MainActivity.kt").read_text()
        self.assertIn("ProjectChatScreen(", main)
        self.assertIn("rememberProjectChatBinding(", main)

    def test_collapsing_is_gesture_or_real_ime_dismissal(self):
        floating = source("FloatingProjectComposer.kt")
        for contract in ("WindowInsets.ime", "wasImeVisible && !imeVisible",
                         "focusRequester.requestFocus()", "keyboard?.hide()", "Offset.Zero",
                         "48.dp", "Radius.pill", "content(if (state.floating) focusRequester else null)"):
            self.assertIn(contract, floating)
        self.assertIn("rememberUpdatedState(onContentDrag)", source("ProjectTaskMap.kt"))
        self.assertIn("onDrag()", source("ProjectTaskMap.kt"))
        self.assertIn("nestedScroll(", source("ProjectTaskWorkbench.kt"))
        self.assertIn("composer.canSend", source("AIChatPanel.kt"))

    def test_session_binding_is_serial_and_checks_root_and_workspace(self):
        vm = (JAVA / "feature/agent/presentation/ProjectTaskViewModel.kt").read_text()
        for contract in ("Mutex()", "sessionMutex.withLock", "suspend fun ensureProjectSession(",
                         "sessions.getSessionById(", "parentId == null", "workspacePath == workspace",
                         "sessions.upsertSession(", "repository.linkProjectExecution("):
            self.assertIn(contract, vm)

    def test_tree_has_no_manual_add_or_supplement_path(self):
        workbench = source("ProjectTaskWorkbench.kt")
        for removed in ("ProjectTaskNodeMenu(", "ProjectTaskEditor(", "ProjectTaskAddSheet(",
                        "ProjectTaskSupplementSheet(", "R.string.project_add_content"):
            self.assertNotIn(removed, workbench)
        self.assertNotIn("ProjectTaskNodeMenu(", source("ProjectTaskMap.kt"))
        self.assertIn("readOnly = true", workbench)
        self.assertIn("if (!readOnly) TextButton(onClick = onEdit)", source("ProjectTaskProcess.kt"))

    def test_root_node_opens_bound_conversation_with_live_streams(self):
        screen = source("ProjectChatScreen.kt")
        workbench = source("ProjectTaskWorkbench.kt")
        task_map = source("ProjectTaskMap.kt")
        for contract in ("rootConversation =", "ProjectTaskConversation(root, agent, onToolDetail)"):
            self.assertIn(contract, screen)
        for contract in ("toggle(rootId)", "rootConversation()", "onOpenRoot = { toggle(rootId) }"):
            self.assertIn(contract, workbench)
        self.assertIn("rootStyle, onClick = onOpenRoot)", task_map)
        conversation = source("ProjectTaskConversation.kt")
        for live_consumer in ("processMessages(sessionId", "processText(sessionId)", "processReasoning(sessionId)", "processTools(sessionId)"):
            self.assertIn(live_consumer, conversation)
        self.assertNotIn("selectSession(", conversation)

    def test_read_only_records_retain_validated_acceptance(self):
        process = source("ProjectTaskProcess.kt")
        self.assertNotIn("if (!readOnly && task.status", process)
        self.assertIn("if (task.status == com.aharou.feature.agent.domain.task.ProjectTaskStatus.REVIEW) TextButton(onClick = onAccept,", process)
        for gate in ("task.acceptance.isNotBlank()", "task.evidenceRefs.isNotEmpty()", "ProjectWorkflowPlan.delivered(task)"):
            self.assertIn(gate, process)
        self.assertIn("if (!readOnly) TextButton(onClick = onEdit)", process)
        self.assertIn("onAccept = { viewModel.accept(task) }", source("ProjectTaskWorkbench.kt"))

    def test_force_insert_preserves_raw_user_text(self):
        panel = source("AIChatPanel.kt")
        self.assertIn("forceInsertAgentRequest(request = text,", panel)
        self.assertNotIn("forceInsertAgentRequest(request = text + projectChatContext", panel)
        self.assertIn("targetSessionId = composerSessionId", panel)

    def test_large_file_ratchet_not_raised(self):
        for relative, maximum in (("MainActivity.kt", 1407),
                                  ("feature/agent/presentation/component/AIChatPanel.kt", 1855),
                                  ("feature/agent/presentation/component/ChatInputBar.kt", 1335)):
            self.assertLessEqual(len((JAVA / relative).read_text().splitlines()), maximum)


if __name__ == "__main__":
    unittest.main()
