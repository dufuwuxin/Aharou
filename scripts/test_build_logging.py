#!/usr/bin/env python3
"""Run only fake build tools; all temporary files stay inside this worktree."""
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CLI = ROOT / "app/src/main/assets/aharou/bin/aharou-build"


class BuildLoggingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix=".build-fixture-", dir=ROOT)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.work = self.root / "workspace with spaces"
        self.work.mkdir()
        self.bin = self.root / "bin"
        self.bin.mkdir()
        for name in ("sh", "cksum", "awk", "pwd", "tail", "cat", "ls", "head", "mkdir", "mktemp", "mv", "sleep"):
            (self.bin / name).symlink_to(shutil.which(name))
        self.env = dict(os.environ, HOME=str(self.root / "home"),
                        AHAROU_LOG_DIR=str(self.root / "logs"),
                        PATH=str(self.bin), FIXTURE_EXIT="0")
        fake = '#!/bin/sh\nprintf "%s\\n" "$@" > "$HOME/args"\nprintf "stdout-marker\\n"\nprintf "stderr-marker\\n" >&2\nsleep "${FIXTURE_SLEEP:-0}"\nexit "$FIXTURE_EXIT"\n'
        Path(self.env["HOME"]).mkdir()
        for name in ("cargo", "python3"):
            path = self.bin / name
            path.write_text(fake)
            path.chmod(0o755)
        (self.work / "gradlew").write_text(fake)

    def run_cli(self, *args, **env):
        return subprocess.run(["sh", str(CLI), *args], cwd=self.work,
                              env=self.env | env, text=True, capture_output=True)

    def test_all_commands_and_failure_exit(self):
        for args, expected in [(("rust",), ["build"]),
                               (("python",), ["-m", "pip", "wheel", "--no-deps", ".", "-w", "dist"]),
                               (("android", ":app:assembleDebug"), ["--console=plain", ":app:assembleDebug"])]:
            result = self.run_cli(*args, FIXTURE_EXIT="7")
            self.assertEqual(result.returncode, 7, result.stderr)
            self.assertIn("stdout-marker", result.stdout)
            self.assertIn("stderr-marker", result.stdout)
            self.assertIn("exit_code=7", result.stdout)
            self.assertEqual((Path(self.env["HOME"]) / "args").read_text().splitlines(), expected)
        self.assertEqual(len(list((self.root / "logs").glob("*/*/*.done"))), 3)

    def test_success_tail_and_logs(self):
        (self.work / "gradlew").write_text('i=1; while [ "$i" -le 120 ]; do echo "line-$i"; i=$((i + 1)); done\n')
        result = self.run_cli("android", "build")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("line-40\n", result.stdout)
        self.assertIn("line-41\n", result.stdout)
        self.assertIn("line-120\n", result.stdout)
        logs = self.run_cli("logs")
        self.assertEqual(logs.returncode, 0, logs.stderr)
        self.assertEqual(logs.stdout, result.stdout)
        self.assertEqual(len(next((self.root / "logs").glob("*/*/*.log")).read_text().splitlines()), 120)

    def test_android_loads_installed_profile_in_non_login_shell(self):
        profile = self.root / "android-profile.sh"
        profile.write_text('export ANDROID_HOME="/fixture/sdk"\n')
        cli = self.root / "cli-fixture.sh"
        cli.write_text(CLI.read_text().replace("/etc/profile.d/aicode-android.sh", str(profile)))
        (self.work / "gradlew").write_text('printf "sdk=%s\\n" "$ANDROID_HOME"\n')
        result = subprocess.run(["sh", str(cli), "android", "build"], cwd=self.work,
                                env=self.env, text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("sdk=/fixture/sdk", result.stdout)
        profile.write_text("return 7\n")
        result = subprocess.run(["sh", str(cli), "android", "build"], cwd=self.work,
                                env=self.env, text=True, capture_output=True)
        self.assertEqual(result.returncode, 7)

    def test_invalid_action_and_task_never_execute(self):
        for args in [("unknown",), ("android",), ("rust", "extra"),
                     ("android", "build", "extra"), ("android", "--init-script=bad"),
                     ("android", "build;touch hacked")]:
            self.assertNotEqual(self.run_cli(*args).returncode, 0)
        self.assertFalse((Path(self.env["HOME"]) / "args").exists())
        self.assertFalse((self.root / "logs").exists())

    def test_logs_no_record_and_workspace_isolation(self):
        self.assertNotEqual(self.run_cli("logs").returncode, 0)
        self.assertFalse((self.root / "logs").exists())
        self.assertEqual(self.run_cli("rust").returncode, 0)
        self.work = self.root / "another workspace"
        self.work.mkdir()
        self.assertNotEqual(self.run_cli("logs").returncode, 0)

    def test_mapped_workspace_identity_isolation(self):
        self.assertEqual(self.run_cli("rust", AHAROU_WORKSPACE_ID="/host/project-a").returncode, 0)
        self.assertNotEqual(self.run_cli("logs", AHAROU_WORKSPACE_ID="/host/project-b").returncode, 0)
        self.assertEqual(self.run_cli("logs", AHAROU_WORKSPACE_ID="/host/project-a").returncode, 0)

    def test_container_isolation(self):
        self.assertEqual(self.run_cli("rust", AHAROU_CONTAINER_ID="first").returncode, 0)
        self.assertNotEqual(self.run_cli("logs", AHAROU_CONTAINER_ID="second").returncode, 0)
        self.assertEqual(self.run_cli("logs", AHAROU_CONTAINER_ID="first").returncode, 0)

    def test_concurrent_and_incomplete(self):
        first = subprocess.Popen(["sh", str(CLI), "rust"], cwd=self.work,
                                 env=self.env | {"FIXTURE_SLEEP": "0.2"},
                                 text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        second = self.run_cli("python")
        stdout, stderr = first.communicate(timeout=5)
        self.assertEqual(first.returncode, 0, stderr)
        self.assertEqual(second.returncode, 0, second.stderr)
        self.assertNotEqual(stdout.split("log_path=")[-1], second.stdout.split("log_path=")[-1])
        folder = next((self.root / "logs").glob("*/*"))
        (folder / "rust.incomplete.log").write_text("MUST-NOT-BE-LATEST")
        self.assertNotIn("MUST-NOT-BE-LATEST", self.run_cli("logs").stdout)
        self.assertEqual(len(list(folder.glob("*.done"))), 2)

    def test_unusable_logging_falls_back_and_preserves_exit(self):
        blocked = self.root / "blocked"
        blocked.write_text("not a directory")
        result = self.run_cli("rust", AHAROU_LOG_DIR=str(blocked), FIXTURE_EXIT="7")
        self.assertEqual(result.returncode, 7, result.stderr)
        self.assertIn("WARNING", result.stderr)
        self.assertIn(".aharou/logs/build", result.stdout)
        queried = self.run_cli("logs", AHAROU_LOG_DIR=str(blocked))
        self.assertEqual(queried.returncode, 0, queried.stderr)
        self.assertIn("exit_code=7", queried.stdout)
        (Path(self.env["HOME"]) / ".aharou/logs/build").rename(self.root / "oldlogs")
        (Path(self.env["HOME"]) / ".aharou/logs/build").write_text("blocked")
        result = self.run_cli("rust", AHAROU_LOG_DIR=str(blocked), FIXTURE_EXIT="7")
        self.assertEqual(result.returncode, 7, result.stderr)
        self.assertIn("stdout-marker", result.stdout)
        self.assertIn("stderr-marker", result.stderr)
        self.assertIn("WARNING", result.stderr)


if __name__ == "__main__":
    unittest.main()
