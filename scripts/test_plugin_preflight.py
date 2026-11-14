#!/usr/bin/env python3
"""Patrol report rejection tests; fake reports here never prove plugin execution."""

import importlib.util
import json
import os
import struct
import subprocess
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("plugin_preflight", Path(__file__).with_name("plugin_preflight.py"))
PATROL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PATROL)


class PluginPreflightReportTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.report = self.root / "TEST-com.aharou.feature.plugin.PluginPatrolTest.xml"

    def tearDown(self):
        self.temporary.cleanup()

    def write(self, cases=None, attributes=""):
        names = sorted(PATROL.CASES) if cases is None else cases
        body = "".join(f'<testcase name="{name}"/>' for name in names)
        self.report.write_text(f'<testsuite tests="{len(names)}" {attributes}>{body}</testsuite>')
        for suite, required in PATROL.CONSUMER_CASES.items():
            cases = "".join(f'<testcase name="{name}"/>' for name in sorted(required))
            (self.root / f"TEST-com.aharou.feature.plugin.{suite}.xml").write_text(
                f'<testsuite tests="{len(required)}">{cases}</testsuite>')

    def test_model_says_pass_is_not_a_junit_report(self):
        (self.root / "model-response.json").write_text('{"success":true,"message":"All plugins passed"}')
        with self.assertRaises(ValueError):
            PATROL.read_runtime_results(self.root, 0)

    def test_zero_and_duplicate_cases_cannot_pass(self):
        for names in ([], [next(iter(PATROL.CASES))] * 2):
            self.write(names)
            with self.assertRaises(ValueError):
                PATROL.read_runtime_results(self.root, 0)

    def test_failed_errored_and_skipped_reports_cannot_pass(self):
        for state in ("failures", "errors", "skipped"):
            self.write(attributes=f'{state}="1"')
            with self.assertRaises(ValueError):
                PATROL.read_runtime_results(self.root, 0)
        self.write()
        self.report.write_text(self.report.read_text().replace("/>", "><skipped/></testcase>", 1))
        with self.assertRaises(ValueError):
            PATROL.read_runtime_results(self.root, 0)

    def test_old_green_report_cannot_pass_a_new_attempt(self):
        self.write()
        old = time.time() - 60
        os.utime(self.report, (old, old))
        with self.assertRaises(ValueError):
            PATROL.read_runtime_results(self.root, time.time())

    def test_missing_or_skipped_permission_consumer_cannot_pass(self):
        self.write()
        consumer = self.root / "TEST-com.aharou.feature.plugin.HostPluginBridgeTest.xml"
        consumer.unlink()
        with self.assertRaises(ValueError):
            PATROL.read_runtime_results(self.root, 0)
        self.write()
        consumer.write_text(consumer.read_text().replace('/>', '><skipped/></testcase>', 1))
        with self.assertRaises(ValueError):
            PATROL.read_runtime_results(self.root, 0)

    def test_fresh_exact_success_returns_case_provenance_not_plain_ok(self):
        self.write()
        result = PATROL.read_runtime_results(self.root, 0)
        self.assertEqual(sorted(PATROL.CASES), result["cases"])
        self.assertEqual(64, len(result["junit_sha256"]))

    def test_dex_class_descriptors_end_at_nul_and_bad_indexes_are_rejected(self):
        descriptor = b"Lexample/ExamplePlugin;"
        data = bytearray(152)
        data[:8] = bytes([100, 101, 120, 10, 48, 51, 53, 0])
        struct.pack_into("<II", data, 56, 1, 112)
        struct.pack_into("<II", data, 64, 1, 116)
        struct.pack_into("<II", data, 96, 1, 120)
        struct.pack_into("<I", data, 112, 152)
        data += bytes([len(descriptor)]) + descriptor + bytes([0])
        self.assertEqual({descriptor.decode("ascii")}, PATROL.dex_defined_classes(data))
        with self.assertRaises(ValueError):
            PATROL.dex_defined_classes(data[:-1])
        struct.pack_into("<I", data, 120, 2)
        with self.assertRaises(ValueError):
            PATROL.dex_defined_classes(data)

    def test_runtime_exit_exception_and_timeout_always_write_failed_report(self):
        for failure in (RuntimeError("fixture process launch failure"), subprocess.TimeoutExpired("fixture-java", 1)):
            output = self.root / "lifecycle.json"
            args = ["plugin_preflight.py", "--results", str(self.root), "--output", str(output),
                    "--classpath-file", str(self.root / "classpath.txt"), "--java", "fixture-java"]
            with patch("sys.argv", args), patch.object(PATROL, "run_runtime", side_effect=failure):
                with self.assertRaises(type(failure)):
                    PATROL.main()
            self.assertEqual("failed", json.loads(output.read_text())["status"])
        output = self.root / "nonzero.json"
        args = ["plugin_preflight.py", "--results", str(self.root), "--output", str(output),
                "--classpath-file", str(self.root / "classpath.txt"), "--java", "fixture-java"]
        with patch("sys.argv", args), patch.object(PATROL, "run_runtime", return_value=9), \
                patch.object(PATROL, "read_runtime_results", return_value={"fixture": True}):
            with self.assertRaises(ValueError):
                PATROL.main()
        report = json.loads(output.read_text())
        self.assertEqual("failed", report["status"])
        self.assertEqual(9, report["runtimeExitCode"])

    def test_successful_execution_wrapper_still_requires_package_verification(self):
        output = self.root / "success.json"
        args = ["plugin_preflight.py", "--results", str(self.root), "--output", str(output),
                "--classpath-file", str(self.root / "classpath.txt"), "--java", "fixture-java"]
        with patch("sys.argv", args), patch.object(PATROL, "run_runtime", return_value=0), \
                patch.object(PATROL, "read_runtime_results", return_value={"fixture": True}), \
                patch.object(PATROL, "compile_fixtures", return_value={"fixture": True}):
            PATROL.main()
        self.assertEqual("passed_with_device_checks_unverified", json.loads(output.read_text())["status"])

    def test_debug_beta_and_release_packaging_require_the_runtime_gate(self):
        gradle = Path(__file__).resolve().parents[1] / "app/build.gradle.kts"
        text = gradle.read_text()
        self.assertIn('setOf("packageArmsoloDebug", "packageArmsoloBeta", "packageArmsoloRelease")', text)
        self.assertIn("dependsOn(pluginPreflight)", text)
        self.assertIn("dependsOn(pluginPatrolTest)", text)
        self.assertIn('tasks.register<Exec>("pluginPatrolTest")', text)
        self.assertIn('"--classpath-file", classpathFile.absolutePath, "--java", java.absolutePath', text)
        self.assertIn("outputs.upToDateWhen { false }", text)
        runner = gradle.parent / "src/test/java/com/aharou/feature/plugin/PluginPatrolRunner.kt"
        source = runner.read_text()
        self.assertIn("JUnitCore()", source)
        self.assertIn("PluginPatrolTest::class.java", source)
        self.assertIn("HostPluginBridgeTest::class.java", source)
        self.assertIn("HostPluginRuntimeTest::class.java", source)
        self.assertIn("HostPluginHttpInterceptorTest::class.java", source)


if __name__ == "__main__":
    unittest.main()
