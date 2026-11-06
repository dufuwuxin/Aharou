#!/usr/bin/env python3
"""Exercise the audit scenario with isolated PATH and fake package operations."""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LIB = ROOT / "app/src/main/assets/aharou/lib"
REQUIRED = ("readelf", "objdump", "strings", "file", "java", "python3")
PACKAGES = {
    "apk": "binutils file openjdk17-jre-headless python3",
    "apt": "binutils file default-jre-headless python3",
    "dnf": "binutils file java-17-openjdk-headless python3",
    "yum": "binutils file java-17-openjdk-headless python3",
    "pacman": "binutils file jre17-openjdk python",
    "xbps": "binutils file openjdk17-jre python3",
}
QUERY_ARGS = {
    "apk": "apk|search -e binutils",
    "apt": "apt-cache|policy binutils",
    "dnf": "dnf|-q list --available binutils",
    "yum": "yum|-q list available binutils",
    "pacman": "pacman|-Si binutils",
    "xbps": "xbps-query|-R binutils",
}


class RuntimeAuditSetupTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="audit-setup-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.trace = self.root / "trace"
        # No host package manager, sudo, network client, or analysis tool is on PATH.
        for name in ("grep", "chmod", "wc", "awk"):
            executable = shutil.which(name)
            assert executable is not None
            (self.bin / name).symlink_to(executable)
        self.env = dict(
            os.environ,
            HOME=str(self.root),
            PATH=str(self.bin),
            AICODE_LIB_DIR=str(LIB),
            TRACE=str(self.trace),
            FIXTURE_BIN=str(self.bin),
        )
        self.tool("fixture-update", 'printf "UPDATE\\n" >> "$TRACE"; exit "${UPDATE_RC:-0}"')
        self.tool("fixture-add", '''printf 'ADD|%s\n' "$*" >> "$TRACE"
[ "${ADD_RC:-0}" = 0 ] || exit "$ADD_RC"
[ "${LEAVE_MISSING:-0}" = 0 ] || exit 0
for package in "$@"; do
    case "$package" in
        binutils) commands='readelf objdump strings';;
        file|jadx|apktool) commands="$package";;
        python|python3) commands=python3;;
        *jre*|*openjdk*) commands=java;;
        *) exit 99;;
    esac
    for command in $commands; do
        printf '#!/bin/sh\nprintf "EXEC|%s\\n" >> "$TRACE"\nexit 97\n' "$command" > "$FIXTURE_BIN/$command"
        chmod +x "$FIXTURE_BIN/$command"
    done
done''')
        for manager in ("apk", "apt-cache", "dnf", "yum", "pacman", "xbps-query"):
            self.tool(manager, f'''printf '{manager}|%s\n' "$*" >> "$TRACE"
for package in "$@"; do :; done
[ "$package" != "${{UNAVAILABLE:-!}}" ] || exit 1
case '{manager}' in
    apk) printf '%s-1.0-r0\n' "$package";;
    apt-cache) printf '  Candidate: 1.0\n';;
esac
exit 0''')

    def tool(self, name: str, body: str) -> None:
        path = self.bin / name
        path.write_text(f"#!/bin/sh\n{body}\n", encoding="utf-8")
        path.chmod(0o755)

    def installed(self, *names: str) -> None:
        for name in names:
            self.tool(name, f'printf "EXEC|{name}\\n" >> "$TRACE"; exit 97')

    def shell(self, body: str, stdin: str = "", **env: str) -> subprocess.CompletedProcess[str]:
        # Replace the shared mutation boundary, not its repository-query consumers.
        # In particular, pkg_add's apt branch writes an absolute /etc configuration;
        # never call that helper or provision.sh on the test host.
        script = f'''. "{LIB}/env-common.sh"
pkg_update() {{ fixture-update; }}
pkg_add() {{ fixture-add "$@"; }}
{body}'''
        return subprocess.run(
            ["/bin/sh", "-c", script],
            env=self.env | env,
            input=stdin,
            capture_output=True,
            text=True,
            timeout=10,
        )

    def receipts(self) -> list[str]:
        return self.trace.read_text().splitlines() if self.trace.exists() else []

    def test_menu_registration_and_version_alignment(self) -> None:
        result = self.shell('printf "%s\\n" "$SCENARIO_IDS"; scenario_name security_audit')
        self.assertEqual(result.returncode, 0, result.stderr)
        lines = result.stdout.splitlines()
        self.assertEqual(lines[0].split(), ["common", "python", "node", "android", "flutter", "java", "go", "rust", "php", "base", "security_audit"])
        self.assertIn("授权离线逆向分析", lines[1])
        shell_version = re.search(r'PROVISION_VERSION="([^"]+)"', (LIB.parent / "provision.sh").read_text())
        engine = ROOT / "app/src/main/java/com/aharou/feature/agent/domain/container/LinuxContainerEngine.kt"
        kotlin_version = re.search(r'PROVISION_VERSION = "([^"]+)"', engine.read_text())
        self.assertIsNotNone(shell_version)
        self.assertIsNotNone(kotlin_version)
        self.assertEqual(shell_version.group(1), kotlin_version.group(1))

    def test_real_environment_menu_selects_audit_scenario(self) -> None:
        provision = (LIB.parent / "provision.sh").read_text()
        start = provision.index("env_install_menu() {")
        end = provision.index("\n}\n", start) + len("\n}\n")
        menu_function = provision[start:end]
        result = self.shell(
            f"PMGR=apk; plog() {{ :; }}; {menu_function}\nenv_install_menu",
            "11\ny\nn\nn\n",
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        menu_text = re.sub(r"\x1b\[[0-9;]*m", "", result.stdout)
        self.assertIn("11. 授权离线逆向分析", menu_text)
        self.assertIn(f"ADD|{PACKAGES['apk']}", self.receipts())
        self.assertIn("基础离线分析工具已就绪", result.stdout)

    def test_detect_is_read_only_and_never_executes_tools(self) -> None:
        self.installed(*REQUIRED, "jadx", "apktool")
        result = self.shell("security_audit_detect")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.count("：可用"), 8)
        self.assertEqual(self.receipts(), [])

    def test_detect_reports_missing(self) -> None:
        result = self.shell("security_audit_detect")
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout.count("不可用（未安装）"), 8)
        self.assertEqual(self.receipts(), [])

    def test_six_manager_package_mappings_and_optional_defaults(self) -> None:
        for manager, packages in PACKAGES.items():
            with self.subTest(manager=manager):
                # Each subcase gets a fresh PATH, including no installed tool stubs.
                for name in (*REQUIRED, "jadx", "apktool"):
                    path = self.bin / name
                    if path.exists():
                        path.unlink()
                if self.trace.exists():
                    self.trace.unlink()
                result = self.shell(f"PMGR={manager}; install_scenario security_audit", "y\n\n\n")
                self.assertEqual(result.returncode, 0, result.stderr)
                receipts = self.receipts()
                self.assertIn(f"ADD|{packages}", receipts)
                self.assertIn(QUERY_ARGS[manager], receipts)
                self.assertEqual(sum(line.startswith("ADD|") for line in receipts), 1)
                self.assertIn("基础离线分析工具已就绪", result.stdout)
                self.assertIn("jadx：不可用", result.stdout)
                self.assertFalse(any(line.startswith("EXEC|") for line in receipts))
                self.assertFalse(any("ghidra" in line.lower() or "nodejs" in line for line in receipts))

    def test_optional_tools_selected_and_checked(self) -> None:
        result = self.shell("PMGR=apk; install_scenario security_audit", "y\ny\ny\n")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(f"ADD|{PACKAGES['apk']} jadx apktool", self.receipts())
        self.assertIn("jadx：可用", result.stdout)
        self.assertIn("apktool：可用", result.stdout)
        self.assertFalse(any(line.startswith("EXEC|") for line in self.receipts()))

    def test_existing_tools_need_no_package_operation(self) -> None:
        self.installed(*REQUIRED, "jadx", "apktool")
        result = self.shell("PMGR=apt; install_scenario security_audit", "y\n")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.receipts(), [])

    def test_only_missing_tools_are_added(self) -> None:
        self.installed("readelf", "objdump", "strings", "file", "java")
        result = self.shell("PMGR=pacman; install_scenario security_audit", "y\nn\nn\n")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("ADD|python", self.receipts())

    def test_cancel_and_eof_never_query_or_install(self) -> None:
        for stdin in ("n\n", "", "y\n", "y\ny\n"):
            with self.subTest(stdin=stdin):
                result = self.shell("PMGR=apk; install_scenario security_audit", stdin)
                self.assertEqual(result.returncode, 2, result.stderr)
                self.assertEqual(self.receipts(), [])

    def test_unavailable_base_package_blocks_install(self) -> None:
        result = self.shell("PMGR=apt; install_scenario security_audit", "y\nn\nn\n", UNAVAILABLE="binutils")
        self.assertEqual(result.returncode, 1)
        self.assertIn("不可用", result.stderr)
        self.assertFalse(any(line.startswith("ADD|") for line in self.receipts()))

    def test_unavailable_optional_package_blocks_install(self) -> None:
        for package, answers in (("jadx", "y\ny\nn\n"), ("apktool", "y\nn\ny\n")):
            with self.subTest(package=package):
                result = self.shell("PMGR=xbps; install_scenario security_audit", answers, UNAVAILABLE=package)
                self.assertEqual(result.returncode, 1)
                self.assertIn(package, result.stderr)
                self.assertFalse(any(line.startswith("ADD|") for line in self.receipts()))

    def test_update_and_install_exit_codes_propagate(self) -> None:
        result = self.shell("PMGR=dnf; install_scenario security_audit", "y\nn\nn\n", UPDATE_RC="9")
        self.assertEqual(result.returncode, 9)
        self.assertEqual(self.receipts(), ["UPDATE"])
        result = self.shell("PMGR=yum; install_scenario security_audit", "y\nn\nn\n", ADD_RC="7")
        self.assertEqual(result.returncode, 7)
        self.assertIn("安装失败", result.stderr)
        self.assertNotIn("基础离线分析工具已就绪", result.stdout)

    def test_success_exit_without_commands_is_not_ready(self) -> None:
        result = self.shell("PMGR=apk; install_scenario security_audit", "y\ny\ny\n", LEAVE_MISSING="1")
        self.assertEqual(result.returncode, 1)
        self.assertIn("环境未就绪", result.stderr)
        self.assertIn("jadx", result.stderr)
        self.assertNotIn("基础离线分析工具已就绪", result.stdout)

    def test_unknown_manager_fails_without_mutation(self) -> None:
        result = self.shell("PMGR=unknown; install_scenario security_audit", "y\n")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(self.receipts(), [])


if __name__ == "__main__":
    unittest.main()
