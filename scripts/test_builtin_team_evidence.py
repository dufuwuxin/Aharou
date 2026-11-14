#!/usr/bin/env python3
"""Controlled fixtures, not an AI verdict or proof of a live team run. No Gradle/network."""
import copy
import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
KEYS = {"schema_version", "status", "acceptance_id", "baseline", "evidence_refs", "failures", "unverified"}
TEAMS = {"feature-delivery": (4, 6, 2), "research-review": (3, 4, 1)}


def nonblank(value: object) -> bool:
    return isinstance(value, str) and bool(value.strip())


def check_delivery(text: str, acceptance_id: str, baseline: dict, fixture_root: Path) -> dict:
    """Validate controlled records against immutable fixture inputs and actual fixture bytes.

    Never execute commands from model output. Hash equality proves identity, not truth.
    """
    lines = [line for line in text.splitlines() if line.startswith("TEAM_EVIDENCE:")]
    if len(lines) != 1:
        raise ValueError("one evidence record required")
    record = json.loads(lines[0].removeprefix("TEAM_EVIDENCE:").strip())
    if not isinstance(record, dict) or not KEYS <= record.keys():
        raise ValueError("missing fields")
    if type(record["schema_version"]) is not int or record["schema_version"] != 1:
        raise ValueError("schema version")
    if record["acceptance_id"] != acceptance_id or record["baseline"] != baseline:
        raise ValueError("changed acceptance or baseline")
    if record["status"] not in {"done", "blocked", "unverified"}:
        raise ValueError("status")
    refs, failures, gaps = (record[k] for k in ("evidence_refs", "failures", "unverified"))
    if not isinstance(refs, list) or not refs or not isinstance(failures, list) or not isinstance(gaps, list):
        raise ValueError("evidence arrays")
    if any(not nonblank(gap) for gap in gaps):
        raise ValueError("empty gap")
    ids, failed = set(), set()
    for ref in refs:
        if not isinstance(ref, dict) or any(not nonblank(ref.get(k)) for k in ("id", "ref", "sha256", "observation")):
            raise ValueError("missing evidence ref")
        if ref["id"] in ids or ref.get("kind") not in {"command", "source"}:
            raise ValueError("duplicate id or kind")
        ids.add(ref["id"])
        path_text, separator, line = ref["ref"].rpartition(":")
        if not separator or not line.isdigit() or int(line) < 1:
            raise ValueError("missing line locator")
        path = (fixture_root / path_text).resolve()
        if not path.is_relative_to(fixture_root.resolve()) or not path.is_file():
            raise ValueError("unresolved evidence ref")
        data = path.read_bytes()
        if int(line) > len(data.splitlines()) or hashlib.sha256(data).hexdigest() != ref["sha256"]:
            raise ValueError("evidence bytes or locator mismatch")
        if ref["kind"] == "command":
            if not nonblank(ref.get("command")) or type(ref.get("exit_code")) is not int:
                raise ValueError("missing actual command result")
            if ref["exit_code"] != 0:
                failed.add(ref["id"])
    covered = set()
    for failure in failures:
        if not isinstance(failure, dict) or any(not nonblank(failure.get(k)) for k in ("evidence_id", "root_cause", "minimal_fix")):
            raise ValueError("missing failure evidence or diagnosis")
        if failure["evidence_id"] not in ids:
            raise ValueError("unresolved failure evidence")
        covered.add(failure["evidence_id"])
    if not failed <= covered:
        raise ValueError("hidden command failure")
    if record["status"] == "done" and (failures or gaps):
        raise ValueError("false done with failures or gaps")
    if record["status"] != "done" and not (failures or gaps):
        raise ValueError("missing blocked or unverified reason")
    return record


def run_real_parser() -> None:
    """Compile real parser/model/flow sources; stub only Android-facing I/O/logging/scope."""
    cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
    dependencies = [
        "org.jetbrains.kotlin/kotlin-compiler-embeddable/2.3.21",
        "org.jetbrains.kotlin/kotlin-stdlib/2.3.21",
        "org.jetbrains.kotlin/kotlin-script-runtime/2.3.21",
        "org.jetbrains.kotlin/kotlin-reflect/1.6.10",
        "org.jetbrains.kotlin/kotlin-daemon-embeddable/2.3.21",
        "org.jetbrains/annotations/13.0",
        "org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.10.2",
        "org.yaml/snakeyaml/2.2",
    ]
    jars = []
    for dependency in dependencies:
        matches = sorted((cache / dependency).glob("*/*.jar"))
        if not matches:
            raise RuntimeError(f"unverified: missing cached JVM dependency {dependency}")
        jars.append(str(matches[0]))
    cp = os.pathsep.join(jars)
    main = ROOT / "app/src/main/java/com/aharou/feature/agent/domain"
    files = [main / "subagent" / name for name in ("AgentTeam.kt", "AgentTeamParser.kt", "TeamFlowGraph.kt")]
    files.append(main / "common/Frontmatter.kt")
    definitions = (main / "subagent/AgentDefinitionParser.kt").read_text()
    start = definitions.index("    internal fun stringList(")
    end = definitions.index("\n    }", start) + len("\n    }")
    real_helper = definitions[start:end]
    stubs = {
        "RealListHelper.kt": "package com.aharou.feature.agent.domain.subagent\nobject AgentDefinitionParser {\n" + real_helper + "\n}\n",
        "Logger.kt": """package com.aharou.core.util
object FileLogger { fun w(tag: String, message: String, error: Throwable? = null) {} }
""",
        "Provider.kt": """package com.aharou.feature.workspace.domain
interface FileAccessProvider { fun readFile(path: String): String }
""",
        "Scope.kt": """package com.aharou.feature.agent.domain.subagent
enum class AgentDefinitionScope { GLOBAL, PROJECT }
""",
        "Fixture.kt": '''import com.aharou.feature.agent.domain.subagent.*
import com.aharou.feature.workspace.domain.FileAccessProvider
fun main(args: Array<String>) {
 val provider = object: FileAccessProvider {
  override fun readFile(path: String) = java.io.File(path).readText()
 }
 val expected = listOf(Triple("feature-delivery", 4, 6), Triple("research-review", 3, 4))
 expected.forEach { (name, stages, tasks) ->
  val team = checkNotNull(AgentTeamParser.parse(provider, args[0] + "/teams/" + name + ".md"))
  check(team.stages.size == stages && team.taskCount == tasks)
  check(team.stages.count { it.isReview } == if (name == "feature-delivery") 2 else 1)
  check(team.charter.contains("TEAM_EVIDENCE:") && team.charter.contains("unverified"))
  check(team.admins.size == 1 && team.admins.all { admin -> team.members.any { it.name == admin } })
  check(team.stages.filter { it.isReview }.all { stage -> stage.tasks.all { it.prompt.contains("TEAM_REVIEW: PASS") && it.prompt.contains("TEAM_REVIEW: REJECT") } })
  val roundtrip = AgentTeamParser.serialize(team.toForm(AgentDefinitionScope.GLOBAL))
  val memory = object: FileAccessProvider { override fun readFile(path: String) = roundtrip }
  val again = checkNotNull(AgentTeamParser.parse(memory, "roundtrip.md"))
  check(again.charter == team.charter && again.taskCount == tasks)
  check(again.stages.count { it.isReview } == team.stages.count { it.isReview })
  println("REAL_PARSER_OK " + name + " stages=" + stages + " tasks=" + tasks)
 }
}
''',
    }
    with tempfile.TemporaryDirectory(prefix="aharou-team-evidence-jvm-") as tmp:
        target = Path(tmp)
        for name, content in stubs.items():
            path = target / name
            path.write_text(content, encoding="utf-8")
            files.append(path)
        classes = target / "classes"
        command = ["java", "-cp", cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-nowarn", "-no-stdlib", "-no-reflect", "-classpath", cp, "-d", str(classes), *map(str, files)]
        subprocess.run(command, check=True, timeout=120)
        subprocess.run(["java", "-cp", str(classes) + os.pathsep + cp, "FixtureKt", str(ASSETS)], check=True, timeout=30)


class EvidenceFixtures(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory(prefix="aharou-team-evidence-")
        self.addCleanup(self.tmp.cleanup)
        self.directory = Path(self.tmp.name)
        self.baseline = {"run_id": "controlled-fixture", "source_revision": "fixture-v1"}
        command = [sys.executable, "-c", "print('controlled fixture passed')"]
        result = subprocess.run(command, capture_output=True, check=True, timeout=10)
        raw = self.directory / "output.txt"
        raw.write_bytes(result.stdout)
        self.record = {
            "schema_version": 1, "status": "done", "acceptance_id": "original-criterion",
            "baseline": self.baseline,
            "evidence_refs": [{"id": "check", "kind": "command", "ref": "output.txt:1", "sha256": hashlib.sha256(result.stdout).hexdigest(), "command": " ".join(command), "exit_code": result.returncode, "observation": result.stdout.decode().strip()}],
            "failures": [], "unverified": [],
        }

    def validate(self, record: dict) -> dict:
        return check_delivery("TEAM_EVIDENCE: " + json.dumps(record), "original-criterion", self.baseline, self.directory)

    def failing_record(self) -> dict:
        record = copy.deepcopy(self.record)
        result = subprocess.run([sys.executable, "-c", "import sys; print('controlled failure'); sys.exit(7)"], capture_output=True, timeout=10)
        self.assertEqual(result.returncode, 7)
        (self.directory / "failure.txt").write_bytes(result.stdout)
        record["evidence_refs"][0].update(ref="failure.txt:1", sha256=hashlib.sha256(result.stdout).hexdigest(), command=f"{sys.executable} -c \"import sys; print('controlled failure'); sys.exit(7)\"", exit_code=7, observation=result.stdout.decode().strip())
        return record

    def test_valid_actual_command(self) -> None:
        self.assertEqual(self.validate(self.record)["status"], "done")

    def test_valid_blocked_keeps_failure(self) -> None:
        record = self.failing_record()
        record.update(status="blocked", failures=[{"evidence_id": "check", "root_cause": "fixture intentionally exits 7", "minimal_fix": "report fixture failure; no product patch"}])
        self.assertEqual(self.validate(record)["status"], "blocked")

    def test_unrun_stays_unverified(self) -> None:
        self.record.update(status="unverified", unverified=["live team run not executed"])
        self.assertEqual(self.validate(self.record)["status"], "unverified")
        self.record["status"] = "done"
        with self.assertRaisesRegex(ValueError, "false done"):
            self.validate(self.record)

    def test_negative_hidden_failure(self) -> None:
        with self.assertRaisesRegex(ValueError, "hidden command failure"):
            self.validate(self.failing_record())

    def test_negative_fake_done_missing_ref(self) -> None:
        self.record["evidence_refs"][0]["ref"] = "nonexistent.txt:1"
        with self.assertRaisesRegex(ValueError, "unresolved evidence ref"):
            self.validate(self.record)

    def test_negative_relaxed_acceptance(self) -> None:
        self.record["acceptance_id"] = "easier-criterion"
        with self.assertRaisesRegex(ValueError, "changed acceptance"):
            self.validate(self.record)

    def test_malformed_schema_rejected(self) -> None:
        del self.record["failures"]
        with self.assertRaisesRegex(ValueError, "missing fields"):
            self.validate(self.record)

    def test_asset_contract_keys(self) -> None:
        rule = (ASSETS / "rules/team-evidence.md").read_text(encoding="utf-8")
        for key in KEYS:
            self.assertIn(key, rule)
        for name in TEAMS:
            text = (ASSETS / "teams" / f"{name}.md").read_text(encoding="utf-8")
            for token in (*KEYS, "TEAM_EVIDENCE:", "team-evidence", "TEAM_REVIEW: PASS", "TEAM_REVIEW: REJECT"):
                self.assertIn(token, text, f"{name}: {token}")

    def test_real_agent_team_parser(self) -> None:
        run_real_parser()


if __name__ == "__main__":
    unittest.main(verbosity=2)
