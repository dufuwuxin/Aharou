"""Offline contract tests; never execute Gradle or contact an Android device."""

import os
import re
import subprocess
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

    def test_suite_summary_failures_and_count_mismatches_are_rejected(self):
        directory = self.root / "reports"
        self.report(directory, "Example")
        path = directory / "TEST-Example.xml"
        original = path.read_text()
        for attributes in ('failures="1"', 'errors="1"', 'skipped="1"', 'tests="2"'):
            with self.subTest(attributes=attributes):
                path.write_text(original.replace("<testsuite>", f"<testsuite {attributes}>"))
                with self.assertRaises(ValueError):
                    groups.report_classes(directory)

    def test_empty_suite_cannot_hide_beside_a_successful_suite(self):
        self.report(self.root, "Example")
        (self.root / "TEST-Empty.xml").write_text('<testsuite tests="0"/>')
        with self.assertRaises(ValueError):
            groups.report_classes(self.root)

    def test_combined_reports_require_complete_app_and_core_results(self):
        expected = self.source()
        self.report(self.root / groups.RESULTS, expected)
        core_source = self.root / "core-agent/src/test/java/CoreTest.kt"
        core_source.parent.mkdir(parents=True, exist_ok=True)
        core_source.write_text("package com.aharou.core\nclass CoreTest { @Test fun works() {} }\n")
        core_name = "com.aharou.core.CoreTest"
        core = self.root / "core-agent/build/test-results/test"
        with self.assertRaisesRegex(ValueError, "No test reports"):
            groups.verify_combined(self.root, {"editor": [expected]})
        self.report(core, core_name, "<skipped/>")
        with self.assertRaisesRegex(ValueError, "Skipped test"):
            groups.verify_combined(self.root, {"editor": [expected]})
        self.report(core, core_name)
        groups.verify_combined(self.root, {"editor": [expected]})
        self.report(self.root / groups.RESULTS, expected, "<skipped/>")
        with self.assertRaisesRegex(ValueError, "Skipped test"):
            groups.verify_combined(self.root, {"editor": [expected]})
        self.report(self.root / groups.RESULTS, expected)
        with self.assertRaisesRegex(ValueError, "coverage mismatch"):
            groups.verify_combined(self.root, {"editor": [expected, "MissingTest"]})

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
        step = text[text.index("      - name: Run combined same-JVM app regression\n"):
                    text.index("      - name: Export exact combination JVM dependencies\n")]
        self.assertIn("id: combined_regression", step)
        self.assertNotIn("continue-on-error:", step)
        self.assertEqual(step.count("      - name:"), 1)
        self.assertLess(step.index("./gradlew :app:testArmsoloDebugUnitTest :core-agent:test --console=plain"),
                        step.index("python3 scripts/ci_test_groups.py --verify-combined"))

    def test_combined_step_preserves_gradle_and_xml_failure_exit_codes(self):
        text = (ROOT / ".github/workflows/ci.yml").read_text()
        step = text[text.index("      - name: Run combined same-JVM app regression\n"):
                    text.index("      - name: Export exact combination JVM dependencies\n")]
        body = step.split("        run: |\n", 1)[1]
        command = "\n".join(line[10:] for line in body.splitlines())
        binaries = self.root / "bin"
        binaries.mkdir()
        for name, script in {
            "gradlew": '#!/bin/sh\necho gradle >> "$EVENTS"\nexit "$GRADLE_STATUS"\n',
            "python3": '#!/bin/sh\necho verify >> "$EVENTS"\nexit "$VERIFY_STATUS"\n',
            "tee": '#!/bin/sh\ncat\n',
        }.items():
            executable = self.root / name if name == "gradlew" else binaries / name
            executable.write_text(script)
            executable.chmod(0o755)
        for gradle_status, verify_status, expected, events in (
            (0, 0, 0, "gradle\nverify\n"),
            (0, 7, 7, "gradle\nverify\n"),
            (6, 0, 6, "gradle\n"),
        ):
            with self.subTest(gradle_status=gradle_status, verify_status=verify_status):
                event_file = self.root / "events"
                event_file.write_text("")
                result = subprocess.run(
                    ["bash", "--noprofile", "--norc", "-e", "-o", "pipefail", "-c", command],
                    cwd=self.root, capture_output=True, text=True, check=False,
                    env={**os.environ, "PATH": str(binaries) + os.pathsep + os.environ["PATH"],
                         "EVENTS": str(event_file), "GRADLE_STATUS": str(gradle_status),
                         "VERIFY_STATUS": str(verify_status)},
                )
                self.assertEqual(result.returncode, expected, result.stderr)
                self.assertEqual(event_file.read_text(), events)

    def test_release_checks_raw_reports_before_packaging(self):
        text = (ROOT / ".github/workflows/android-release.yml").read_text()
        step = text[text.index("      - name: Run unit tests\n"):
                    text.index("      - name: Verify built-in team evidence fixtures\n")]
        self.assertNotIn("continue-on-error:", step)
        self.assertEqual(step.count("      - name:"), 1)
        self.assertLess(step.index("./gradlew :core-agent:test :app:testArmsoloDebugUnitTest"),
                        step.index("python3 scripts/ci_test_groups.py --verify-combined"))
        self.assertLess(text.index("python3 scripts/ci_test_groups.py --verify-combined"),
                        text.index("      - name: Build ARM64 Release APK\n"))

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
