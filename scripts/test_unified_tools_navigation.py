"""Static source contracts, not click tests, compilation, or runtime verification.

AHAROU_TEST_ROOT selects the implementation tree; no Android dependencies are needed.
"""
import os
import re
import unittest
from pathlib import Path

ROOT = Path(os.environ.get("AHAROU_TEST_ROOT", Path(__file__).resolve().parents[1])).resolve()
COMPONENTS = ROOT / "app/src/main/java/com/aharou/feature/settings/presentation/component"
TOKENS = re.compile(r'""".*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*.*?\*/', re.S)


def source(name: str) -> str:
    path = COMPONENTS / f"{name}.kt"
    return path.read_text(encoding="utf-8") if path.is_file() else ""


def masked(text: str) -> str:
    return TOKENS.sub(lambda match: re.sub(r"[^\n]", " ", match.group()), text)


def enclosed(text: str, marker: str, opening: str = "{", closing: str = "}") -> str:
    """Select a Kotlin block or argument list without counting literal braces."""
    if marker not in text:
        raise AssertionError(f"Missing source consumer: {marker}")
    mask = masked(text)
    start = mask.index(opening, text.index(marker) + len(marker))
    depth = 0
    for index in range(start, len(mask)):
        if mask[index] == opening:
            depth += 1
        elif mask[index] == closing:
            depth -= 1
            if depth == 0:
                return text[start + 1:index]
    raise AssertionError(f"Unclosed source consumer: {marker}")


def click_arguments(text: str) -> list[str]:
    code = masked(text)
    return [
        enclosed(code[match.start():], match.group().rstrip("("), "(", ")")
        for match in re.finditer(r"\b(?:Button|TextButton)\s*\(", code)
    ]

def has_click(text: str, callback: str) -> bool:
    return any(
        re.search(rf"\bonClick\s*=\s*{re.escape(callback)}\b", args)
        for args in click_arguments(text)
    )

class UnifiedToolsNavigationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.management = source("ToolManagementSection")
        cls.unified = source("UnifiedToolsSection")
        cls.creation = source("ToolCreationSheet")
        cls.toolbox = source("ToolboxSection")
        cls.tools = source("ToolsSection")
        cls.scripts = source("ScriptToolsSection")
        cls.tasks = source("ScheduledTasksSection")

    def creation_branch(self, name: str) -> str:
        args = enclosed(self.management, "ToolCreationSheet", "(", ")")
        return enclosed(args, f"{name} =")

    def test_old_six_tabs_are_replaced_by_unified_browser(self):
        self.assertNotIn("SegmentedTabs(", self.management)
        self.assertNotIn("when (selected)", self.management)
        self.assertIn("UnifiedToolsSection(", self.management)
        self.assertIn("verticalScroll(rememberScrollState())", self.unified)

    def test_creation_menu_has_six_clickable_real_callbacks(self):
        self.assertIn("AdaptiveModalBottomSheet(", self.creation)
        body = enclosed(self.creation, "fun ToolCreationSheet(")
        for callback in ("onCreateCommand", "onCreateScript", "onCreateMcp", "onCreateTask", "onImportTool", "onImportMcp"):
            with self.subTest(callback=callback):
                self.assertRegex(body, rf"onClick\s*=\s*{callback}\b")
                self.creation_branch(callback)
        request = enclosed(self.management, "LaunchedEffect(createRequested)")
        self.assertIn("if (createRequested)", request)
        self.assertEqual(1, request.count("onCreateHandled()"))
        self.assertIn("showCreate = true", request)
        self.assertIn("if (showCreate)", self.management)
        self.assertIn("onDismiss = { showCreate = false }", self.management)

    def test_command_and_mcp_creation_reach_existing_editors(self):
        self.assertIn(".startCreate()", self.creation_branch("onCreateCommand"))
        self.assertIn("onCreateMcp()", self.creation_branch("onCreateMcp"))
        self.assertIn("ToolEditorSheet(", self.tools)
        self.assertIn("state.editor", self.tools)
        screen = source("SettingsScreen")
        self.assertIn("McpServerDialogHost(", screen)
        self.assertIn("onCreateMcp", screen)

    def test_script_task_and_import_branches_reach_consumers(self):
        consumers = {
            "onCreateScript": (self.unified, "ScriptToolsSection"),
            "onCreateTask": (self.unified + self.management, "ScheduledTasksSection"),
        }
        for callback, (consumer, component) in consumers.items():
            with self.subTest(callback=callback):
                branch = self.creation_branch(callback)
                args = enclosed(consumer, component, "(", ")")
                request = re.search(r"(?:createRequested|importRequested)\s*=\s*(\w+)\b", args)
                self.assertIsNotNone(request, f"{component} must consume a creation/import request")
                self.assertRegex(branch, rf"\b{request.group(1)}\s*=\s*true\b")
                if callback != "onImportTool":
                    parent = enclosed(self.management, "UnifiedToolsSection", "(", ")")
                    self.assertRegex(parent, rf"\b{request.group(1)}\s*=\s*{request.group(1)}\b")
                self.assertIn("onCreateHandled" if callback != "onImportTool" else "onImportHandled", args)
        import_args = enclosed(self.management, "ToolImportSheet", "(", ")")
        self.assertIn("visible = importTool", import_args)
        self.assertIn("importTool = true", self.creation_branch("onImportTool"))
        self.assertIn("onDismiss = { importTool = false }", import_args)
        self.assertNotIn("var importing", self.toolbox)
        self.assertIn("onImportJson()", self.creation_branch("onImportMcp"))
        self.assertIn("McpJsonImportSheet(", source("SettingsScreen"))
        self.assertIn("viewModel::previewImport", self.toolbox)
        self.assertIn("viewModel::saveImport", self.toolbox)

    def test_commands_and_scripts_share_default_browsing_content(self):
        body = enclosed(self.unified, "fun UnifiedToolsSection(")
        custom = enclosed(body, "ToolsSection", "(", ")")
        script = enclosed(body, "ScriptToolsSection", "(", ")")
        self.assertIn("ToolSource.CUSTOM", custom)
        self.assertIn("embedded = true", custom)
        self.assertIn("embedded = true", script)
        self.assertNotRegex(body[:body.index("ScriptToolsSection(")], r"when\s*\(\s*selected\s*\)")
        self.assertIn("if (!embedded)", self.scripts)

    def test_templates_are_only_available_when_creating_commands(self):
        self.assertNotIn("PracticalToolTemplates", self.toolbox)
        self.assertNotIn("PracticalToolTemplates", self.unified)
        self.assertNotIn("var chooser", self.toolbox)
        self.assertNotIn("state.saved.forEach", self.toolbox)
        editor = source("ToolEditorSheet")
        self.assertIn("PracticalToolTemplates", editor)
        self.assertIn("isNew", editor)
        template_gate = enclosed(editor, "if (isNew)")
        self.assertIn("PracticalToolTemplates.all.forEach", template_gate)
        self.assertIn("onSelectTemplate(preset.name)", template_gate)
        self.assertIn("startTemplate", self.tools)

    def test_script_dynamic_tools_use_real_names_and_are_filtered(self):
        self.assertIn(".definitions.collectAsState", self.unified)
        self.assertRegex(self.unified, r"\.map\s*\{\s*it\.toolName\s*\}\.toSet\(\)")
        self.assertIn("excludedNames =", self.unified)
        self.assertRegex(self.tools, r"\w+\.name\s*!in\s*excludedNames")
        self.assertNotRegex(self.unified + self.tools, r'startsWith\(\s*"script_')
        self.assertIn("ToolSource.BUILTIN", self.unified)
        self.assertIn("ToolSource.MCP", self.unified)

    def test_command_run_reaches_selection_and_existing_output_actions(self):
        self.assertIn("onRun =", self.unified)
        self.assertIn(".select(", self.management)
        self.assertIn("ToolboxSection(", self.management)
        self.assertIn("onRun", self.tools)
        for callback in ("viewModel::run", "viewModel::cancel", "viewModel::exportSelected", "viewModel::previewImport", "viewModel::saveImport"):
            with self.subTest(callback=callback):
                self.assertTrue(has_click(self.toolbox, callback))
        self.assertTrue(any("onClick =" in args and "Intent.ACTION_SEND" in args
                            and "putExtra(Intent.EXTRA_TEXT, state.exportText)" in args
                            for args in click_arguments(self.toolbox)))
        run = enclosed(self.management, "onRun =")
        self.assertIn("toolboxViewModel.select(CustomToolDefinition(", masked(run))
        self.assertIn("EditorSettingsScreen(", masked(self.management))
        self.assertIn("onEditorSettings =", masked(self.management))
        self.assertNotIn("EditorSettingsScreen(", masked(self.toolbox))
        for capability in ("state.preview", "state.output", "state.exitCode", "state.truncated", "Intent.ACTION_SEND"):
            with self.subTest(capability=capability):
                self.assertIn(capability, masked(self.toolbox))

    def test_comment_cannot_replace_cancel_consumer(self):
        for callback in ("viewModel::run", "viewModel::cancel", "viewModel::exportSelected",
                         "viewModel::previewImport", "viewModel::saveImport"):
            with self.subTest(callback=callback):
                broken = self.toolbox.replace(callback, "viewModel::refresh")
                broken += f"\n// {callback}\n"
                self.assertFalse(has_click(broken, callback))

    def test_create_only_conflict_and_edit_upsert_have_distinct_paths(self):
        vm = (COMPONENTS.parent / "ToolsViewModel.kt").read_text()
        body = masked(enclosed(vm, "fun saveCustomTool("))
        create = enclosed(body, "if (creating)")
        self.assertIn("store.createCustomTool(definition)", create)
        self.assertNotIn("store.saveCustomTool", create)
        self.assertIn("store.saveCustomTool(definition)", body)
        self.assertIn("ToolsMessage.NAME_CONFLICT", body)
        self.assertIn("editor?.name == null", body)
        self.assertIn("saveError = state.message", self.tools)

    def test_history_blocks_stale_run_export_without_clearing_output(self):
        vm = (COMPONENTS.parent / "ToolboxViewModel.kt").read_text()
        invalidation = masked(enclosed(vm, "fun invalidateSelection("))
        self.assertIn("selectionReady = false", invalidation)
        self.assertNotIn("output =", invalidation)
        self.assertNotIn("invalidate()", invalidation)
        for marker in ("fun run(", "fun exportSelected("):
            self.assertIn("selectionReady", masked(enclosed(vm, marker)))
        self.assertIn("LaunchedEffect(customState.entries)", self.management)
        history = enclosed(self.management, "onOpenRunner =")
        self.assertIn("invalidateSelection()", history)
        self.assertIn("state.selectionReady", masked(self.toolbox))

    def test_draft_preview_and_run_does_not_require_registration(self):
        editor = source("ToolEditorSheet")
        draft = enclosed(editor, "if (isNew && onRunDraft != null)")
        self.assertIn("onRunDraft(CustomToolDefinition(", draft)
        self.assertNotIn("saveCustomTool", draft)
        self.assertIn("onRunDraft = onRunDraft", self.unified)
        self.assertIn("onRunDraft =", self.tools)
        self.assertIn("toolboxViewModel.select(draft)", self.management)

    def test_draft_return_to_editor_keeps_create_only_identity(self):
        draft = masked(self.creation_branch("onCreateCommand"))
        self.assertIn("customViewModel.startCreate()", draft)
        run = masked(enclosed(self.management, "onRunDraft ="))
        self.assertIn("selectedDraft = true", run)
        saved = masked(enclosed(self.management, "onRun ="))
        self.assertIn("selectedDraft = false", saved)
        edit = masked(enclosed(self.management, "onOpenEditor ="))
        self.assertIn("if (selectedDraft) customViewModel.startDraft(it)", edit)
        vm = (COMPONENTS.parent / "ToolsViewModel.kt").read_text()
        body = masked(enclosed(vm, "fun startDraft("))
        self.assertIn("ToolEditorTarget(template = definition)", body)
        self.assertNotIn("name =", body)

    def test_modal_errors_and_busy_dismissal_are_guarded(self):
        modal = enclosed(self.scripts, "Column(Modifier.imePadding()")
        self.assertIn("if (error)", modal)
        self.assertIn("sheetGesturesEnabled = !busy", self.scripts)
        self.assertIn("confirmValueChange = { !busyNow }", self.scripts)
        editor = source("ToolEditorSheet")
        self.assertIn("sheetGesturesEnabled = !saving", editor)
        self.assertIn("confirmValueChange = { !savingNow }", editor)
        self.assertIn("if (!saving) onDismiss()", editor)

    def test_permission_and_mcp_connection_management_are_reachable(self):
        self.assertIn("permissionContent = permissionContent", self.management)
        self.assertIn("permissionContent()", self.unified)
        self.assertIn("R.string.settings_permissions", self.unified)
        self.assertIn("AdaptiveModalBottomSheet(", self.management)
        args = enclosed(self.management, "McpSection", "(", ")")
        for capability in ("onReload", "onToggle", "onEdit", "onDelete", "onBrowseDirectory", "onImportJson", "onExportJson"):
            with self.subTest(capability=capability):
                self.assertRegex(args, rf"\b{capability}\s*=")
                if re.search(rf"\b{capability}\s*=\s*{capability}\b", args) is None:
                    callback = enclosed(args, f"{capability} =")
                    self.assertRegex(callback, rf"\b{capability}\s*\(")
        self.assertIn("LazyColumn(", source("McpSettingsSection"))
        for consumer in ("viewModel.openEditor(entry.name)", "viewModel.deleteCustomTool(entry.name)", "viewModel.saveCustomTool(", "viewModel.saveToolSettings("):
            self.assertIn(consumer, self.tools)

    def test_management_keeps_named_parameter_contract_with_settings_route(self):
        declaration = enclosed(self.management, "fun ToolManagementSection", "(", ")")
        route = enclosed(source("SettingsScreen"), "ToolManagementSection", "(", ")")
        for parameter in ("entries", "statuses", "reloading", "onReload", "onBrowseDirectory", "onImportJson", "onExportJson", "createRequested", "onCreateHandled", "onCreateMcp", "onToggle", "onDelete", "onEdit", "permissionContent"):
            with self.subTest(parameter=parameter):
                self.assertRegex(declaration, rf"\b{parameter}\s*:")
                self.assertRegex(route, rf"\b{parameter}\s*=")

    def test_automation_external_request_is_consumed_once_and_keeps_editor(self):
        effect = enclosed(self.tasks, "LaunchedEffect(createRequested)")
        gate = enclosed(effect, "if (createRequested)")
        self.assertIn("editing = null", gate)
        self.assertIn("showEditor = true", gate)
        self.assertEqual(1, gate.count("onCreateHandled()"))
        self.assertLess(gate.index("showEditor = true"), gate.index("onCreateHandled()"))
        self.assertIn("ScheduledTaskEditorSheet(", self.tasks)
        self.assertIn("embedded: Boolean", self.tasks)
        self.assertLessEqual(len(self.tasks.splitlines()), 998)


if __name__ == "__main__":
    unittest.main()
