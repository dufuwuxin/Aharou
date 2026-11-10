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
    def assert_branch_binding(self, screen):
        self.assertIn("var requirementBranchId by rememberSaveable(id, workspace)", screen)
        selected = screen.split("val selectedBranch =", 1)[1].split("val chooseBranch:", 1)[0]
        for guard in ("it.id == requirementBranchId", "it.projectId == id", "it.workspace == workspace", "it.isRequirementBranch"):
            self.assertIn(guard, selected)
        effect = screen.split("LaunchedEffect(id, workspace, project != null, state.workspace, requirementBranchId)", 1)[1].split("val ready =", 1)[0]
        for contract in ("val requestedBranch = requirementBranchId",
                         "if (requestedBranch == null) projects.ensureProjectSession(id, workspace)",
                         "else projects.ensureBranchSession(requestedBranch, workspace)",
                         "currentCoroutineContext().ensureActive()",
                         "projects.state.value.workspace == workspace && requirementBranchId == requestedBranch",
                         "sessionId = target; agent.selectSession(target)"):
            self.assertIn(contract, effect)
        self.assertIn("requirementBranchId = requirementBranchId, onSelectRequirementBranch = chooseBranch", screen)
        self.assertIn("branchSessionId = sessionId.takeIf { requirementBranchId != null }", screen)

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
        self.assertIn("bodyOverride = binding.body, floatingComposer = true, composerSessionId = binding.sessionId", screen)
        self.assertIn("if (bodyOverride == null) ChatHeader(", source("AIChatPanel.kt"))
        self.assertIn("header = header, process = process)", source("ProjectTaskWorkbench.kt"))

    def test_status_questions_do_not_force_requirement_writes(self):
        screen = source("ProjectChatScreen.kt")
        self.assertIn("Only new or changed requirements", screen)
        self.assertIn("use read-only queries for them", screen)
        self.assertIn("Saving is not evidence of execution or acceptance", screen)
        self.assertIn("PLAN only saves", screen)
        self.assertNotIn("apply the appropriate action before reporting changes", screen)

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

    def test_tree_creates_empty_branch_without_inline_editing(self):
        workbench = source("ProjectTaskWorkbench.kt")
        for removed in ("ProjectTaskNodeMenu(", "ProjectTaskEditor(",
                        "ProjectTaskSupplementSheet(", "R.string.project_add_content"):
            self.assertNotIn(removed, workbench)
        self.assertIn("ProjectTaskAddSheet(", workbench)
        submit = workbench.split("onSubmit = { description ->", 1)[1].split("onDismiss =", 1)[0]
        self.assertIn("viewModel.createRequirementBranch(projectId, description, parentId = addingParentId)", submit)
        self.assertIn("onSelectRequirementBranch(branchId)", submit)
        self.assertNotIn("addTaskByDescription", submit)
        self.assertRegex(workbench, r'(?s)val header:.*?DropdownMenu\(expanded = menuOpen, onDismissRequest = \{ menuOpen = false \}\).*?DropdownMenuItem\(text = \{ Text\(stringResource\(R.string.project_add_task\)\) \}, enabled = !state.busy,\s*modifier = Modifier.testTag\("project_add_task"\),\s*onClick = \{ addingParentId = null; adding = true; menuOpen = false \}\)')
        self.assertIn("addingParentId = row.task.id", workbench)
        self.assertNotIn("ProjectTaskNodeMenu(", source("ProjectTaskMap.kt"))
        self.assertIn("readOnly = true", workbench)
        self.assertIn("if (!readOnly) TextButton(onClick = onEdit)", source("ProjectTaskProcess.kt"))

    def test_root_node_opens_bound_conversation_with_live_streams(self):
        screen = source("ProjectChatScreen.kt")
        workbench = source("ProjectTaskWorkbench.kt")
        task_map = source("ProjectTaskMap.kt")
        for contract in ("rootConversation =", "ProjectRootConversation(records, agent, onToolDetail)",
                         "it.id in project.flowSessionIds", "it.parentId == null", "it.workspacePath == workspace"):
            self.assertIn(contract, screen)
        for contract in ("toggle(rootId)", "rootConversation()", "onOpenRoot = { toggle(rootId) }"):
            self.assertIn(contract, workbench)
        self.assertIn("rootStyle, onClick = onOpenRoot)", task_map)
        conversation = source("ProjectTaskConversation.kt")
        self.assertIn("ProjectTaskConversation(selected, agentViewModel, onToolDetail)", conversation)
        self.assertIn("recordState.SaveableStateProvider(selected)", conversation)
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

    def test_branch_binding_rejects_scope_and_late_selection_mutants(self):
        screen = source("ProjectChatScreen.kt")
        self.assert_branch_binding(screen)
        for old, new in (("projects.ensureBranchSession(requestedBranch, workspace)", "projects.ensureProjectSession(id, workspace)"),
                         ("requirementBranchId == requestedBranch", "true"),
                         ("it.projectId == id", "true"),
                         ("it.workspace == workspace", "true")):
            mutant = screen.replace(old, new, 1)
            self.assertNotEqual(mutant, screen)
            with self.assertRaises(AssertionError):
                self.assert_branch_binding(mutant)
        workbench = source("ProjectTaskWorkbench.kt")
        toggle = workbench.split("val toggle:", 1)[1].split("val process:", 1)[0]
        self.assertIn("if (id == rootId) onSelectRequirementBranch(null)", toggle)
        self.assertIn("onSelectRequirementBranch(null); rootReader = true", workbench)
        self.assertIn("Ask and answer questions normally without planning unless the user asks to add requirements", screen)

    def test_branch_sessions_are_linked_only_to_the_same_branch(self):
        vm = (JAVA / "feature/agent/presentation/ProjectTaskViewModel.kt").read_text()
        branch = vm.split("suspend fun ensureBranchSession(", 1)[1].split("\n    fun createRequirementBranch(", 1)[0]
        for contract in ("sessionMutex.withLock", "it.id == branchId && it.workspace == workspace && it.isRequirementBranch",
                         "val key = workspace to branchId", "branch.sessionIds +", "pendingBranchSessions[key]",
                         "it.parentId == null && it.workspacePath == workspace", "sessions.upsertSession(it)",
                         "repository.transact(workspace)", "it.projectId == branch.projectId",
                         "latest.copy(sessionIds = latest.sessionIds + session.id)"):
            self.assertIn(contract, branch)
        self.assertNotIn("flowSessionIds", branch)
        self.assertNotIn("linkProjectExecution", branch)
        create = vm.split("fun createRequirementBranch(", 1)[1].split("\n    fun selectProject(", 1)[0]
        for guard in ("if (title.isBlank()) return", "repository.createRequirementBranch(projectId, title, workspace, parentId)",
                      "selectionEpoch == epoch", "mutable.value.workspace == requestedWorkspace",
                      "mutable.value.selectedProjectId == selectedProject"):
            self.assertIn(guard, create)
        graph = (JAVA / "feature/agent/domain/task/ProjectTaskGraph.kt").read_text()
        for guard in ("parent.projectId != task.projectId", "parent.workspace != task.workspace", "PARENT_NOT_FOUND"):
            self.assertIn(guard, graph)

    def test_branch_process_returns_before_task_acceptance_and_owner(self):
        process = source("ProjectTaskProcess.kt")
        branch = process.split("if (task.isRequirementBranch) {", 1)[1].split("val records = projectProcessRecords(task)", 1)[0]
        for contract in ("project_branch_discussion_hint", "it in task.sessionIds", "conversation(task, sessionId)"):
            self.assertIn(contract, branch)
        self.assertRegex(branch, r"return\s*}\s*$")
        for task_only in ("onClick = onAccept", "task.owner", "task.status", "task.acceptance", "task.evidenceRefs"):
            self.assertNotIn(task_only, branch)
        workbench = source("ProjectTaskWorkbench.kt")
        self.assertIn("if (!row.task.isRequirementBranch) Text(row.task.owner", workbench)
        vm = (JAVA / "feature/agent/presentation/ProjectTaskViewModel.kt").read_text()
        self.assertIn(".filterNot { it.isRequirementBranch }.groupingBy", vm)
        accept = vm.split("fun accept(", 1)[1].split("\n    }", 1)[0]
        self.assertIn("if (task.isRequirementBranch || task.status !=", accept)

    def test_force_insert_preserves_raw_user_text(self):
        panel = source("AIChatPanel.kt")
        self.assertIn("forceInsertAgentRequest(request = text,", panel)
        self.assertNotIn("forceInsertAgentRequest(request = text + projectChatContext", panel)
        self.assertIn("targetSessionId = composerSessionId", panel)

    def test_model_selection_callback_keeps_original_session(self):
        panel = source("AIChatPanel.kt")
        self.assertIn("val modelSelectionSessionId = currentSessionId", panel)
        self.assertNotIn("val modelSelectionSessionId by", panel)
        self.assertIn("viewModel.setSessionProviderModel(provider, model, expectedSessionId = modelSelectionSessionId)", panel)
        vm = (JAVA / "feature/agent/presentation/AIAgentViewModel.kt").read_text()
        method = vm.split("fun setSessionProviderModel(", 1)[1].split("\n    }", 1)[0]
        for contract in ("expectedSessionId: String? = _currentSessionId.value",
                         "if (expectedSessionId != _currentSessionId.value) return",
                         "val sid = expectedSessionId ?: return",
                         "sessionUseCase.updateProviderModel(sid, providerId, model)"):
            self.assertIn(contract, method)
        self.assertLess(method.index("if (expectedSessionId"), method.index("viewModelScope.launch"))

    def test_large_file_ratchet_not_raised(self):
        for relative, maximum in (("MainActivity.kt", 1407),
                                  ("feature/agent/presentation/component/AIChatPanel.kt", 1855),
                                  ("feature/agent/presentation/component/ChatInputBar.kt", 1335)):
            self.assertLessEqual(len((JAVA / relative).read_text().splitlines()), maximum)


if __name__ == "__main__":
    unittest.main()
