#!/usr/bin/env python3
"""轻量回归：真实仓库合同与临时资源反向夹具，不启动 Gradle。"""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import check_architecture as gate

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/aharou"


class ChineseResourceGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.policy = {"chineseResources": {"res": "app/src/main/res", "locale": "zh"}}
        self.write("app/src/main/res/values/strings.xml", '<resources><string name="hello">你好</string></resources>')
        self.write("app/src/main/res/xml/locales_config.xml", '<locale-config xmlns:android="http://schemas.android.com/apk/res/android"><locale android:name="zh"/></locale-config>')

    def write(self, path, text):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")

    def errors(self):
        errors = []
        with patch.object(gate, "ROOT", str(self.root)):
            gate.check_chinese_resources(self.policy, errors)
        return errors

    def test_chinese_and_technical_names_pass(self):
        self.write("app/src/main/java/Test.kt", 'val a = R.string.hello; val b = android.R.string.ok')
        self.assertEqual([], self.errors())

    def test_foreign_language_in_variant_fails(self):
        self.write("app/src/beta/res/values-en/labels.xml", '<resources><string name="hello">Hello</string></resources>')
        self.assertTrue(self.errors())

    def test_missing_reference_fails(self):
        self.write("app/src/main/java/Test.kt", 'val a = R.string.missing')
        self.assertTrue(self.errors())

    def test_string_comment_markers_do_not_hide_references(self):
        cases = (
            'val a = listOf("https://example.invalid", R.string.missing)',
            'val a = "/* not a comment */"; val b = R.string.missing',
            'val a = """https://example.invalid"""; val b = R.string.missing',
            'val a = "https://example.invalid/${R.string.missing}"',
            'val a = """https://example.invalid/${R.string.missing}"""',
        )
        for source in cases:
            with self.subTest(source=source):
                self.write("app/src/main/java/Test.kt", source)
                self.assertTrue(self.errors())

    def test_real_comments_are_ignored(self):
        self.write("app/src/main/java/Test.kt", 'val a = "https://example.invalid"; // R.string.missing\n/* R.string.missing */ val b = R.string.hello')
        self.assertEqual([], self.errors())

    def test_xml_missing_reference_fails(self):
        self.write("app/src/main/res/layout/test.xml", '<TextView text="@string/missing"/>')
        self.assertTrue(self.errors())

    def test_duplicate_and_malformed_resources_fail(self):
        self.write("app/src/main/res/values/other.xml", '<resources><string name="hello">重复</string></resources>')
        self.assertTrue(self.errors())
        self.write("app/src/main/res/values/other.xml", '<resources>')
        self.assertTrue(self.errors())

    def test_missing_policy_and_locale_fail(self):
        self.policy.clear()
        self.assertTrue(self.errors())
        self.policy["chineseResources"] = {"res": "app/src/main/res", "locale": "zh"}
        self.write("app/src/main/res/xml/locales_config.xml", '<locale-config/>')
        self.assertTrue(self.errors())

    def test_night_resources_and_split_chinese_pass(self):
        self.write("app/src/main/res/values-night/styles.xml", '<resources><style name="Night"/></resources>')
        self.write("app/src/main/res/values/labels.xml", '<resources><string name="second">第二</string></resources>')
        self.write("app/src/main/java/Test.kt", 'val a = R.string.second')
        self.assertEqual([], self.errors())


class FixedAppearanceContractTest(unittest.TestCase):
    def test_repository_and_contexts_ignore_old_language(self):
        repo = (JAVA / "feature/settings/data/repository/LanguageSettingsRepository.kt").read_text()
        self.assertIn('fun getLanguageSync(): String? = "zh"', repo)
        self.assertIn('it[LANGUAGE_KEY] = "zh"', repo)
        self.assertNotIn('getString(PREFS_KEY', repo)
        for name in ("MainActivity.kt", "AIEditorApp.kt"):
            self.assertIn('config.setLocale(java.util.Locale.SIMPLIFIED_CHINESE)', (JAVA / name).read_text())

    def test_config_has_no_language_or_palette_writers(self):
        app = (JAVA / "feature/settings/data/ConfigAppFields.kt").read_text()
        colors = (JAVA / "feature/settings/data/ConfigAppearanceFields.kt").read_text()
        self.assertNotIn('path = "app.language"', app)
        for path in ("appearance.theme_preset", "appearance.dynamic_color", "appearance.skins"):
            self.assertNotIn(path, colors)
        self.assertIn('path = "appearance.theme_mode"', colors)

    def test_old_backups_cannot_restore_palette(self):
        repo = (JAVA / "feature/settings/data/repository/ThemeSettingsRepository.kt").read_text()
        self.assertIn('suspend fun presetSnapshot(): String = "xiaocheng"', repo)
        self.assertIn('suspend fun dynamicColorSnapshot(): Boolean = false', repo)
        for key in ("THEME_SKIN_KEY", "THEME_PRESET_KEY", "CUSTOM_SKINS_KEY", "DYNAMIC_COLOR_KEY"):
            self.assertIn('it.remove(%s)' % key, repo)
        host = (JAVA / "feature/settings/presentation/component/AppThemeHost.kt").read_text()
        self.assertIn('AppThemeMode.AUTO -> systemDark', host)
        theme = (JAVA / "core/theme/AIEditorTheme.kt").read_text()
        self.assertIn('val preset = AppThemePreset.DEFAULT', theme)
        self.assertNotIn('dynamicColor', theme)
        skins = (JAVA / "core/theme/AppSkins.kt").read_text()
        self.assertIn('listOf(Xiaocheng)', skins)
        self.assertNotIn('val Qinglan', skins)
        self.assertFalse((JAVA / "core/theme/AppCustomSkin.kt").exists())


if __name__ == "__main__":
    unittest.main()
