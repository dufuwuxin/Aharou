"""Source contracts for settings onboarding and origin-aware connector navigation.

No clicks, Kotlin execution or package installation are simulated by these checks.
"""
import os
import re
import unittest
from pathlib import Path

ROOT = Path(os.environ.get("AHAROU_TEST_ROOT", Path(__file__).resolve().parents[1]))
SRC = ROOT / "app/src/main/java/com/aharou"
SETTINGS = SRC / "feature/settings/presentation/component/SettingsScreen.kt"


def body(text, marker, opening_char="{", closing_char="}"):
    start = text.index(marker) + len(marker)
    tokens = re.compile(r'""".*?"""|"(?:\\.|[^"\\])*"|//[^\n]*|/\*.*?\*/', re.S)
    masked = tokens.sub(lambda match: " " * len(match.group()), text)
    opening = masked.index(opening_char, start)
    depth = 1
    for pos in range(opening + 1, len(masked)):
        depth += (masked[pos] == opening_char) - (masked[pos] == closing_char)
        if depth == 0:
            return text[opening + 1:pos]
    raise AssertionError(f"Unclosed source block: {marker}")


class SettingsOnboardingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.screen = SETTINGS.read_text()
        cls.activity = (SRC / "MainActivity.kt").read_text()
        cls.steps = (SRC / "feature/onboarding/domain/OnboardingStep.kt").read_text()

    def test_provider_steps_route_to_config(self):
        for step in ("CONFIG_PROVIDER", "PROVIDER_ADD", "PROVIDER_CONFIG_INFO",
                     "PROVIDER_FETCH_MODELS", "SIMULATE_FETCH_DIALOG"):
            with self.subTest(step=step):
                match = re.search(rf"\n    {step}\(\s*route = \"([^\"]+)\"", self.steps)
                self.assertIsNotNone(match)
                self.assertEqual("config", match.group(1))

    def test_config_home_shows_provider_onboarding_target(self):
        effect = body(self.screen, "OnboardingStep.CONFIG_PROVIDER ->")
        self.assertIn("section = SettingsSection.AiGroup", effect)
        groups = (SETTINGS.parent / "SettingsGroupScreens.kt").read_text()
        self.assertIn("OnboardingStep.CONFIG_PROVIDER", body(groups, "fun SettingsAiGroupScreen("))

    def test_config_receives_onboarding_and_completion_callbacks(self):
        config = body(self.activity, 'composable("config")')
        self.assertIn("onboardingStep = onboardingUiState.step.takeIf { onboardingUiState.active }", config)
        added = body(config, "onOnboardingModelAdded =")
        self.assertIn("OnboardingStep.SIMULATE_FETCH_DIALOG", added)
        self.assertIn("navController.popBackStack()", added)
        self.assertIn("onboardingCoordinator.nextStep()", added)
        dismissed = body(config, "onOnboardingDismissFetchDialog =")
        self.assertIn("onboardingCoordinator.goToStep(OnboardingStep.PROVIDER_FETCH_MODELS)", dismissed)
        settings = body(self.activity, 'composable("settings")')
        self.assertNotIn("onboardingStep =", settings)

    def test_both_overlay_entry_actions_open_config_and_finish_returns(self):
        overlay = body(self.activity, "\n        OnboardingOverlay", "(", ")")
        for callback in ("onNextStep =", "onTargetClick ="):
            with self.subTest(callback=callback):
                action = body(overlay, callback)
                entry = body(action, "OnboardingStep.ENTER_SETTINGS ->")
                self.assertIn('navController.navigate("config")', entry)
                self.assertNotIn('navController.navigate("settings")', entry)
                finished = body(action, "OnboardingStep.SIMULATE_FETCH_DIALOG ->")
                self.assertIn("navController.popBackStack()", finished)
                self.assertIn("onboardingCoordinator.nextStep()", finished)

    def test_connector_entry_records_current_section(self):
        opening = body(self.screen, "val openSection:")
        self.assertRegex(opening, r"if\s*\(target == SettingsSection.Connectors\)\s*connectorsReturnSection = section")
        self.assertIn("section = target", opening)
        self.assertIn("onBrowseDirectory = { openSection(SettingsSection.Connectors) }", self.screen)
        self.assertRegex(self.screen, r"var connectorsReturnSection by remember.*SettingsSection.AiGroup")

    def test_connector_back_and_dialog_use_recorded_origin(self):
        self.assertIn("SettingsSection.Connectors -> connectorsReturnSection", self.screen)
        dialog = body(self.screen, "McpServerDialogHost", "(", ")")
        self.assertRegex(dialog, r"if\s*\(section == SettingsSection.Connectors\)\s*section = connectorsReturnSection")
        self.assertRegex(dialog, r"logReturnSection = if\s*\(section == SettingsSection.Connectors\)\s*connectorsReturnSection else section")
        self.assertNotIn("SettingsSection.Mcp", self.screen)


if __name__ == "__main__":
    unittest.main()
