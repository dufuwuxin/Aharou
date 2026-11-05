#!/usr/bin/env python3
"""Mock XBPS and SDK operations; never install packages or access the network."""
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LIB = ROOT / "app/src/main/assets/aharou/lib"


class VoidToolchainTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix=".void-fixture-", dir=ROOT)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        # Deliberately exclude host package managers even on the pre-implementation run.
        for name in ("grep", "sed", "awk", "sort", "head", "readlink", "uname",
                     "mkdir", "rm", "cp", "chmod", "mv", "mktemp", "rmdir"):
            (self.bin / name).symlink_to(shutil.which(name))
        self.env = dict(os.environ, HOME=str(self.root), AICODE_LIB_DIR=str(LIB),
                        PATH=str(self.bin), TRACE=str(self.root / "trace"),
                        AICODE_XBPS_CONF_DIR=str(self.root / "etc-xbps.d"),
                        AICODE_XBPS_SHARE_DIR=str(self.root / "share-xbps.d"))
        self.tool("xbps-install", 'printf "%s\\n" "$*" >> "$TRACE"; [ "$*" = "-Suy xbps" ] && exit "${SELF_EXIT:-0}"; [ "$*" = "-S" ] && [ -n "${SYNC_ERROR:-}" ] && echo "ERROR: [reposync] failed to fetch file" >&2; exit "${ADD_EXIT:-0}"')
        self.tool("xbps-query", 'case "$*" in *openjdk17*) [ "${ONLY_21:-0}" = 1 ] && exit 1; echo 17.0.1_1;; *openjdk21*) echo 21.0.1_1;; *) echo 1.2.3_1;; esac')

    def tool(self, name, body):
        path = self.bin / name
        if path.is_symlink():
            path.unlink()
        path.write_text(f"#!/bin/sh\n{body}\n")
        path.chmod(0o755)

    def shell(self, body, stdin="", **env):
        return subprocess.run(["/bin/sh", "-c", f'. "{LIB}/env-common.sh"; PMGR=xbps; {body}'],
                              env=self.env | env, input=stdin, capture_output=True, text=True)

    def test_detection_and_xbps_commands(self):
        result = self.shell('detect_pmgr; echo "$PMGR"; pkg_update; pkg_add "base-devel" "rust"; pkg_versions rust')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.splitlines(), ["xbps", "1.2.3_1"])
        self.assertEqual((self.root / "trace").read_text().splitlines(), ["-Sy", "-Suy xbps", "-uy", "-y base-devel rust"])
        self.assertEqual(self.shell('pkg_add rust', ADD_EXIT="7").returncode, 7)
        self.assertEqual(self.shell('pkg_update', ADD_EXIT="7").returncode, 7)

    def test_mappings_and_candidates(self):
        result = self.shell('base_pkgs; tool_pkg netcat-openbsd; runtime_pkgs rust; runtime_pkgs python; runtime_pkgs java; runtime_pkgs node; runtime_pkgs go; runtime_pkgs php; runtime_candidates java; android_jdk_pkg')
        self.assertEqual(result.returncode, 0, result.stderr)
        lines = result.stdout.splitlines()
        self.assertIn("bind-utils", lines[0])
        self.assertNotIn("bind-tools", lines[0])
        self.assertEqual(lines[1:8], ["netcat", "rust cargo base-devel pkg-config",
                                    "python3 python3-pip python3-devel base-devel pkg-config",
                                    "openjdk17", "nodejs", "go", "php"])
        self.assertEqual(lines[8:], ["openjdk21", "openjdk17", "openjdk17"])
        self.assertEqual(self.shell('android_jdk_pkg', ONLY_21="1").stdout.strip(), "openjdk21")

    def test_xbps_self_update_runs_once_before_install(self):
        result = self.shell('pkg_add rust; pkg_add cargo', SELF_EXIT="17")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.root / "trace").read_text().splitlines(),
                         ["-Suy xbps", "-uy", "-y rust", "-y cargo"])
        (self.root / "trace").unlink()
        self.assertEqual(self.shell('pkg_add rust', SELF_EXIT="16").returncode, 16)
        self.assertEqual((self.root / "trace").read_text().splitlines(), ["-Suy xbps"])
        self.assertEqual(self.shell('pkg_add rust', ADD_EXIT="17").returncode, 0)
        self.tool("xbps-query", 'exit 2')
        self.assertEqual(self.shell('pkg_add rust', ADD_EXIT="17").returncode, 17)

    def test_void_mirror_switch_and_restore(self):
        share = self.root / "share-xbps.d"
        conf = self.root / "etc-xbps.d"
        share.mkdir()
        official = "repository=https://repo-default.voidlinux.org/current/aarch64\n"
        (share / "00-repository-main.conf").write_text(official)
        (share / "xbps.conf").write_text("#repository=https://repo-default.voidlinux.org/current\n")
        result = self.shell('MIRROR=mirrors.tuna.tsinghua.edu.cn; setup_mirror')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((conf / "00-repository-main.conf").read_text(),
                         "# aicode-managed-void-mirror\nrepository=https://mirrors.tuna.tsinghua.edu.cn/voidlinux/current/aarch64\n")
        self.assertFalse((conf / "xbps.conf").exists())
        self.assertEqual((share / "00-repository-main.conf").read_text(), official)
        self.assertEqual((self.root / "trace").read_text().splitlines(), ["-S"])
        (conf / "00-repository-main.conf").write_text(official)
        self.assertNotEqual(self.shell('MIRROR=mirrors.bfsu.edu.cn; setup_mirror', ADD_EXIT="7").returncode, 0)
        self.assertEqual((conf / "00-repository-main.conf").read_text(), official)
        self.assertFalse((conf / "00-repository-main.conf.backup").exists())
        (conf / "00-repository-main.conf").unlink()
        self.assertNotEqual(self.shell('MIRROR=mirrors.bfsu.edu.cn; setup_mirror', ADD_EXIT="7").returncode, 0)
        self.assertFalse((conf / "00-repository-main.conf").exists())
        self.assertNotEqual(self.shell('MIRROR=mirrors.bfsu.edu.cn; setup_mirror', SYNC_ERROR="1").returncode, 0)
        self.assertFalse((conf / "00-repository-main.conf").exists())

    def test_void_mirror_preserves_backup_and_handles_space_paths(self):
        share = self.root / "share space"
        conf = self.root / "conf space"
        share.mkdir()
        conf.mkdir()
        name = "00 main.conf"
        official = "repository=https://repo-default.voidlinux.org/current/aarch64\n"
        (share / name).write_text(official)
        (conf / name).write_text(official)
        backup = conf / (name + ".backup")
        backup.write_text("user backup\n")
        env = dict(AICODE_XBPS_CONF_DIR=str(conf), AICODE_XBPS_SHARE_DIR=str(share))
        result = self.shell('MIRROR=mirrors.tuna.tsinghua.edu.cn; setup_mirror', ADD_EXIT="7", **env)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((conf / name).read_text(), official)
        self.assertEqual(backup.read_text(), "user backup\n")
        result = self.shell('MIRROR=mirrors.tuna.tsinghua.edu.cn; setup_mirror', **env)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(backup.read_text(), "user backup\n")
        self.assertIn("mirrors.tuna", (conf / name).read_text())
        self.assertEqual(sorted(p.name for p in conf.iterdir()), [name, name + ".backup"])

    def test_void_mirror_can_switch_managed_source_again(self):
        share = self.root / "share-xbps.d"
        conf = self.root / "etc-xbps.d"
        share.mkdir()
        (share / "00-repository-main.conf").write_text("repository=https://repo-default.voidlinux.org/current/aarch64\n")
        result = self.shell('MIRROR=mirrors.tuna.tsinghua.edu.cn; setup_mirror; MIRROR=mirrors.bfsu.edu.cn; setup_mirror')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("mirrors.bfsu", (conf / "00-repository-main.conf").read_text())
        self.assertNotIn("mirrors.tuna", (conf / "00-repository-main.conf").read_text())

    def test_android_propagates_failures_without_ready_message(self):
        body = '''pkg_add() { printf '%s\\n' "$*"; return "${ADD_EXIT:-0}"; }
install_android_sdk() { return 0; }
install_android_tools_arm64() { return 7; }
write_android_profile() { echo BAD-PROFILE; }
show_plan() { return 0; }
install_scenario_android'''
        result = self.shell(body)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("openjdk17 unzip", result.stdout)
        self.assertNotIn("环境就绪", result.stdout)
        self.assertNotIn("BAD-PROFILE", result.stdout)
        result = self.shell(body, ADD_EXIT="7")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("环境就绪", result.stdout)

    def test_void_scenarios_propagate_install_failure(self):
        for scenario in ("rust", "python"):
            result = self.shell(f'ask_mirror() {{ :; }}; show_plan() {{ :; }}; install_scenario_{scenario}', ADD_EXIT="7")
            self.assertNotEqual(result.returncode, 0)

    def test_arm64_download_and_validation_failures(self):
        self.tool("uname", 'echo aarch64')
        sdk = self.root / "android/sdk"
        (sdk / "build-tools/35.0.0").mkdir(parents=True)
        (sdk / "platform-tools").mkdir()
        self.tool("curl", 'exit 7')
        self.assertNotEqual(self.shell('install_android_tools_arm64').returncode, 0)
        cache = self.root / ".aicode-cache"
        (cache / "android-sdk-tools-static-aarch64.zip").write_text("fake zip")
        self.tool("unzip", 'exit 0')
        result = self.shell('install_android_tools_arm64')
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("已替换", result.stdout)

    def test_arm64_execution_failure_and_success(self):
        self.tool("uname", 'echo aarch64')
        cache = self.root / ".aicode-cache"
        cache.mkdir()
        (cache / "android-sdk-tools-static-aarch64.zip").write_text("fake zip")
        sdk = self.root / "android/sdk"
        (sdk / "build-tools/35.0.0").mkdir(parents=True)
        (sdk / "platform-tools").mkdir()
        self.tool("unzip", 'mkdir -p "$HOME/.aicode-cache/armtools35/build-tools" "$HOME/.aicode-cache/armtools35/platform-tools"; printf \'#!/bin/sh\\nexit ${VERIFY_EXIT:-0}\\n\' > "$HOME/.aicode-cache/armtools35/build-tools/aapt2"; cp "$HOME/.aicode-cache/armtools35/build-tools/aapt2" "$HOME/.aicode-cache/armtools35/platform-tools/adb"')
        self.assertNotEqual(self.shell('install_android_tools_arm64', VERIFY_EXIT="7").returncode, 0)
        self.assertEqual(self.shell('install_android_tools_arm64').returncode, 0)

    def test_void_scenario_commands_and_validation(self):
        for tool in ("rustc", "cargo", "python3"):
            self.tool(tool, 'echo "validated-$*"; exit "${VERIFY_EXIT:-0}"')
        for scenario, packages in (("rust", "rust cargo base-devel pkg-config"),
                                   ("python", "python3 python3-pip python3-devel base-devel pkg-config")):
            result = self.shell(f'show_plan() {{ return 0; }}; install_optional_tools() {{ :; }}; install_scenario_{scenario}')
            self.assertEqual(result.returncode, 0, result.stderr)
            trace = (self.root / "trace").read_text()
            self.assertIn(packages, trace)
            self.assertIn("nodejs", trace)
            self.assertNotEqual(self.shell(f'show_plan() {{ return 0; }}; install_optional_tools() {{ :; }}; install_scenario_{scenario}', VERIFY_EXIT="7").returncode, 0)

    def test_void_scenarios_allow_cancel_without_installing(self):
        for scenario in ("rust", "python"):
            result = self.shell(f'show_plan() {{ return 1; }}; install_scenario_{scenario}')
            self.assertEqual(result.returncode, 2)
            self.assertEqual((self.root / "trace").read_text().splitlines(), ["-Sy"] * (1 if scenario == "rust" else 2))

    def test_void_mirror_keeps_custom_repositories_and_opt_out(self):
        share = self.root / "share-xbps.d"
        conf = self.root / "etc-xbps.d"
        share.mkdir()
        conf.mkdir()
        (share / "00-repository-main.conf").write_text("repository=https://repo-default.voidlinux.org/current/aarch64\n")
        custom = "repository=https://example.invalid/current/aarch64\n"
        (conf / "00-repository-main.conf").write_text(custom)
        self.assertEqual(self.shell('MIRROR=mirrors.tuna.tsinghua.edu.cn; setup_mirror').returncode, 0)
        self.assertEqual((conf / "00-repository-main.conf").read_text(), custom)
        (conf / "00-repository-main.conf").unlink()
        result = self.shell('setup_mirror() { echo BAD-SETUP; }; ask_mirror', stdin="0\n")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("BAD-SETUP", result.stdout)
        self.assertIn("mirrors.bfsu.edu.cn", result.stdout)
        self.assertNotIn("mirrors.huaweicloud.com", result.stdout)
        self.assertFalse((conf / "00-repository-main.conf").exists())

    def test_android_cancel_never_installs(self):
        result = self.shell('show_plan() { return 1; }; install_scenario_android')
        self.assertEqual(result.returncode, 2)
        self.assertEqual((self.root / "trace").read_text().splitlines(), ["-Sy"])

    def test_profile_write_failures_propagate(self):
        private_sdk_script = self.root / "sdk-fixture.sh"
        profile_dir = self.root / "profile.d"
        private_sdk_script.write_text((LIB / "android-sdk.sh").read_text().replace("/etc/profile.d", str(profile_dir)))
        sdk = self.root / "android/sdk/build-tools/35.0.0"
        sdk.mkdir(parents=True)
        (sdk / "aapt2").write_text("fixture")
        (sdk / "aapt2").chmod(0o755)
        prelude = f'. "{private_sdk_script}"; java_home_dir() {{ echo /jdk; }}; '
        result = self.shell(prelude + 'mkdir() { return 7; }; write_android_profile')
        self.assertNotEqual(result.returncode, 0)
        profile_dir.mkdir()
        (profile_dir / "aicode-android.sh").mkdir()
        result = self.shell(prelude + 'write_android_profile')
        self.assertNotEqual(result.returncode, 0)
        (profile_dir / "aicode-android.sh").rmdir()
        result = self.shell(prelude + 'mv() { return 7; }; write_android_profile')
        self.assertNotEqual(result.returncode, 0)
        result = self.shell(prelude + 'write_android_profile')
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_java_home_void_paths(self):
        script = (LIB / "android-sdk.sh").read_text()
        self.assertIn("/usr/lib/jvm/openjdk$v", script)


if __name__ == "__main__":
    unittest.main()
