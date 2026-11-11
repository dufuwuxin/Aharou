#!/usr/bin/env python3
"""Validate saved GitHub REST run/jobs/artifacts evidence without network access.

Required inputs are unmodified REST objects (all pages merged into one jobs or
artifacts object, with total_count retained). The default required job is the
repository's CI job, "Build & Test"; --required-job overrides it (repeatable).
Use --require-clean-log --build-log PATH to require diagnostic-free build logs.
JUnit XML and logs must be extracted from this run by the caller: local files
alone cannot establish their provenance. Artifact checks validate download
metadata, not HTTP availability, APK contents/signatures or installed behavior.
For CI's mixed unit-test-results ZIP, pass --apk-artifact-name unit-test-results;
the caller must independently verify that its APK entry is actually present.
"""

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse


class EvidenceError(ValueError):
    """Missing, inconsistent or unsuccessful evidence."""


def require(condition: bool, message: str) -> None:
    if not condition:
        raise EvidenceError(message)


def positive_int(value: object) -> bool:
    return type(value) is int and value > 0


def read_object(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, ValueError) as exc:
        raise EvidenceError(f"{path.name}: cannot read JSON ({type(exc).__name__})") from exc
    require(isinstance(value, dict), f"{path.name}: expected a REST JSON object")
    return value


def timestamp(value: object, label: str) -> datetime:
    require(isinstance(value, str), f"{label}: missing ISO timestamp")
    try:
        result = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise EvidenceError(f"{label}: invalid ISO timestamp") from exc
    require(result.tzinfo is not None, f"{label}: timezone required")
    return result


def collection(document: dict, key: str) -> list[dict]:
    rows = document.get(key)
    require(isinstance(rows, list) and bool(rows), f"{key}: empty or missing collection")
    require(all(isinstance(row, dict) for row in rows), f"{key}: entries must be objects")
    total = document.get("total_count")
    require(type(total) is int and total == len(rows), f"{key}: total_count mismatch; collect every page")
    ids = [row.get("id") for row in rows]
    require(all(positive_int(item) for item in ids), f"{key}: missing positive IDs")
    require(len(set(ids)) == len(ids), f"{key}: duplicate IDs")
    return rows


def check_run(run: dict, expected_sha: str) -> None:
    require(re.fullmatch(r"[0-9a-fA-F]{40}", expected_sha) is not None, "expected-sha: full 40-digit SHA required")
    require(run.get("head_sha") == expected_sha, "run: head_sha does not match expected-sha")
    require(positive_int(run.get("id")), "run: missing positive id")
    require(positive_int(run.get("run_attempt")), "run: missing positive run_attempt")
    require(run.get("status") == "completed" and run.get("conclusion") == "success", "run: must be completed/success")


def check_jobs(document: dict, run: dict, required_jobs: list[str]) -> int:
    jobs = collection(document, "jobs")
    names = [job.get("name") for job in jobs]
    for name in required_jobs:
        require(names.count(name) == 1, f"jobs: required job missing or duplicated: {name}")
    for job in jobs:
        label = f"job {job['id']}"
        require(job.get("run_id") == run["id"], f"{label}: run_id mismatch")
        require(job.get("head_sha") == run["head_sha"], f"{label}: head_sha mismatch")
        require(job.get("run_attempt") == run["run_attempt"], f"{label}: run_attempt mismatch")
        require(job.get("status") == "completed" and job.get("conclusion") == "success", f"{label}: must actually complete successfully (not skipped/cancelled)")
        start = timestamp(job.get("started_at"), f"{label} started_at")
        end = timestamp(job.get("completed_at"), f"{label} completed_at")
        require(end >= start, f"{label}: completion precedes start")
        steps = job.get("steps")
        require(isinstance(steps, list) and steps and all(isinstance(step, dict) for step in steps), f"{label}: missing execution steps")
        require(any(step.get("status") == "completed" and step.get("conclusion") == "success" for step in steps), f"{label}: no successful executed step")
        require(all(step.get("status") == "completed" and step.get("conclusion") in ("success", "skipped") for step in steps), f"{label}: failed/cancelled/incomplete step")
    return len(jobs)


def check_artifacts(document: dict, run: dict, apk_artifact_name: str | None = None) -> int:
    artifacts = collection(document, "artifacts")
    apk_count = 0
    for artifact in artifacts:
        name = artifact.get("name")
        if apk_artifact_name is not None:
            selected = name == apk_artifact_name
        else:
            selected = isinstance(name, str) and re.search(r"(?:^|[^a-z0-9])apk(?:$|[^a-z0-9])", name, re.I) is not None
        if not selected:
            continue
        label = f"APK artifact {artifact['id']}"
        provenance = artifact.get("workflow_run")
        require(isinstance(provenance, dict), f"{label}: missing workflow_run provenance")
        require(provenance.get("id") == run["id"] and provenance.get("head_sha") == run["head_sha"], f"{label}: run/SHA mismatch")
        require(artifact.get("expired") is False, f"{label}: expired or missing expiration status")
        expires = timestamp(artifact.get("expires_at"), f"{label} expires_at")
        require(expires > datetime.now(timezone.utc), f"{label}: expiration time has passed")
        require(positive_int(artifact.get("size_in_bytes")), f"{label}: size_in_bytes must be > 0")
        url = artifact.get("archive_download_url")
        require(isinstance(url, str), f"{label}: missing download URL")
        try:
            parsed = urlparse(url)
        except ValueError as exc:
            raise EvidenceError(f"{label}: invalid GitHub download URL") from exc
        require(parsed.scheme == "https" and parsed.hostname == "api.github.com" and parsed.username is None and parsed.password is None and parsed.path.endswith(f"/actions/artifacts/{artifact['id']}/zip"), f"{label}: invalid GitHub download URL")
        apk_count += 1
    require(apk_count > 0, "artifacts: no downloadable non-expired APK artifact")
    return apk_count


def tag(element: ET.Element) -> str:
    return element.tag.rsplit("}", 1)[-1]


def check_junit(directory: Path) -> int:
    require(directory.is_dir(), "junit: directory missing")
    files = sorted(directory.rglob("*.xml"))
    require(bool(files), "junit: no XML reports")
    total = 0
    for path in files:
        try:
            root = ET.parse(path).getroot()
        except (OSError, ET.ParseError) as exc:
            raise EvidenceError(f"junit {path.name}: unreadable XML ({type(exc).__name__})") from exc
        require(tag(root) in {"testsuite", "testsuites"}, f"junit {path.name}: unexpected root")
        cases = [element for element in root.iter() if tag(element) == "testcase"]
        require(bool(cases), f"junit {path.name}: zero actual testcases")
        require(not any(tag(element) in {"failure", "error", "skipped"} for element in root.iter()), f"junit {path.name}: failure/error/skipped element")
        for suite in root.iter():
            if tag(suite) not in {"testsuite", "testsuites"}:
                continue
            actual = sum(tag(element) == "testcase" for element in suite.iter())
            for attribute in ("tests", "failures", "errors", "skipped"):
                value = suite.get(attribute)
                # Aggregate testsuites wrappers may omit counters; real suites may not.
                if value is None and tag(suite) == "testsuites":
                    continue
                require(value is not None and re.fullmatch(r"[0-9]+", value) is not None, f"junit {path.name}: missing/invalid {attribute} counter")
                expected = actual if attribute == "tests" else 0
                require(int(value) == expected, f"junit {path.name}: {attribute} counter mismatch (expected {expected})")
        total += len(cases)
    return total


DIAGNOSTIC = re.compile(r"\b(?:warnings?|errors?)\b", re.I)
ZERO_COUNT = re.compile(r"(?<![\w.+-])0[ \t]+(?:warnings?|errors?)\b", re.I)
ANSI = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")


def check_logs(paths: list[Path]) -> None:
    require(bool(paths), "logs: --require-clean-log needs at least one --build-log")
    for path in paths:
        try:
            with path.open(encoding="utf-8") as stream:
                nonempty = False
                for number, line in enumerate(stream, 1):
                    nonempty = nonempty or bool(line.strip())
                    text = ANSI.sub("", line)
                    text = ZERO_COUNT.sub("", text)
                    # Gradle/Kotlin also emit compact `w:` and `e:` diagnostics.
                    found = DIAGNOSTIC.search(text) or re.search(r"(?:^|\s)[we]:\s", text, re.I)
                    require(found is None, f"logs {path.name}:{number}: warning/error diagnostic")
                require(nonempty, f"logs {path.name}: empty log")
        except (OSError, UnicodeError) as exc:
            raise EvidenceError(f"logs {path.name}: unreadable ({type(exc).__name__})") from exc


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--expected-sha", required=True)
    parser.add_argument("--run-json", type=Path, required=True)
    parser.add_argument("--jobs-json", type=Path, required=True)
    parser.add_argument("--artifacts-json", type=Path, required=True)
    parser.add_argument("--required-job", action="append", help="Exact job name; repeat to require multiple jobs")
    parser.add_argument("--apk-artifact-name", help="Exact APK-containing artifact name, for mixed ZIPs without apk in their name; contents need independent verification")
    parser.add_argument("--junit-dir", type=Path)
    parser.add_argument("--require-clean-log", action="store_true", help="Reject case-insensitive warning/error words (except 0 counts), w:/e: diagnostics")
    parser.add_argument("--build-log", action="append", type=Path, default=[], help="Saved build log; repeatable; checked with --require-clean-log")
    args = parser.parse_args(argv)
    try:
        run = read_object(args.run_json)
        check_run(run, args.expected_sha)
        jobs = check_jobs(read_object(args.jobs_json), run, args.required_job or ["Build & Test"])
        apks = check_artifacts(read_object(args.artifacts_json), run, args.apk_artifact_name)
        tests = check_junit(args.junit_dir) if args.junit_dir is not None else None
        require(not args.build_log or args.require_clean_log, "logs: --build-log requires --require-clean-log")
        if args.require_clean_log:
            check_logs(args.build_log)
    except EvidenceError as exc:
        print(f"[FAIL] {exc}", file=sys.stderr)
        return 1
    print(f"[OK] run/SHA verified; jobs={jobs}; APK artifact metadata={apks}; JUnit tests={tests if tests is not None else 'not checked'}; logs={'clean' if args.require_clean_log else 'not checked'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
