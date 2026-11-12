#!/usr/bin/env python3
"""Offline synthetic fixtures; these are not evidence of an Android/CI run."""

import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path

import verify_upgrade_evidence as verify


class VerifyUpgradeEvidenceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.sha = "a" * 40
        self.run = {"id": 123, "run_attempt": 1, "head_sha": self.sha, "status": "completed", "conclusion": "success"}
        self.jobs = {"total_count": 1, "jobs": [{
            "id": 456, "run_id": 123, "run_attempt": 1, "head_sha": self.sha,
            "name": "Build & Test", "status": "completed", "conclusion": "success",
            "started_at": "2026-01-01T00:00:00Z", "completed_at": "2026-01-01T00:01:00Z",
            "steps": [{"name": "Build", "status": "completed", "conclusion": "success"}],
        }]}
        self.artifacts = {"total_count": 1, "artifacts": [{
            "id": 789, "name": "aharou-debug-apk", "expired": False, "size_in_bytes": 128,
            "expires_at": "2999-01-01T00:00:00Z",
            "archive_download_url": "https://api.github.com/repos/example/app/actions/artifacts/789/zip",
            "workflow_run": {"id": 123, "head_sha": self.sha},
        }]}
        self.junit = self.root / "junit"
        self.junit.mkdir()
        self.xml = self.junit / "TEST-example.xml"
        self.xml.write_text('<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase name="example"/></testsuite>', encoding="utf-8")
        self.log = self.root / "build.log"
        self.log.write_text("BUILD SUCCESSFUL\n0 errors, 0 warnings\n", encoding="utf-8")

    def invoke(self, extra: list[str] | None = None) -> tuple[int, str]:
        for name, data in (("run", self.run), ("jobs", self.jobs), ("artifacts", self.artifacts)):
            (self.root / f"{name}.json").write_text(json.dumps(data), encoding="utf-8")
        args = ["--expected-sha", self.sha]
        for name in ("run", "jobs", "artifacts"):
            args.extend([f"--{name}-json", str(self.root / f"{name}.json")])
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            result = verify.main(args + (extra or []))
        return result, output.getvalue()

    def assert_rejected(self, text: str, extra: list[str] | None = None) -> None:
        code, output = self.invoke(extra)
        self.assertEqual(code, 1, output)
        self.assertIn(text, output)

    def test_clean_success(self) -> None:
        code, output = self.invoke(["--junit-dir", str(self.junit), "--require-clean-log", "--build-log", str(self.log)])
        self.assertEqual(code, 0, output)
        self.assertIn("JUnit tests=1", output)
        self.assertIn("logs=clean", output)

    def test_minimal_success_marks_optional_evidence_unchecked(self) -> None:
        code, output = self.invoke()
        self.assertEqual(code, 0, output)
        self.assertIn("JUnit tests=not checked; logs=not checked", output)

    def test_wrong_sha_and_abbreviated_sha(self) -> None:
        self.run["head_sha"] = "b" * 40
        self.assert_rejected("head_sha")
        self.sha = "aaaaaaa"
        self.assert_rejected("full 40-digit")

    def test_unsuccessful_or_incomplete_run(self) -> None:
        for conclusion in ("failure", "cancelled", "skipped", None):
            with self.subTest(conclusion=conclusion):
                self.run["conclusion"] = conclusion
                self.assert_rejected("completed/success")
        self.run.update(status="in_progress", conclusion="success")
        self.assert_rejected("completed/success")

    def test_zero_jobs(self) -> None:
        self.jobs = {"total_count": 0, "jobs": []}
        self.assert_rejected("empty or missing")

    def test_job_skipped_failed_cancelled_or_incomplete(self) -> None:
        for conclusion in ("skipped", "failure", "cancelled", None):
            with self.subTest(conclusion=conclusion):
                self.jobs["jobs"][0]["conclusion"] = conclusion
                self.assert_rejected("must actually complete")

    def test_required_job_missing(self) -> None:
        self.jobs["jobs"][0]["name"] = "Lint only"
        self.assert_rejected("required job missing")
        code, output = self.invoke(["--required-job", "Lint only"])
        self.assertEqual(code, 0, output)

    def test_job_provenance_and_pagination(self) -> None:
        job = self.jobs["jobs"][0]
        for field, wrong in (("run_id", 999), ("head_sha", "b" * 40), ("run_attempt", 2)):
            with self.subTest(field=field):
                previous = job[field]
                job[field] = wrong
                self.assert_rejected(f"{field} mismatch")
                job[field] = previous
        self.jobs["total_count"] = 2
        self.assert_rejected("collect every page")

    def test_missing_execution_or_failed_step(self) -> None:
        job = self.jobs["jobs"][0]
        job["steps"][0]["conclusion"] = "skipped"
        self.assert_rejected("no successful executed step")
        job["steps"].append({"status": "completed", "conclusion": "success"})
        job["steps"][0]["conclusion"] = "failure"
        self.assert_rejected("failed/cancelled/incomplete step")
        job["steps"] = []
        self.assert_rejected("missing execution steps")

    def test_expired_artifact(self) -> None:
        artifact = self.artifacts["artifacts"][0]
        artifact["expired"] = True
        self.assert_rejected("expired")
        artifact["expired"] = False
        artifact["expires_at"] = "2000-01-01T00:00:00Z"
        self.assert_rejected("expiration time has passed")

    def test_artifact_missing_empty_or_not_apk(self) -> None:
        artifact = self.artifacts["artifacts"][0]
        artifact["size_in_bytes"] = 0
        self.assert_rejected("size_in_bytes")
        artifact["size_in_bytes"] = True
        self.assert_rejected("size_in_bytes")
        artifact["name"] = "junit-reports"
        self.assert_rejected("no downloadable")
        self.artifacts = {"total_count": 0, "artifacts": []}
        self.assert_rejected("empty or missing")

    def test_artifact_wrong_provenance_or_download_url(self) -> None:
        artifact = self.artifacts["artifacts"][0]
        artifact["workflow_run"]["id"] = 999
        self.assert_rejected("run/SHA mismatch")
        artifact["workflow_run"]["id"] = 123
        for url in ("https://example.invalid/file.apk", "https://[invalid"):
            with self.subTest(url=url):
                artifact["archive_download_url"] = url
                self.assert_rejected("invalid GitHub download URL")

    def test_explicit_mixed_apk_artifact_name(self) -> None:
        self.artifacts["artifacts"][0]["name"] = "unit-test-results"
        self.assert_rejected("no downloadable")
        code, output = self.invoke(["--apk-artifact-name", "unit-test-results"])
        self.assertEqual(code, 0, output)
        self.assertIn("APK artifact metadata=1", output)
        self.assert_rejected("no downloadable", ["--apk-artifact-name", "different-name"])

    def test_zero_tests_and_counter_only_fabrication(self) -> None:
        for count in (0, 50):
            with self.subTest(count=count):
                self.xml.write_text(f'<testsuite tests="{count}" failures="0" errors="0" skipped="0"/>', encoding="utf-8")
                self.assert_rejected("zero actual testcases", ["--junit-dir", str(self.junit)])

    def test_xml_failure_error_and_skipped(self) -> None:
        for kind in ("failure", "error", "skipped"):
            with self.subTest(kind=kind):
                self.xml.write_text(f'<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase><{kind}/></testcase></testsuite>', encoding="utf-8")
                self.assert_rejected("failure/error/skipped", ["--junit-dir", str(self.junit)])

    def test_xml_counter_mismatch_and_malformed(self) -> None:
        for attrs in ('tests="2" failures="0" errors="0" skipped="0"', 'tests="1" failures="1" errors="0" skipped="0"', 'tests="1" failures="0" errors="0"'):
            with self.subTest(attrs=attrs):
                self.xml.write_text(f'<testsuite {attrs}><testcase/></testsuite>', encoding="utf-8")
                self.assert_rejected("counter", ["--junit-dir", str(self.junit)])
        self.xml.write_text("<broken", encoding="utf-8")
        self.assert_rejected("unreadable XML", ["--junit-dir", str(self.junit)])

    def test_xml_aggregate_not_double_counted(self) -> None:
        self.xml.write_text('<testsuites tests="1"><testsuite tests="1" failures="0" errors="0" skipped="0"><testcase/></testsuite></testsuites>', encoding="utf-8")
        code, output = self.invoke(["--junit-dir", str(self.junit)])
        self.assertEqual(code, 0, output)
        self.assertIn("JUnit tests=1", output)

    def test_warning_error_case_boundaries_and_compact_diagnostics(self) -> None:
        for line in ("Warning: deprecated", "ERROR: failed", "2 warnings", "1.0 errors", "w: file.kt deprecated", "e: file.kt unresolved", "\x1b[33mWARNING\x1b[0m: bad"):
            with self.subTest(line=line):
                self.log.write_text(line, encoding="utf-8")
                self.assert_rejected("warning/error diagnostic", ["--require-clean-log", "--build-log", str(self.log)])

    def test_zero_counts_and_non_words_are_not_errors(self) -> None:
        self.log.write_text("0 errors; 0 WARNINGS\nerrorless warningCount errorsCount\nBUILD SUCCESSFUL\n", encoding="utf-8")
        code, output = self.invoke(["--require-clean-log", "--build-log", str(self.log)])
        self.assertEqual(code, 0, output)

    def test_missing_or_empty_logs(self) -> None:
        self.assert_rejected("needs at least one", ["--require-clean-log"])
        self.log.write_text("", encoding="utf-8")
        self.assert_rejected("empty log", ["--require-clean-log", "--build-log", str(self.log)])
        self.assert_rejected("requires --require-clean-log", ["--build-log", str(self.log)])

    def test_missing_json_and_junit_reports(self) -> None:
        code, output = self.invoke(["--run-json", str(self.root / "missing.json")])
        self.assertEqual(code, 1, output)
        self.assertIn("cannot read JSON", output)
        self.assert_rejected("directory missing", ["--junit-dir", str(self.root / "absent")])
        empty = self.root / "empty"
        empty.mkdir()
        self.assert_rejected("no XML reports", ["--junit-dir", str(empty)])


if __name__ == "__main__":
    unittest.main()
