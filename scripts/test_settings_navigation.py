"""Source contracts only: these checks do not simulate clicks or install packages.

Set AHAROU_TEST_ROOT to check another implementation tree with this same suite.
"""
import os
import re
import unittest
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

    def test_account_and_all_four_im_pages_have_clickable_entries(self):
        account = function(self.groups, "SettingsAharouRows")
        config = function(self.groups, "SettingsAiGroupScreen")
        for name, rows in (("AccountCenter", account), *((name, config) for name in
                          ("TelegramBot", "WeixinBot", "FeishuBot", "QqBot"))):
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

    def test_automation_has_direct_config_entry_and_reuses_existing_page(self):
        self.assertIn("onOpen(SettingsSection.ScheduledTasks)", function(self.groups, "SettingsAiGroupScreen"))
        self.assertRegex(self.screen, r"ScheduledTasks\(R\.string\.settings_scheduled_tasks\)")
        self.assertIn("ScheduledTasksSection()", block(self.screen, "SettingsSection.ScheduledTasks ->"))
        self.assertIn("ScheduledTasksSection()", self.tools)

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


if __name__ == "__main__":
    unittest.main()
