"""Source-level contract; does not replace Kotlin compilation or device testing."""
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/aharou"
SETTINGS = SRC / "feature/settings/presentation/component"


class FlatSettingsNoVoiceTest(unittest.TestCase):
    def test_removed_group_routes(self):
        text = (SETTINGS / "SettingsScreen.kt").read_text()
        for name in ("AharouGroup", "GeneralGroup", "PermissionsGroup", "MoreGroup", "Extensions"):
            self.assertTrue(f"SettingsSection.{name}" not in text, name)
        self.assertIn("SettingsSection.AiGroup", text)
        for name in ("Plugins", "Connectors"):
            self.assertIn(f"SettingsSection.{name}", text)
        self.assertFalse((SETTINGS / "ExtensionsSection.kt").exists())

    def test_no_voice_implementation_or_calls(self):
        self.assertFalse(any((SRC / "feature/voice").rglob("*.kt")))
        for path in SRC.rglob("*.kt"):
            text = path.read_text()
            with self.subTest(path=str(path.relative_to(SRC))):
                for symbol in ("com.aharou.feature.voice", "VoiceMicButton", "ConfigVoiceFields", "VoiceCallService", "RECORD_AUDIO", "ACTION_START_VOICE", "fun startVoice("):
                    self.assertTrue(symbol not in text, symbol)

    def test_manifest_keeps_accessibility_without_microphone(self):
        text = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
        for symbol in ("RECORD_AUDIO", "FOREGROUND_SERVICE_MICROPHONE", "voice.assistant", "VoiceCallService", "RECORD_AUDIO", "ACTION_START_VOICE", "fun startVoice("):
            self.assertTrue(symbol not in text, symbol)
        self.assertIn("BIND_ACCESSIBILITY_SERVICE", text)
        self.assertIn("shizuku", text.lower())

    def test_media_control_remains(self):
        self.assertTrue(any("media_control" in p.read_text() for p in SRC.rglob("*.kt")))

    def test_hsl_control_is_defined_without_skin_editor(self):
        self.assertIn("fun HslSliders(", (SETTINGS / "ChatAppearanceSheet.kt").read_text())
        self.assertFalse((SETTINGS / "SkinEditorSheet.kt").exists())

    def test_no_voice_storage_or_pet_entry(self):
        for name in ("feature/settings/domain/model/StorageUsage.kt", "feature/settings/presentation/StorageViewModel.kt", "feature/settings/presentation/component/StorageSection.kt", "core/theme/AIEditorTheme.kt"):
            self.assertNotIn("VoiceModels", (SRC / name).read_text())
        self.assertNotIn("ID_CALL", (SRC / "feature/pet/PetOverlay.kt").read_text())

    def test_no_voice_shortcuts_or_widget_and_old_shortcuts_disabled(self):
        for name in ("app/src/main/res/xml/shortcuts.xml", "app/src/main/res/layout/widget_aharou.xml"):
            self.assertNotIn("voice", (ROOT / name).read_text())
        launcher = (SRC / "core/util/LauncherShortcutOverrides.kt").read_text()
        self.assertIn('removeDynamicShortcuts(ctx, listOf("start_voice"))', launcher)
        self.assertIn('disableShortcuts(ctx, listOf("start_voice"), null)', launcher)
        self.assertEqual(1, launcher.count('removeDynamicShortcuts(ctx, listOf("start_voice"))'))
        self.assertLess(launcher.index('disableShortcuts(ctx,'), launcher.index('push(ctx, "new_chat"'))

    def test_settings_import_export_helpers_are_defined(self):
        text = (SETTINGS / "SettingsScreen.kt").read_text()
        for declaration in ("private fun shareFile(", "private fun SkillImportResultDialog(", "private fun SkillImportError.messageRes("):
            self.assertIn(declaration, text)

    def test_no_pet_call_state_or_widget_voice_binding(self):
        pet = (SRC / "feature/pet/PetOverlay.kt").read_text()
        for symbol in ("setCallState", "inCall", "pet_menu_call", "ic_pet_mic"):
            self.assertNotIn(symbol, pet)
        for name in ("WidgetActions.kt", "WidgetRenderer.kt"):
            text = (SRC / "feature/widget" / name).read_text()
            for symbol in ("startVoice", "widget_entry_voice", "SLOT_VOICE", "START_VOICE"):
                self.assertNotIn(symbol, text)

    def test_no_voice_only_prompt_resources_or_references(self):
        resources = ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()
        prefixes = ("voice_", "settings_voice_", "assistant_", "storage_category_voice_", "storage_clean_voice_", "permissions_mic", "pet_perm_mic")
        names = {node.get("name", "") for node in resources}
        for name in names:
            self.assertFalse(name.startswith(prefixes), name)
        for name in ("shortcut_start_voice", "widget_entry_voice", "pet_menu_call", "pet_no_mic"):
            self.assertNotIn(name, names)
        for path in SRC.rglob("*.kt"):
            text = path.read_text()
            for name in (*prefixes, "shortcut_start_voice", "widget_entry_voice", "pet_menu_call", "pet_no_mic"):
                self.assertNotIn(f"R.string.{name}", text, str(path))
        hint = resources.find("string[@name='pet_menu_hint']")
        self.assertIsNotNone(hint)
        self.assertNotIn("麦克风", hint.text)

    def test_removed_native_speech_libraries(self):
        self.assertFalse(any((ROOT / "app/src/main/java/com/k2fsa/sherpa/onnx").glob("*.kt")))
        for name in ("libonnxruntime.so", "libsherpa-onnx-jni.so", "libsherpa-onnx-c-api.so", "libsherpa-onnx-cxx-api.so"):
            self.assertFalse((ROOT / "app/src/_armJniLibs/arm64-v8a" / name).exists())

    def test_header_no_title_but_permission_attribution_remains(self):
        self.assertNotIn("sessionTitle", (SRC / "feature/agent/presentation/component/ChatHeaderComponents.kt").read_text())
        self.assertIn("sessionTitle = pendingPermissionSessionTitle", (SRC / "feature/agent/presentation/component/AIChatPanel.kt").read_text())

    def test_single_chinese_regression_is_present(self):
        self.assertTrue((ROOT / "scripts/test_single_chinese.py").is_file())


if __name__ == "__main__":
    unittest.main()
