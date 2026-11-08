"""Source contracts only: these checks do not simulate clicks or install packages.

Set AHAROU_TEST_ROOT to check another implementation tree with this same suite.
"""
import os
import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(os.environ.get("AHAROU_TEST_ROOT", Path(__file__).resolve().parents[1])).resolve()
SRC = ROOT / "app/src/main/java/com/aharou"
SETTINGS = SRC / "feature/settings/presentation/component"
LSP = SRC / "feature/editor/lsp"
TOKENS = re.compile(r'""".*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*.*?\*/', re.S)


def source(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def masked(text: str) -> str:
    """Ignore braces in Kotlin literals/comments when selecting a source block."""
    return TOKENS.sub(lambda match: re.sub(r"[^\n]", " ", match.group()), text)


def block(text: str, marker: str, opening: str = "{", closing: str = "}") -> str:
    start = text.index(marker)
    mask = masked(text)
    start = mask.index(opening, start + len(marker))
    depth = 0
    for index in range(start, len(mask)):
        if mask[index] == opening:
            depth += 1
        elif mask[index] == closing:
            depth -= 1
            if depth == 0:
                return text[start + 1:index]
    raise AssertionError(f"Unclosed Kotlin source block: {marker}")


def function(text: str, name: str) -> str:
    return block(text, f"fun {name}(")


class SettingsNavigationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.screen = source(SETTINGS / "SettingsScreen.kt")
        cls.groups = source(SETTINGS / "SettingsGroupScreens.kt")
        cls.tools = source(SETTINGS / "ToolManagementSection.kt")
        cls.installer = source(LSP / "LanguageServerInstaller.kt")
        cls.catalog = source(LSP / "LspServerCatalog.kt")

    def test_role_management_has_its_own_entry_and_returns_to_config(self):
        config = function(self.groups, "SettingsAiGroupScreen")
        self.assertIn("onOpen(SettingsSection.SubAgents)", config)
        self.assertIn("SettingsSection.SubAgents -> (configHome ?: SettingsSection.AiGroup)", self.screen)
        for name in ("SubAgents", "SubAgentDetail"):
            actions = self.screen[self.screen.index("actions = {"):]
            self.assertIn(f"SettingsSection.{name} ->", actions)
        self.assertIn("viewModel.deleteSubAgent(target.name, target.scope)", self.screen)
        self.assertIn("editingSubAgent = entry", self.screen)

    def test_team_create_role_card_opens_empty_editor_and_returns_to_team(self):
        team = source(SETTINGS / "SubAgentTeamsSection.kt")
        start = team.rfind("SettingsRow(", 0, team.index("R.string.team_create_member"))
        row = block(team[start:], "SettingsRow", "(", ")")
        self.assertIn("onClick = onCreateRoleCard", row)
        self.assertNotIn("onOpenExperts", row)
        self.assertNotIn("expertCount", row)
        route = block(self.screen, "SettingsSection.Teams -> SubAgentTeamsSection", "(", ")")
        create = block(route, "onCreateRoleCard =")
        self.assertIn("editingSubAgent = null", create)
        self.assertIn("viewModel.clearSubAgentSaveState()", create)
        self.assertIn("subAgentEditorReturn = SettingsSection.Teams", create)
        self.assertIn("section = SettingsSection.SubAgentEditor", create)
        self.assertNotRegex(create, r"section\s*=\s*SettingsSection\.SubAgents\b")
        editor = block(self.screen, "current == SettingsSection.SubAgentEditor -> SubAgentEditorScreen", "(", ")")
        for callback in ("onSaved =", "onNavigateBack ="):
            self.assertIn("section = subAgentEditorReturn", block(editor, callback))

    def test_team_page_reloads_role_cards_after_returning_from_creation(self):
        team = source(SETTINGS / "SubAgentTeamsSection.kt")
        self.assertIn("viewModel.refresh()", block(team, "LaunchedEffect(Unit)"))
        model = source(SETTINGS.parent / "TeamSettingsViewModel.kt")
        self.assertIn("load()", function(model, "refresh"))
        reload = function(model, "load")
        self.assertIn("_members.value =", reload)
        self.assertIn("agentDefinitionRepository.listEnabled()", reload)
        self.assertIn("availableMembers = availableMembers", team)

    def test_role_management_does_not_show_old_subagent_terminology(self):
        resources = ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()
        values = {item.attrib["name"]: "".join(item.itertext()) for item in resources.findall("string")}
        for name in ("SubAgentsSection.kt", "SubAgentEditorScreen.kt", "SubAgentDetailSection.kt"):
            references = re.findall(r"R\.string\.(\w+)", source(SETTINGS / name))
            for reference in references:
                with self.subTest(file=name, resource=reference):
                    self.assertNotIn("子代理", values[reference])
        self.assertIn("communicationEnabled", source(SETTINGS / "SubAgentsSection.kt"))
        self.assertIn("setCommunicationEnabled", source(SETTINGS / "SubAgentsSection.kt"))

    def test_bottom_roots_hide_titles_and_back_but_keep_child_navigation(self):
        self.assertIn("current == SettingsSection.Menu || current == configHome", self.screen)
        self.assertIn("if (!isRoot && (!expanded || parentSection != null))", self.screen)
        self.assertIn("if (!isRoot) TopAppBar(", self.screen)
        self.assertNotIn("settings_category_", self.groups)
        sessions = source(SRC / "feature/agent/presentation/component/SessionListScreen.kt")
        projects = source(SRC / "feature/agent/presentation/component/ProjectListScreen.kt")
        self.assertNotIn("R.string.session_list_title", sessions)
        self.assertNotIn("Text(stringResource(R.string.project_home)", projects)
        self.assertIn("onNewSession", sessions)
        self.assertIn('testTag("create_project")', projects)

    def test_injection_details_are_conditional_and_switches_preserve_state(self):
        prompt = source(SETTINGS / "PromptEditorScreen.kt")
        fields = source(SETTINGS / "PromptInjectionFields.kt")
        self.assertIn("keywords = keywords.map", prompt)
        self.assertIn("if (keywordsEnabled)", fields)
        self.assertIn("if (membersEnabled)", fields)
        self.assertIn("keywords.forEachIndexed", fields)
        self.assertIn("singleLine = true", fields)
        self.assertIn("keywords.filterIndexed", fields)
        self.assertIn('onKeywordsChange(keywords + "")', fields)
        self.assertNotIn("keywordsRaw", prompt)
        for callback in ("onKeywordsEnabledChange", "onMembersEnabledChange"):
            self.assertIn(f"onCheckedChange = {callback}", fields)

    def test_task_continuation_preserves_structured_parent_at_any_depth(self):
        workbench = source(SRC / "feature/agent/presentation/component/ProjectTaskWorkbench.kt")
        detail = source(SRC / "feature/agent/presentation/component/ProjectTaskDetailScreen.kt")
        self.assertNotIn("onAddChild = {}", workbench)
        self.assertIn("onAddChild = { task ->", workbench)
        self.assertIn("viewModel.addTaskByDescription(projectId, description, parentId = addingParentId)", workbench)
        self.assertIn("viewModel.addTaskByDescription(projectId, description, parentId = task.id)", detail)
        self.assertIn('testTag("task_add_child_${row.task.id}")', workbench)

    def test_map_fit_and_zoom_are_not_limited_by_touch_target_size(self):
        layout = source(SRC / "feature/agent/domain/task/ProjectTaskMapLayout.kt")
        task_map = source(SRC / "feature/agent/presentation/component/ProjectTaskMap.kt")
        self.assertNotIn("48f / NODE_HEIGHT", layout)
        self.assertIn("fun fitZoom(", layout)
        self.assertIn("fun minimumZoom(", layout)
        self.assertIn("fun clampZoom(", layout)
        self.assertIn("target.isFinite()", layout)
        self.assertIn('ProjectMapIcon(FeatherIcons.Maximize, stringResource(R.string.project_map_fit), "map_fit")', task_map)
        self.assertIn('IconButton(onClick = onClick, modifier = Modifier.size(48.dp).testTag(tag))', task_map)
        self.assertIn("ProjectTaskMapLayout.fitZoom(", task_map)
        self.assertIn("ProjectTaskMapLayout.clampZoom(", task_map)
        self.assertIn("(r - RING_DP * density / 2f).coerceAtLeast(0f)", task_map)

    def test_personal_center_is_removed_without_removing_shared_identity(self):
        account = function(self.groups, "SettingsAharouRows")
        self.assertNotIn("AccountCenter", self.screen + self.groups)
        self.assertFalse((SRC / "feature/account/presentation/AccountScreen.kt").exists())
        self.assertFalse((SRC / "feature/account/presentation/UserIdentityCard.kt").exists())
        for name in ("Soul", "ConfigAudit"):
            with self.subTest(section=name):
                self.assertIn(f"onOpen(SettingsSection.{name})", account)
                self.assertRegex(self.screen, rf"SettingsSection\.{name}\s*->")
        self.assertIn("UserIdentityStore.observe(context)", source(SRC / "feature/agent/presentation/component/SoulChatHeader.kt"))
        self.assertIn("UserIdentityStore.setName", source(SRC / "core/config/ConfigBuiltins.kt"))
        self.assertTrue((SRC / "core/identity/UserIdentityStore.kt").is_file())

    def test_all_four_im_pages_have_clickable_entries(self):
        config = function(self.groups, "SettingsAiGroupScreen")
        for name in ("TelegramBot", "WeixinBot", "FeishuBot", "QqBot"):
            rows = config
            with self.subTest(section=name):
                self.assertRegex(rows, rf"onClick\s*=\s*\{{\s*onOpen\(SettingsSection\.{name}\)")
                self.assertRegex(self.screen, rf"SettingsSection\.{name}\s*->")

    def test_ai_configuration_lives_in_config_not_general_or_more(self):
        config = function(self.groups, "SettingsAiGroupScreen")
        general = function(self.groups, "SettingsGeneralRows")
        more = function(self.groups, "SettingsMoreRows")
        for name in ("Providers", "DefaultModels", "Plugins", "Connectors", "Vault"):
            with self.subTest(section=name):
                self.assertIn(f"onOpen(SettingsSection.{name})", config)
                self.assertNotIn(f"onOpen(SettingsSection.{name})", general)
                self.assertNotIn(f"onOpen(SettingsSection.{name})", more)

    def test_existing_config_capabilities_remain_reachable(self):
        config = function(self.groups, "SettingsAiGroupScreen")
        for name in ("Memory", "Skills", "Teams", "Tools", "Prompts", "Rules"):
            with self.subTest(section=name):
                self.assertIn(f"onOpen(SettingsSection.{name})", config)
        self.assertIn("OnboardingStep.CONFIG_PROVIDER", config)

    def test_automation_has_one_entry_in_tools_and_keeps_existing_page(self):
        self.assertNotIn("SettingsSection.ScheduledTasks", self.groups + self.screen)
        enum = block(self.screen, "enum class SettingsSection")
        self.assertNotRegex(enum, r"\bScheduledTasks\(")
        self.assertIn("onOpen(SettingsSection.Tools)", function(self.groups, "SettingsAiGroupScreen"))
        self.assertEqual(1, self.tools.count("ScheduledTasksSection()"))
        self.assertIn("R.string.settings_scheduled_tasks", self.tools)

    def test_duplicate_mcp_page_route_is_removed_not_mcp_features(self):
        self.assertNotRegex(self.screen, r"SettingsSection\.Mcp\b")
        enum = block(self.screen, "enum class SettingsSection")
        self.assertNotRegex(enum, r"\bMcp\(")
        self.assertIn("McpSection(", self.tools)
        combined = self.screen + self.tools
        for capability in ("onReload", "onToggle", "onEdit", "onDelete", "onBrowseDirectory", "onImportJson", "onExportJson"):
            with self.subTest(capability=capability):
                self.assertIn(capability, self.tools)
        for capability in ("McpJsonImportSheet(", "McpJsonExportSheet(", "McpServerDialogHost("):
            self.assertIn(capability, combined)
        for capability in ("ScriptToolsSection(", "ToolsSection(", "permissionContent()"):
            self.assertIn(capability, self.tools)

    def connector_origin(self):
        parents = block(self.screen, "val parentSection:")
        origin = re.search(r"SettingsSection\.Connectors\s*->\s*(\w+)\s*(?:\n|$)", parents)
        self.assertIsNotNone(origin, "Connectors must return through captured origin, not a fixed page")
        return origin.group(1)

    def test_connectors_parent_returns_to_captured_origin(self):
        origin = self.connector_origin()
        self.assertRegex(self.screen, rf"var {origin}\s+by remember\s*\{{\s*mutableStateOf\(SettingsSection\.AiGroup\)")
        opening = block(self.screen, "val openSection:")
        self.assertRegex(opening, rf"if\s*\(target == SettingsSection\.Connectors\)\s*\{{?\s*{origin}\s*=\s*section\b")
        self.assertLess(opening.index(f"{origin} ="), opening.index("section = target"))
        self.assertIn("SettingsSection.AiGroup -> SettingsAiGroupScreen(onOpen = openSection)", self.screen)
        tools = block(self.screen, "SettingsSection.Tools -> ToolManagementSection", "(", ")")
        browse = block(tools, "onBrowseDirectory =")
        self.assertIn("openSection(SettingsSection.Connectors)", browse)
        self.assertNotIn("section = SettingsSection.Connectors", browse)
        self.assertIn("parentSection?.let { section = it }", block(self.screen, "BackHandler(enabled = parentSection != null)"))
        self.assertIn("if (parent != null) section = parent else onNavigateBack()", self.screen)

    def test_mcp_dialog_close_from_connectors_returns_to_captured_origin(self):
        origin = self.connector_origin()
        dialog = block(self.screen, "McpServerDialogHost", "(", ")")
        dismiss = block(dialog, "onDismiss =")
        self.assertIn("mcpDialogRequest = null", dismiss)
        self.assertRegex(dismiss, rf"if\s*\(section == SettingsSection\.Connectors\)\s*section = {origin}\b")

    def test_mcp_logs_return_to_origin_including_direct_config_entry(self):
        origin = self.connector_origin()
        dialog = block(self.screen, "McpServerDialogHost", "(", ")")
        logs = block(dialog, "onOpenLogs =")
        self.assertRegex(logs, rf"logReturnSection\s*=\s*if\s*\(section == SettingsSection\.Connectors\)\s*{origin}\s*else section")
        self.assertIn("mcpDialogRequest = null", logs)
        self.assertIn("viewModel.refreshLogs(filterServerName = name)", logs)
        self.assertIn("section = SettingsSection.Log", logs)
        self.assertIn("SettingsSection.Log -> logReturnSection", self.screen)

    def test_config_provider_uses_real_route_mount_and_visible_target(self):
        steps = source(SRC / "feature/onboarding/domain/OnboardingStep.kt")
        provider = block(steps, "\n    CONFIG_PROVIDER", "(", ")")
        route_match = re.search(r'route\s*=\s*"([^"]+)"', provider)
        self.assertIsNotNone(route_match)
        self.assertEqual("config", route_match.group(1))
        activity = source(SRC / "MainActivity.kt")
        route = block(activity, f'composable("{route_match.group(1)}")')
        mount = block(route, "SettingsScreen", "(", ")")
        self.assertIn("onboardingStep = onboardingUiState.step.takeIf { onboardingUiState.active }", mount)
        self.assertIn("onOnboardingModelAdded =", mount)
        self.assertIn("onOnboardingDismissFetchDialog =", mount)
        self.assertRegex(mount, r"menuGroups\s*=\s*listOf\([^)]*SettingsSection\.AiGroup")
        for name in ("PROVIDER_ADD", "PROVIDER_CONFIG_INFO", "PROVIDER_FETCH_MODELS", "SIMULATE_FETCH_DIALOG"):
            self.assertIn('route = "config"', block(steps, f"\n    {name}", "(", ")"))
        effect = block(self.screen, "LaunchedEffect(onboardingStep)")
        step = block(effect, "OnboardingStep.CONFIG_PROVIDER ->")
        self.assertRegex(step, r"section\s*=\s*SettingsSection\.AiGroup\b")
        self.assertNotIn("section = SettingsSection.Menu", step)
        self.assertIn("SettingsSection.AiGroup -> SettingsAiGroupScreen(onOpen = openSection)", self.screen)
        config = function(self.groups, "SettingsAiGroupScreen")
        row = next(line for line in config.splitlines() if "OnboardingStep.CONFIG_PROVIDER" in line)
        self.assertIn("onOpen(SettingsSection.Providers)", row)

    def test_settings_cards_use_theme_radius(self):
        components = source(SETTINGS / "SettingsGroupComponents.kt")
        self.assertIn("import com.aharou.core.theme.Radius", components)
        self.assertNotIn("GroupCardCorner", components)
        self.assertIn("RoundedCornerShape(Radius.lg)", function(components, "SettingsGroup"))
        lazy = function(components, "LazyGroupItem")
        for corner, boundary in (("topStart", "isFirst"), ("topEnd", "isFirst"),
                                 ("bottomStart", "isLast"), ("bottomEnd", "isLast")):
            self.assertIn(f"{corner} = if ({boundary}) Radius.lg else 0.dp", lazy)

    def test_permission_paths_match_aharou_without_changing_path_algorithm(self):
        permissions = source(SRC / "feature/agent/domain/permission/PermissionRulesRepository.kt")
        self.assertNotIn("filesDir/aicode/permissions.json", permissions)
        self.assertNotIn(".aicode/permissions.json", permissions)
        self.assertNotIn("~/workspace/.aicode", permissions)
        self.assertIn('File(File(context.filesDir, "aharou"), PERMISSIONS_FILE)', permissions)
        self.assertIn("File(projectAicodeRoot.forPath(workspacePath), PERMISSIONS_FILE)", permissions)
        self.assertIn('const val AHAROU_DIR_NAME = ".aharou"', permissions)

    def test_rust_catalog_is_registered_with_rs_and_matching_command(self):
        self.assertIn("val RUST = Entry", self.catalog)
        rust = block(self.catalog, "val RUST = Entry", "(", ")")
        for field in ('extension = LanguageExtension.RUST', 'languageId = "rust"',
                      'fileExtensions = listOf("rs")', 'program = "rust-analyzer"'):
            self.assertIn(field, rust)
        self.assertRegex(self.catalog, r"val ALL[^\n]*\bRUST\b")
        self.assertIn("\n    RUST(", self.installer)
        rust_spec = block(self.installer, "\n    RUST(", "(", ")")
        self.assertIn('command = "rust-analyzer"', rust_spec)
        self.assertIn("InstallKind.RUSTUP", rust_spec)

    def test_rust_uses_available_rustup_without_installing_a_toolchain(self):
        install = function(self.installer, "installCommand")
        self.assertIn("InstallKind.RUSTUP ->", install)
        self.assertRegex(install, r'hasCommand\("rustup"\)')
        self.assertIn("rustup component add rust-analyzer", install)
        self.assertNotIn("sh.rustup.rs", self.installer)

    def test_xbps_support_covers_server_and_runtime_command_generation(self):
        server = function(self.installer, "packageManagerCommand")
        self.assertRegex(server, r'hasCommand\("xbps-install"\)\s*->\s*"xbps-install -[A-Za-z]*y[^"\n]*\$pkg"')
        runtime = function(self.installer, "runtimeInstallCommand")
        for command in (server, runtime):
            self.assertIn('hasCommand("xbps-install") ->', command)
            self.assertIn("else -> null", command)
            self.assertLess(command.index('hasCommand("zypper")'), command.index('hasCommand("xbps-install")'))
        void = block(runtime, 'hasCommand("xbps-install") ->')
        self.assertRegex(void, r'if\s*\(kind == RuntimeKind.NPM\)\s*"nodejs"\s*else\s*"python3 python3-pip"')
        self.assertRegex(void, r'"xbps-install -[A-Za-z]*y[^"\n]*\$voidPackages"')
        self.assertIn('val node = "nodejs npm"', runtime)
        self.assertNotIn('"nodejs npm"', void)

    def test_no_unproven_kotlin_lsp_is_added(self):
        self.assertNotRegex(self.catalog, r"LanguageExtension\.KOTLIN\b")
        self.assertNotIn('program = "kotlin-language-server"', self.catalog)


class TaskEntryAuditTest(unittest.TestCase):
    component = SRC / "feature/agent/presentation/component"

    def test_unassigned_entry_is_a_compact_workbench(self):
        text = source(self.component / "ProjectTaskDetailScreen.kt")
        for contract in ('testTag("task_compact_header")', "DropdownMenu(",
                         "ProjectTaskProcess(", "rememberSaveable(taskId)"):
            self.assertIn(contract, text)
        self.assertNotIn("task.sessionIds.forEachIndexed", text)
        self.assertNotIn("R.string.project_task_execution_hint", text)

    def test_preserves_execution_history_and_explicit_actions(self):
        text = source(self.component / "ProjectTaskDetailScreen.kt")
        for action in ("viewModel.execute(task, true, runExecution)",
                       "viewModel.execute(task, false, runExecution)",
                       "viewModel.cancelExecution(task.id)", "viewModel.accept(task)",
                       "onSelectSession(id)", "ProjectTaskEditor("):
            self.assertIn(action, text)
        self.assertIn("!state.loading && !state.busy", text)
        self.assertIn('testTag("task_start")', text)
        self.assertIn('testTag("task_stop")', text)

    def test_history_is_scrollable_without_weighted_content(self):
        text = source(self.component / "ProjectTaskDetailScreen.kt")
        body = text[text.index("if (task != null) Column("):text.index("if (overview && task != null)")]
        self.assertIn("verticalScroll(rememberScrollState())", body)
        self.assertIn("fillConversation = false", body)
        process = source(self.component / "ProjectTaskProcess.kt")
        self.assertIn("if (fillConversation) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth()", process)

    def test_errors_are_presented_through_shared_labels(self):
        text = source(self.component / "ProjectTaskDetailScreen.kt")
        self.assertIn("projectTaskExecutionReasonLabel(it)", text)
        labels = source(self.component / "ProjectTaskWorkbench.kt")
        self.assertIn('"FAILED", "TASK_EXECUTION_FAILED" ->', labels)

    def test_running_tasks_keep_the_edit_action_available(self):
        text = source(self.component / "ProjectTaskDetailScreen.kt")
        edit = text[text.index("DropdownMenuItem(text = { Text(stringResource(R.string.project_task_edit))"):]
        edit = edit[:edit.index("DropdownMenuItem(", 1)]
        self.assertIn("enabled = !state.loading && !state.busy", edit)
        self.assertNotIn("!running", edit)
        self.assertNotIn("enabled = ready", edit)

    def test_acceptance_button_exposes_busy_state_instead_of_silent_noop(self):
        detail = source(self.component / "ProjectTaskDetailScreen.kt")
        self.assertIn("acceptEnabled = !state.loading && !state.busy", detail)
        self.assertIn("onAccept = { viewModel.accept(task) }", detail)
        process = source(self.component / "ProjectTaskProcess.kt")
        self.assertIn("acceptEnabled: Boolean = true", process)
        self.assertIn("enabled = acceptEnabled &&", process)

    def test_index_template_indexes_instead_of_only_initializing(self):
        text = source(SRC / "feature/agent/domain/tool/custom/PracticalToolTemplates.kt")
        entry = text.split('name = "codegraph_index"', 1)[1].split("keywords =", 1)[0]
        self.assertIn('command = "codegraph index"', entry)


if __name__ == "__main__":
    unittest.main()
