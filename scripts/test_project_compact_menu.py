"""Source contracts for project chrome; these do not replace device interaction tests."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
COMPONENT = ROOT / "app/src/main/java/com/aharou/feature/agent/presentation/component"


def source(name):
    path = COMPONENT / name
    return path.read_text() if path.exists() else ""


class CompactProjectMenuTest(unittest.TestCase):
    def test_project_does_not_stack_the_chat_header(self):
        panel = source("AIChatPanel.kt")
        self.assertIn("if (bodyOverride == null) ChatHeader(", panel)
        self.assertNotIn("ChatSessionModelControls(", panel)
        self.assertIn("ProjectChatMenuHost(", panel)
        self.assertIn("bodyOverride(composer::collapse)", panel)

    def test_project_has_one_header_and_overflow_only(self):
        ui = source("ProjectTaskWorkbench.kt")
        self.assertEqual(1, ui.count('testTag("project_compact_header")'))
        header = ui[ui.index('testTag("project_compact_header")'):ui.index("if (planning) Column")]
        self.assertEqual(2, header.count("IconButton("))
        for contract in ("mapMenu { menuOpen = false }", "sessionMenu { menuOpen = false }",
                         'testTag("project_flow_start")', 'testTag("project_flow_stop")',
                         "enabled = !state.loading && !state.busy && !planning"):
            self.assertIn(contract, header)

    def test_mode_switch_only_offers_the_other_view(self):
        ui = source("ProjectTaskWorkbench.kt")
        self.assertIn('if (viewMode == "map") R.string.project_task_tree else R.string.project_task_map', ui)
        self.assertIn('viewMode = if (viewMode == "map") "tree" else "map"', ui)
        self.assertNotIn('listOf("map" to', ui)
        self.assertIn('testTag("project_mode_toggle")', ui)

    def test_fit_and_pinch_remain_without_obsolete_controls(self):
        ui = source("ProjectTaskMap.kt")
        for tag in ("map_expand_all",):
            self.assertIn('testTag("' + tag + '")', ui)
        for removed in ("map_zoom_in", "map_zoom_out", "map_reset", "map_fit", "map_legend", "showLegend", "zoomAtCenter", "ZOOM_STEP"):
            self.assertNotIn(removed, ui)
        self.assertIn("detectProjectMapGestures", ui)
        self.assertIn("zoom * scale", ui)
        self.assertNotIn("Modifier.align(Alignment.TopEnd)", ui)

    def test_search_is_opt_in_and_close_clears_hidden_filter(self):
        ui = source("ProjectTaskMap.kt")
        for contract in ("if (showSearch) Row", 'showSearch = false; query = ""',
                         "if (searching) Text(", "rememberSaveable(projectId)"):
            self.assertIn(contract, ui)

    def test_project_menu_owns_sheets_and_useful_navigation(self):
        ui = source("ProjectChatMenu.kt")
        self.assertIn("CompositionLocalProvider(LocalProjectChatMenu provides menu, LocalProjectDiagnostics provides", ui)
        provider = ui.index("CompositionLocalProvider(LocalProjectChatMenu provides menu")
        for sheet in ("stewardOpen",):
            self.assertGreater(ui.index("if (" + sheet + " && sessionId != null)"), provider)
        for entry in ("steward_entry_title", "chat_open_terminal", "chat_open_git",
                      "chat_open_browser", "chat_open_sandbox", "chat_open_shared", "session_diagnostics_title"):
            self.assertIn("R.string." + entry, ui)
        self.assertNotIn("onOpenDrawer", ui)
        self.assertIn("onClick = { dismiss(); action() }", ui)

    def test_sidebar_shell_and_exclusive_component_are_deleted(self):
        main = (ROOT / "app/src/main/java/com/aharou/MainActivity.kt").read_text()
        for removed in ("ModalNavigationDrawer", "drawerState", "drawerBody", "sidebarWidth", "ChatDrawerContent"):
            self.assertNotIn(removed, main)
        self.assertFalse((COMPONENT / "ChatDrawer.kt").exists())
        for filename in ("FileBrowseController.kt", "FileBrowseTree.kt", "ContainerTreeRootProvider.kt", "FileSearchViewModel.kt"):
            self.assertFalse((COMPONENT.parent / filename).exists())
        vm = (COMPONENT.parent / "AIAgentViewModel.kt").read_text()
        for removed in ("fileBrowse", "chatSearchState", "pendingScrollMessage"):
            self.assertNotIn(removed, vm)
        for filename in ("AIChatPanel.kt", "ChatHeaderComponents.kt", "ProjectChatScreen.kt"):
            self.assertNotIn("onOpenDrawer", source(filename))

    def test_ghost_feature_is_deleted_not_disabled(self):
        self.assertFalse((COMPONENT / "GhostModeIndicator.kt").exists())
        agent = ROOT / "app/src/main/java/com/aharou/feature/agent"
        self.assertFalse((agent / "domain/session/GhostModeStore.kt").exists())
        allowed_schema = {"ChatSessionEntity.kt", "ChatSessionDao.kt"}
        for path in agent.rglob("*.kt"):
            if path.name in allowed_schema:
                continue
            text = path.read_text()
            for removed in ("GhostModeStore", "ghostMode", "ghost_mode", "isGhostSession"):
                self.assertNotIn(removed, text, str(path.relative_to(ROOT)))
        resources = (ROOT / "app/src/main/res/values/strings.xml").read_text()
        for removed in ("ghost_mode_", 'name="project_map_zoom_in"', 'name="project_map_zoom_out"',
                        'name="project_map_reset"', 'name="project_map_legend"', 'name="project_task_dependency_legend"'):
            self.assertNotIn(removed, resources)

    def test_stage_routing_is_retired_not_hidden(self):
        resources = (ROOT / "app/src/main/res/values/strings.xml").read_text()
        self.assertNotIn('name="dual_model_', resources)
        agent = ROOT / "app/src/main/java/com/aharou/feature/agent"
        for path in (agent / "domain/workflow/DualModelRouter.kt", agent / "domain/session/DualModelSettings.kt",
                     agent / "presentation/component/DualModelButton.kt", agent / "data/ConfigDualModelFields.kt"):
            self.assertFalse(path.exists(), str(path))
        self.assertNotIn("dualModelRouter", (agent / "domain/workflow/StatefulAgentWorkflow.kt").read_text())

    def test_default_map_root_history_and_approvals_are_preserved(self):
        ui = source("ProjectTaskWorkbench.kt")
        for contract in ('mutableStateOf("map")', "header = header", "rootConversation = rootConversation",
                         "approvals()", "readOnly = true", "onAccept = { viewModel.accept(task) }"):
            self.assertIn(contract, ui)
        self.assertIn("rememberUpdatedState(onContentDrag)", source("ProjectTaskMap.kt"))

    def test_large_file_ratchet_is_not_raised(self):
        self.assertLessEqual(len(source("AIChatPanel.kt").splitlines()), 1855)


if __name__ == "__main__":
    unittest.main()
