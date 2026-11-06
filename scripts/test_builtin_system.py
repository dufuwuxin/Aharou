#!/usr/bin/env python3
"""Static asset contracts only: not model-trigger, runtime, or effectiveness tests."""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
SKILLS = (
    "config-channel",
    "dashboard-panel-script",
    "security-audit",
    "third-party-app",
    "root-cause-debugging",
    "delivery-verification",
)
NEW_SKILLS = SKILLS[-2:]
AGENTS = {
    "decision-advisor": "决策顾问",
    "design-ui-designer": "UI Designer",
    "design-ui-finish-gate-reviewer": "UI Finish-Gate Reviewer",
    "design-ux-architect": "UX Architect",
    "docs-writer": "Docs Writer",
    "engineering-ai-engineer": "AI Engineer",
    "engineering-android-kotlin-engineer": "Android Kotlin Engineer",
    "engineering-code-reviewer": "Code Reviewer",
    "engineering-data-engineer": "Data Engineer",
    "engineering-devops-automator": "DevOps Automator",
    "engineering-minimal-change-engineer": "Minimal Change Engineer",
    "engineering-mobile-release-engineer": "Mobile Release Engineer",
    "engineering-python-backend-engineer": "Python Backend Engineer",
    "engineering-rust-engineer": "Rust Engineer",
    "engineering-software-architect": "Software Architect",
    "explore": "Explore",
    "migration-checker": "Migration Checker",
    "product-manager": "Product Manager",
    "product-trend-researcher": "Trend Researcher",
    "security-appsec-engineer": "Application Security Engineer",
    "test-writer": "Test Writer",
    "testing-accessibility-auditor": "Accessibility Auditor",
    "testing-performance-benchmarker": "Performance Benchmarker",
    "testing-reality-checker": "Reality Checker",
}
TEAMS = {
    "feature-delivery": (
        "功能交付团队",
        ("Software Architect", "Android Kotlin Engineer", "Rust Engineer",
         "Python Backend Engineer", "Code Reviewer", "Reality Checker"),
    ),
    "research-review": (
        "调研评审团队", ("Explore", "Reality Checker", "Docs Writer"),
    ),
    "ui-delivery": (
        "界面交付团队",
        ("UI Designer", "UX Architect", "Android Kotlin Engineer",
         "UI Finish-Gate Reviewer", "Accessibility Auditor"),
    ),
}


def split_frontmatter(text: str) -> tuple[str, str]:
    """Read the delimited asset header, not occurrences in Markdown prose."""
    lines = text.splitlines()
    if not lines or lines[0] != "---":
        raise ValueError("missing opening frontmatter delimiter")
    try:
        end = lines.index("---", 1)
    except ValueError as exc:
        raise ValueError("missing closing frontmatter delimiter") from exc
    return "\n".join(lines[1:end]), "\n".join(lines[end + 1:])


def scalar(raw: str) -> str:
    """The built-in headers use single-line plain or quoted YAML scalars."""
    value = raw.strip()
    if not value or value[0] in "|>[{&*!":
        raise ValueError(f"unsupported or empty scalar: {raw!r}")
    if value[0] in "\"'":
        if len(value) < 2 or value[-1] != value[0]:
            raise ValueError("unclosed scalar quote")
        value = value[1:-1]
    if not value.strip() or value.lower() in {"null", "~"}:
        raise ValueError("empty or null scalar")
    return value


def top_fields(header: str) -> dict[str, str]:
    fields = {}
    for line in header.splitlines():
        match = re.fullmatch(r"([a-zA-Z_][\w-]*):\s*(.*)", line)
        if match:
            key, value = match.groups()
            if key in fields:
                raise ValueError(f"duplicate metadata key: {key}")
            fields[key] = value
    return fields


def skill_metadata(text: str) -> tuple[dict[str, str], str]:
    header, body = split_frontmatter(text)
    fields = top_fields(header)
    if set(fields) != {"name", "description"} or len(header.splitlines()) != 2:
        raise ValueError("skill metadata must contain only name and description")
    return {key: scalar(value) for key, value in fields.items()}, body


class BuiltinSystemTest(unittest.TestCase):
    def test_six_skill_entrypoints_have_valid_unique_metadata(self):
        names = []
        for entry in SKILLS:
            with self.subTest(skill=entry):
                path = ASSETS / "skills" / entry / "SKILL.md"
                self.assertTrue(path.is_file(), f"missing built-in skill: {entry}")
                metadata, body = skill_metadata(path.read_text(encoding="utf-8"))
                self.assertEqual(metadata["name"], entry)
                self.assertRegex(metadata["name"], r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
                self.assertLessEqual(len(metadata["description"]), 1024)
                self.assertRegex(metadata["description"], r"[\u4e00-\u9fff]")
                self.assertTrue(body.strip())
                self.assertLessEqual(len(body.splitlines()), 200)
                names.append(metadata["name"])
        self.assertEqual(len(names), 6)
        self.assertEqual(len(names), len(set(names)))

    def test_24_agent_ids_and_names_are_stable(self):
        self.assertEqual(len(AGENTS), 24)
        self.assertEqual({p.stem for p in (ASSETS / "agents").glob("*.md")}, set(AGENTS))
        names = []
        for entry, expected in AGENTS.items():
            with self.subTest(agent=entry):
                header, _ = split_frontmatter(
                    (ASSETS / "agents" / f"{entry}.md").read_text(encoding="utf-8")
                )
                fields = top_fields(header)
                name = scalar(fields["name"])
                self.assertEqual(name, expected)
                self.assertTrue(scalar(fields["description"]))
                names.append(name)
        self.assertEqual(len(set(names)), 24)

    def test_three_team_ids_names_and_resolved_references_are_stable(self):
        self.assertEqual({p.stem for p in (ASSETS / "teams").glob("*.md")}, set(TEAMS))
        names = []
        for entry, (expected_name, expected_refs) in TEAMS.items():
            with self.subTest(team=entry):
                header, _ = split_frontmatter(
                    (ASSETS / "teams" / f"{entry}.md").read_text(encoding="utf-8")
                )
                fields = top_fields(header)
                name = scalar(fields["name"])
                self.assertEqual(name, expected_name)
                self.assertTrue(scalar(fields["description"]))
                self.assertIn("stages", fields)
                refs = tuple(scalar(match.group(1)) for match in re.finditer(
                    r"^ +(?:- +)?agent: *(.+)$", header, re.M
                ))
                self.assertEqual(refs, expected_refs)
                self.assertTrue(set(refs).issubset(AGENTS.values()))
                selectors = re.findall(
                    r"^([ ]+)- department: *([^\n]+)\n\1  agentQuery: *([^\n]+)$",
                    header, re.M,
                )
                self.assertEqual(
                    [(scalar(dept), scalar(query)) for _, dept, query in selectors],
                    [("product", "trend research")] if entry == "research-review" else [],
                )
                selector_lines = re.findall(
                    r"^ +(?:- +)?(?:department|agentQuery):", header, re.M
                )
                self.assertEqual(len(selector_lines), 2 * len(selectors))
                names.append(name)
        self.assertEqual(len(set(names)), 3)

    def test_new_skills_are_bounded_self_contained_documents(self):
        for entry in NEW_SKILLS:
            with self.subTest(skill=entry):
                text = (ASSETS / "skills" / entry / "SKILL.md").read_text(encoding="utf-8")
                _, body = skill_metadata(text)
                self.assertLessEqual(len(text.splitlines()), 120)
                # These skills contain guidance, not executable/resource dependencies.
                self.assertNotRegex(text, r"(?i)(?:~[/\\]|/(?:root|home|data|tmp)/|\.aharou|\.aicode)")
                self.assertNotRegex(text, r"(?i)\b(?:scripts|references|skills)/|\b[\w.-]+\.(?:py|sh)\b")
                self.assertNotRegex(text, r"!?\[[^\]\n]*\]\([^\n)]+\)|^\s*\[[^\]]+\]:")
                self.assertNotRegex(text, r"(?i)\b(?:loadSkill|loadRule|mcp[._:/-]\w+)\b")
                self.assertRegex(body, r"(?m)^## ")

    def test_frontmatter_rejects_missing_delimiters(self):
        for text in ("name: example", "---\nname: example"):
            with self.subTest(text=text), self.assertRaises(ValueError):
                split_frontmatter(text)

    def test_skill_metadata_rejects_duplicates_and_extra_fields(self):
        for header in (
            "name: x\nname: y\ndescription: example",
            "name: x\ndescription: example\ntools: anything",
            "name: x\ndescription: |\n  hidden dependency",
        ):
            with self.subTest(header=header), self.assertRaises(ValueError):
                skill_metadata(f"---\n{header}\n---\nBody")

    def test_metadata_does_not_read_values_from_body(self):
        with self.assertRaises(ValueError):
            skill_metadata("---\nname: x\n---\ndescription: not metadata")

    def test_scalar_validates_quotes_and_nulls(self):
        self.assertEqual(scalar('"中文说明"'), "中文说明")
        self.assertEqual(scalar("'plain text'"), "plain text")
        for value in ("", "null", "~", '"unfinished', "{}", "|"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                scalar(value)


if __name__ == "__main__":
    unittest.main()
