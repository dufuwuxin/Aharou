"""Run disjoint app test domains on the existing, shared Android test variant."""

import argparse
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GROUPS = (
    "agent", "backup", "browser", "credentials", "editor", "git", "localmodel",
    "onboarding", "pet", "plugin", "sandbox", "settings", "terminal", "vault",
    "workspace", "core",
)
RESULTS = Path("app/build/test-results/testArmsoloDebugUnitTest")
ARCHIVE = Path("app/build/test-results/ciGroups")


def discover(root: Path) -> dict[str, list[str]]:
    groups: dict[str, list[str]] = {group: [] for group in GROUPS}
    for path in sorted((root / "app/src/test").rglob("*")):
        if path.suffix not in {".kt", ".java"}:
            continue
        text = path.read_text(encoding="utf-8")
        if not re.search(r"@(?:org\.junit\.)?Test\b", text):
            continue
        package = re.search(r"^package\s+([\w.]+)", text, re.MULTILINE)
        if package is None:
            raise ValueError(f"Unrecognized test package: {path}")
        declarations = list(re.finditer(r"^(?:(?:public|internal|open|final)\s+)*class\s+(\w+)\b", text, re.MULTILINE))
        selected = []
        for index, declaration in enumerate(declarations):
            end = declarations[index + 1].start() if index + 1 < len(declarations) else len(text)
            if re.search(r"@(?:org\.junit\.)?Test\b", text[declaration.end():end]):
                selected.append(declaration[1])
        if not selected:
            raise ValueError(f"Unrecognized test class: {path}")
        parts = package[1].split(".")
        group = parts[3] if parts[:3] == ["com", "aharou", "feature"] else "core"
        if group not in groups:
            raise ValueError(f"New test domain needs a visible CI step: {group}")
        for declared in selected:
            name = f"{package[1]}.{declared}"
            if any(name in members for members in groups.values()):
                raise ValueError(f"Duplicate test class: {name}")
            groups[group].append(name)
    if not any(groups.values()):
        raise ValueError("No app test classes discovered")
    return groups


def report_classes(directory: Path) -> set[str]:
    classes: set[str] = set()
    cases: set[tuple[str, str]] = set()
    files = list(directory.glob("TEST-*.xml"))
    if not files:
        raise ValueError(f"No test reports: {directory}")
    for path in files:
        suite = ET.parse(path).getroot()
        for case in suite.iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                raise ValueError(f"Failed test: {path.name}: {case.get('name')}")
            if case.find("skipped") is not None:
                raise ValueError(f"Skipped test: {path.name}: {case.get('name')}")
            name = case.get("classname", "").split("$")[0]
            identity = (name, case.get("name", ""))
            if identity in cases:
                raise ValueError(f"Duplicate test execution: {identity}")
            cases.add(identity)
            # JUnit parameterized runners may append a parameter display name.
            classes.add(name.split("[")[0])
    if not classes or "" in classes:
        raise ValueError(f"Zero executed tests or missing class names: {directory}")
    return classes


def verify_report(directory: Path, expected: list[str]) -> set[str]:
    actual = report_classes(directory)
    if actual != set(expected):
        raise ValueError(
            f"Test coverage mismatch in {directory}: "
            f"missing={sorted(set(expected) - actual)}, extra={sorted(actual - set(expected))}"
        )
    return actual


def run_group(root: Path, group: str, members: list[str]) -> int:
    if not members:
        raise ValueError(f"Empty test domain: {group}")
    reports = root / RESULTS
    archive = root / ARCHIVE / group
    if archive.exists():
        for path in archive.glob("TEST-*.xml"):
            path.unlink()
    # A cached Gradle result is fine, but a stale report must not conceal a failed run.
    if reports.exists():
        for path in reports.glob("TEST-*.xml"):
            path.unlink()
    command = ["./gradlew", ":app:testArmsoloDebugUnitTest", "--console=plain"]
    for name in members:
        command.extend(["--tests", name])
    result = subprocess.run(command, cwd=root, check=False)
    archive.mkdir(parents=True, exist_ok=True)
    for path in reports.glob("TEST-*.xml"):
        shutil.copy2(path, archive / path.name)
    if result.returncode:
        return result.returncode
    verify_report(archive, members)
    print(f"{group}: {len(members)} test classes verified", flush=True)
    return 0


def verify_all(root: Path, groups: dict[str, list[str]]) -> None:
    seen: set[str] = set()
    for group, expected in groups.items():
        if not expected:
            raise ValueError(f"Empty test domain: {group}")
        actual = verify_report(root / ARCHIVE / group, expected)
        if seen & actual:
            raise ValueError(f"Tests executed in multiple groups: {sorted(seen & actual)}")
        seen.update(actual)
    combined = root / RESULTS
    combined.mkdir(parents=True, exist_ok=True)
    for group in groups:
        directory = root / ARCHIVE / group
        for report in directory.glob("TEST-*.xml"):
            shutil.copy2(report, combined / report.name)
    print(f"Complete app regression: {len(seen)} classes in {len(groups)} disjoint domains")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--group", choices=GROUPS)
    parser.add_argument("--verify-all", action="store_true")
    args = parser.parse_args()
    groups = discover(ROOT)
    if args.verify_all:
        verify_all(ROOT, groups)
        return 0
    if args.group:
        return run_group(ROOT, args.group, groups[args.group])
    for group, members in groups.items():
        print(f"{group}: {len(members)} classes")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
