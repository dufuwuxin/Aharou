"""Source contracts for the retired Beta pipeline and retained release gates."""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = ROOT / ".github/workflows"


def beta_builds(source: str) -> bool:
    active = "\n".join(line for line in source.splitlines() if not line.lstrip().startswith("#"))
    return bool(re.search(r"assemble\w*Beta|gh\s+release\s+\w+\s+beta-latest|Aharou-beta-", active))


class FormalReleaseOnlyTest(unittest.TestCase):
    def test_beta_workflow_is_retired(self) -> None:
        self.assertFalse((WORKFLOWS / "beta.yml").exists())
        for path in WORKFLOWS.glob("*.yml"):
            with self.subTest(workflow=path.name):
                self.assertFalse(beta_builds(path.read_text(encoding="utf-8")))

    def test_ci_keeps_debug_build_and_unit_tests(self) -> None:
        source = (WORKFLOWS / "ci.yml").read_text(encoding="utf-8")
        self.assertIn(":app:assembleArmsoloDebug", source)
        self.assertIn(":app:testArmsoloDebugUnitTest", source)
        for check in ("check_migrations.py", "check_skills.py", "check_architecture.py"):
            self.assertIn(check, source)

    def test_release_keeps_master_ancestry_signing_and_tests(self) -> None:
        source = (WORKFLOWS / "android-release.yml").read_text(encoding="utf-8")
        for gate in ("git merge-base --is-ancestor", "origin/master", "Restore release keystore",
                     ":app:testArmsoloDebugUnitTest", ":app:assembleArmsoloRelease",
                     "softprops/action-gh-release@"):
            self.assertIn(gate, source)

    def test_detector_rejects_reintroduced_beta_build_or_release(self) -> None:
        for source in ("run: ./gradlew :app:assembleArmsoloBeta", "run: gh release create beta-latest",
                       "name: Aharou-beta-${{ github.sha }}"):
            self.assertTrue(beta_builds(source), source)
        for source in ("run: ./gradlew :app:assembleArmsoloRelease", "# assembleArmsoloBeta retired"):
            self.assertFalse(beta_builds(source), source)


if __name__ == "__main__":
    unittest.main()
