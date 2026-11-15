"""Offline contract tests; never execute Gradle or contact an Android device."""

import re
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import ci_test_groups as groups

ROOT = Path(__file__).resolve().parents[1]


class CiLayersTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def source(self, package="com.aharou.feature.editor", name="DocumentTest"):
        directory = self.root / "app/src/test/java"
        directory.mkdir(parents=True, exist_ok=True)
        (directory / f"{name}.kt").write_text(
            f"package {package}\nclass {name} {{ @Test fun saves() {{}} }}\n",
            encoding="utf-8",
        )
        return f"{package}.{name}"

    def report(self, directory, name, body="", classname=None):
        directory.mkdir(parents=True, exist_ok=True)
        (directory / f"TEST-{name}.xml").write_text(
            f'<testsuite><testcase classname="{classname or name}" name="works">'
            f"{body}</testcase></testsuite>", encoding="utf-8",
        )

    def test_real_classes_form_complete_unique_partition(self):
        found = groups.discover(ROOT)
        flat = [name for members in found.values() for name in members]
        self.assertTrue(all(found.values()))
        self.assertEqual(len(flat), len(set(flat)))
        self.assertIn("com.aharou.feature.agent.presentation.ProjectGoalStoreTest", flat)
        for path in (ROOT / "app/src/test").rglob("*.kt"):
            text = path.read_text(encoding="utf-8")
            if re.search(r"@(?:org\.junit\.)?Test\b", text):
                package = re.search(r"^package\s+([\w.]+)", text, re.MULTILINE)[1]
                self.assertTrue(any(name.startswith(package + ".") and
                    re.search(r"\bclass\s+" + re.escape(name.rsplit(".", 1)[1]) + r"\b", text) for name in flat), str(path))

    def test_multiple_top_level_test_classes_are_not_lost_to_the_file_name(self):
        self.source(name="FirstTest")
        path = self.root / "app/src/test/java/FirstTest.kt"
        path.write_text(path.read_text() + "class SecondTest { @Test fun second() {} }\n")
        self.assertEqual(groups.discover(self.root)["editor"], [
            "com.aharou.feature.editor.FirstTest", "com.aharou.feature.editor.SecondTest"])

    def test_discovery_uses_package_not_directory(self):
        expected = self.source()
        self.assertEqual(groups.discover(self.root)["editor"], [expected])

    def test_unknown_domain_is_not_silently_omitted(self):
        self.source(package="com.aharou.feature.newfeature")
        with self.assertRaisesRegex(ValueError, "visible CI step"):
            groups.discover(self.root)

    def test_no_sources_rejected(self):
        with self.assertRaisesRegex(ValueError, "No app test"):
            groups.discover(self.root)

    def test_missing_empty_and_failed_reports_rejected(self):
        directory = self.root / "reports"
        with self.assertRaisesRegex(ValueError, "No test reports"):
            groups.report_classes(directory)
        self.report(directory, "Example", "<skipped/>")
        with self.assertRaisesRegex(ValueError, "Zero executed|Skipped test"):
            groups.report_classes(directory)
        self.report(directory, "Example", "<failure/>")
        with self.assertRaisesRegex(ValueError, "Failed test"):
            groups.report_classes(directory)

    def test_repeated_testcase_is_rejected_before_class_set_deduplication(self):
        self.report(self.root, "CopyOne", classname="Example")
        self.report(self.root, "CopyTwo", classname="Example")
        with self.assertRaisesRegex(ValueError, "Duplicate test execution"):
            groups.verify_report(self.root, ["Example"])

    def test_missing_and_extra_classes_rejected(self):
        self.report(self.root, "Actual")
        with self.assertRaisesRegex(ValueError, "coverage mismatch"):
            groups.verify_report(self.root, ["Expected"])

    def test_failed_gradle_status_is_preserved(self):
        self.source()
        with patch.object(groups.subprocess, "run") as run:
            run.return_value.returncode = 7
            self.assertEqual(groups.run_group(self.root, "editor", ["Example"]), 7)
            command = run.call_args.args[0]
            self.assertEqual(command[-2:], ["--tests", "Example"])
            self.assertFalse(run.call_args.kwargs["check"])

    def test_success_without_tests_is_rejected(self):
        with patch.object(groups.subprocess, "run") as run:
            run.return_value.returncode = 0
            with self.assertRaisesRegex(ValueError, "No test reports"):
                groups.run_group(self.root, "editor", ["Example"])

    def test_combination_checks_every_group_and_overlap(self):
        self.report(self.root / groups.ARCHIVE / "editor", "Example")
        groups.verify_all(self.root, {"editor": ["Example"]})
        self.report(self.root / groups.ARCHIVE / "core", "Example")
        with self.assertRaisesRegex(ValueError, "multiple groups"):
            groups.verify_all(self.root, {"editor": ["Example"], "core": ["Example"]})

    def test_combined_same_jvm_regression_runs_before_final_artifact_acceptance(self):
        text = (ROOT / ".github/workflows/ci.yml").read_text()
        self.assertIn("./gradlew :app:testArmsoloDebugUnitTest :core-agent:test --console=plain", text)
        self.assertIn("steps.combined_regression.outcome == 'success'", text)
        self.assertLess(text.index("Run combined same-JVM app regression"), text.index("Upload test report"))
        self.assertIn("id: combined_regression", text)

    def test_combination_dependencies_are_handed_off_and_verified_not_assumed_cached(self):
        text = (ROOT / ".github/workflows/ci.yml").read_text()
        self.assertIn("name: combination-jvm-dependencies", text)
        self.assertIn("Missing resolved JVM dependency", text)
        self.assertIn("Combination dependency digest mismatch", text)
        self.assertIn('"com.android.tools/r8/9.1.57"', text)
        self.assertLess(text.index("Verify and restore combination dependency identity"), text.index("Verify built-in team evidence fixtures"))

    def test_workflow_dependencies_and_original_gates(self):
        text = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        for dependency in ("foundation", "feature-tests", "combination"):
            self.assertIn(f"needs: {dependency}", text)
        self.assertIn("fail-fast: false", text)
        self.assertIn("steps.group_manifest.outcome == 'success'", text)
        self.assertEqual(set(re.findall(r"ci_test_groups.py --group (\w+)", text)), set(groups.GROUPS))
        for command in (
            ":core-agent:test", ":core-storage:jvmJar",
            ":terminal-emulator:compileDebugJavaWithJavac",
            ":terminal-view:compileDebugJavaWithJavac", ":app:assembleArmsoloDebug",
            "check_migrations.py", "test_check_migrations.py", "check_skills.py",
            "check_builtin_container.py", "test_builtin_ubuntu.py", "test_container_image_sync.py",
            "check_architecture.py --check-ratchet", "test_security_audit.py",
            "test_builtin_team_evidence.py", "test_release_yaml.py", "test_plugin_sdk.py",
            "test_plugin_preflight.py", "test_translation_quality.py --bridge-test",
            "test_build_intent.py", "test_translation_app_download.py",
            "unit-test-results", "plugin-patrol-evidence",
        ):
            self.assertIn(command, text)
        final = text[text.index("  build:\n"):]
        self.assertIn("name: Build & Test", final)
        self.assertIn("needs: combination", final)
        self.assertIn(":app:assembleArmsoloDebug", final)
        self.assertNotIn(":app:assembleArmsoloDebug", text[:text.index("  build:\n")])


if __name__ == "__main__":
    unittest.main()
