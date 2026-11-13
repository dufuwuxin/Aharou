#!/usr/bin/env python3
"""SDK CLI regression tests. Compilers are Python fakes; no plugin is executed."""
import json
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CLI = ROOT / "app/src/main/assets/plugin-sdk/build_plugin.py"
EXAMPLES = CLI.parent / "examples"


class PluginSdkTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="plugin-sdk-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / "source with spaces"
        self.source.mkdir()
        (self.source / "ExamplePlugin.java").write_text("package example; class ExamplePlugin {}")
        self.sdk = self.root / "sdk"
        api = self.sdk / "com/aharou/plugin/api"
        api.mkdir(parents=True)
        for name in ("AharouPlugin", "PluginHost"):
            (api / f"{name}.java").write_text(f"package com.aharou.plugin.api; public interface {name} {{}}")
        self.manifest = self.root / "manifest.json"
        self.host = {"runtime": "dex", "entry": "example.ExamplePlugin", "artifact": "classes.dex", "apiVersion": 1}
        self.write_manifest()
        self.output = self.root / "result.txplugin"
        self.trace = self.root / "trace.jsonl"
        # All compiler entry points are explicit fakes, with no PATH/JAVA_HOME fallback.
        self.env = {k: v for k, v in os.environ.items() if k not in ("JAVA_HOME", "JAVAC", "D8", "R8_JAR", "JAVA")}
        self.env.update(PATH="", TRACE=str(self.trace))
        self.javac = self.fake("javac", '''
out = Path(args[args.index('-d') + 1]); out.mkdir(parents=True, exist_ok=True)
name = 'com/aharou/plugin/api/AharouPlugin.class' if any('PluginHost.java' in a for a in args) else 'example/ExamplePlugin.class'
target = out / name; target.parent.mkdir(parents=True, exist_ok=True); target.write_bytes(b'fake class')
''')
        self.d8 = self.fake("d8", '''
import zipfile
with zipfile.ZipFile(args[-1]) as program:
    assert program.namelist() == ['example/ExamplePlugin.class']
with zipfile.ZipFile(args[args.index('--classpath') + 1]) as sdk:
    assert 'com/aharou/plugin/api/AharouPlugin.class' in sdk.namelist()
out = Path(args[args.index('--output') + 1]); out.mkdir(parents=True, exist_ok=True)
if os.environ.get('BAD_DEX') == '1':
    data = b'not a dex'
else:
    data = b'dex\\n035\\x00' + bytes(104)
(out / 'classes.dex').write_bytes(data)
if os.environ.get('MULTIDEX') == '1': (out / 'classes2.dex').write_bytes(data)
''')

    def fake(self, name, body):
        path = self.root / name
        path.write_text(f"#!{sys.executable}\nimport json, os, sys\nfrom pathlib import Path\nargs = sys.argv[1:]\nwith open(os.environ['TRACE'], 'a') as f: f.write(json.dumps([Path(sys.argv[0]).name, args]) + '\\n')\nif os.environ.get('FAIL_TOOL') == Path(sys.argv[0]).name:\n print('compiler stdout'); print('compiler stderr', file=sys.stderr); sys.exit(7)\n" + body)
        path.chmod(0o755)
        return path

    def write_manifest(self):
        self.manifest.write_text(json.dumps({"schemaVersion": 1, "id": "example.test", "name": "Test", "version": "1.0.0", "hostRuntime": self.host}))

    def run_cli(self, *extra, env=None, compilers=True):
        args = [sys.executable, str(CLI), "--source-dir", str(self.source), "--sdk-src", str(self.sdk), "--manifest", str(self.manifest), "--output", str(self.output)]
        if compilers:
            args += ["--javac", str(self.javac), "--d8", str(self.d8)]
        return subprocess.run(args + list(extra), capture_output=True, text=True, env=self.env | (env or {}))

    def test_help_needs_no_toolchain(self):
        result = subprocess.run([sys.executable, str(CLI), "--help"], capture_output=True, text=True, env=self.env)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("--r8-jar", result.stdout)

    def test_dex_compile_classpath_and_archive(self):
        result = self.run_cli()
        self.assertEqual(result.returncode, 0, result.stderr)
        with zipfile.ZipFile(self.output) as package:
            self.assertEqual(package.namelist(), ["manifest.json", "payload/classes.dex"])
            self.assertEqual(package.read("manifest.json"), self.manifest.read_bytes())
            self.assertTrue(package.read("payload/classes.dex").startswith(b"dex\n035\x00"))
        trace = [json.loads(line) for line in self.trace.read_text().splitlines()]
        self.assertEqual([t[0] for t in trace], ["javac", "javac", "d8"])
        self.assertIn("-proc:none", trace[0][1])
        self.assertIn("-classpath", trace[1][1])
        d8 = trace[2][1]
        self.assertEqual(d8[d8.index("--min-api") + 1], "26")
        self.assertIn("--classpath", d8)
        self.assertTrue(d8[-1].endswith("plugin.jar"))
        self.assertNotEqual(d8[d8.index("--classpath") + 1], d8[-1])

    def test_failed_compilers_keep_old_output_and_logs(self):
        for tool in ("javac", "d8"):
            with self.subTest(tool=tool):
                self.output.write_bytes(b"old usable package")
                result = self.run_cli(env={"FAIL_TOOL": tool})
                self.assertEqual(result.returncode, 7, result.stderr)
                self.assertIn("compiler stdout", result.stdout)
                self.assertIn("compiler stderr", result.stderr)
                self.assertEqual(self.output.read_bytes(), b"old usable package")

    def test_invalid_dex_and_multidex_preserve_old_package(self):
        for flag in ("BAD_DEX", "MULTIDEX"):
            with self.subTest(flag=flag):
                self.output.write_bytes(b"old")
                result = self.run_cli(env={flag: "1"})
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(self.output.read_bytes(), b"old")

    def test_missing_dependencies_are_actionable(self):
        result = self.run_cli(compilers=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("javac", result.stderr.lower())
        self.assertFalse(self.output.exists())
        self.assertFalse(self.trace.exists())

    def test_js_packages_without_compilers(self):
        self.host = {"runtime": "js", "entry": "plugin.js", "apiVersion": 1}
        self.write_manifest()
        (self.source / "ExamplePlugin.java").unlink()
        (self.source / "plugin.js").write_text("var plugin = {};")
        (self.source / "assets").mkdir()
        (self.source / "assets/message.txt").write_text("hello")
        result = self.run_cli(compilers=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(self.trace.exists())
        with zipfile.ZipFile(self.output) as package:
            self.assertEqual(set(package.namelist()), {"manifest.json", "payload/plugin.js", "payload/assets/message.txt"})

    def test_unsafe_paths_and_versions_fail_before_compiling(self):
        for value in ("../escape.dex", "/escape.dex", "a\\b.dex", "a/../b.dex"):
            with self.subTest(value=value):
                self.host["artifact"] = value
                self.write_manifest()
                self.assertNotEqual(self.run_cli().returncode, 0)
        self.host["artifact"] = "classes.dex"
        self.host["apiVersion"] = 2
        self.write_manifest()
        self.assertNotEqual(self.run_cli().returncode, 0)
        self.assertFalse(self.trace.exists())

    def test_js_symlink_is_not_followed(self):
        self.host = {"runtime": "js", "entry": "plugin.js", "apiVersion": 1}
        self.write_manifest()
        (self.source / "plugin.js").symlink_to(self.manifest)
        self.assertNotEqual(self.run_cli(compilers=False).returncode, 0)
        self.assertFalse(self.output.exists())

    def test_r8_jar_uses_explicit_java(self):
        jar = self.root / "r8.jar"
        jar.write_bytes(b"fixture")
        java = self.fake("java", "out = Path" + self.d8.read_text().split("out = Path", 1)[1])
        result = self.run_cli("--r8-jar", str(jar), "--java", str(java))
        self.assertNotEqual(result.returncode, 0)  # --d8 and --r8-jar must not silently win.
        args = [sys.executable, str(CLI), "--source-dir", str(self.source), "--sdk-src", str(self.sdk), "--manifest", str(self.manifest), "--output", str(self.output), "--javac", str(self.javac), "--r8-jar", str(jar), "--java", str(java)]
        result = subprocess.run(args, capture_output=True, text=True, env=self.env)
        self.assertEqual(result.returncode, 0, result.stderr)
        trace = [json.loads(line) for line in self.trace.read_text().splitlines()]
        self.assertEqual(trace[-1][1][:3], ["-cp", str(jar), "com.android.tools.r8.D8"])

    def test_examples_match_sdk_v1(self):
        for runtime in ("java", "js"):
            manifest = json.loads((EXAMPLES / runtime / "manifest.json").read_text())
            self.assertEqual(manifest["schemaVersion"], 1)
            self.assertEqual(manifest["hostRuntime"]["apiVersion"], 1)
        java = (EXAMPLES / "java/ExamplePlugin.java").read_text()
        self.assertIn("implements AharouPlugin", java)
        self.assertIn("PluginHost", java)


if __name__ == "__main__":
    unittest.main()
