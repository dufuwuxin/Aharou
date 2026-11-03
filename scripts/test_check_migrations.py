import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("check_migrations.py")
CURRENT_DB = "core-storage/src/commonMain/kotlin/com/aharou/feature/agent/data/local/database/AgentDatabase.kt"
LEGACY_DB = "app/src/main/java/com/aharou/feature/agent/data/local/database/AgentDatabase.kt"
MIGRATION = "app/src/main/assets/migrations/80_fixture.sql"


class CheckMigrationsTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / "scripts").mkdir()
        shutil.copyfile(SCRIPT, self.root / "scripts/check_migrations.py")
        self.git("init", "--quiet")
        self.write(MIGRATION, "SELECT 1;\n")

    def git(self, *args, input=None):
        return subprocess.run(
            ["git", "-C", str(self.root), *args],
            input=input, text=True, capture_output=True, check=True,
        ).stdout.strip()

    def write(self, relative, text):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def publish(self, path, version=80, tag="v1.0.0"):
        self.write(path, "const val SCHEMA_VERSION = %d\n" % version)
        self.git("add", path, MIGRATION)
        tree = self.git("write-tree")
        # 在临时仓库构造标签对象，不提交或切换实际工作树。
        commit = self.git(
            "hash-object", "-t", "commit", "-w", "--stdin",
            input=(
                "tree %s\n"
                "author Fixture <fixture@example.invalid> 1700000000 +0000\n"
                "committer Fixture <fixture@example.invalid> 1700000000 +0000\n"
                "\nfixture\n" % tree
            ),
        )
        self.git("update-ref", "refs/tags/" + tag, commit)

    def run_gate(self):
        self.write(CURRENT_DB, "const val SCHEMA_VERSION = 80\n")
        return subprocess.run(
            [sys.executable, str(self.root / "scripts/check_migrations.py")],
            text=True, capture_output=True, check=False,
        )

    def assert_frozen_rejection(self):
        self.write(MIGRATION, "SELECT 2;\n")
        result = self.run_gate()
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("已发布迁移不可修改或复用", result.stdout)

    def test_legacy_tag_unchanged_passes_and_reports_limit(self):
        self.publish(LEGACY_DB)
        result = self.run_gate()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("最大 SCHEMA_VERSION）= 80", result.stdout)
        self.assertNotIn("跳过已发布冻结校验", result.stdout)

    def test_legacy_tag_tampering_fails(self):
        self.publish(LEGACY_DB)
        self.assert_frozen_rejection()

    def test_current_tag_tampering_fails(self):
        self.publish(CURRENT_DB)
        self.assert_frozen_rejection()

    def test_unparseable_current_path_still_checks_legacy_path(self):
        self.write(CURRENT_DB, "// no schema constant here\n")
        self.git("add", CURRENT_DB)
        self.publish(LEGACY_DB)
        self.assert_frozen_rejection()

    def test_mixed_layout_tags_keep_highest_published_schema(self):
        self.publish(LEGACY_DB, tag="v1.0.0")
        self.publish(CURRENT_DB, version=79, tag="v1.1.0")
        result = self.run_gate()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("最大 SCHEMA_VERSION）= 80", result.stdout)

    def test_no_tags_keeps_local_checks(self):
        result = self.run_gate()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("迁移版本号对账通过", result.stdout)


if __name__ == "__main__":
    unittest.main()
