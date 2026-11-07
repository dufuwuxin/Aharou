import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
QUERY = ROOT / "app/src/main/assets/aharou/lib/codekit/query.py"


class CodeStructureTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.work = self.root / "workspace with spaces"
        self.work.mkdir()
        (self.work / "main.py").write_text('def hello():\n    return 1\nhello()\n# hello()\ns = "hello()"\n')
        (self.work / "main.kt").write_text('class Sample {\n fun greet() = 1\n}\n')
        (self.work / "main.rs").write_text('fn greet() -> i32 { 1 }\n')

    def run_query(self, *args):
        result = subprocess.run([sys.executable, "-I", str(QUERY), *args], cwd=self.work,
                                capture_output=True, text=True, timeout=15)
        return result, json.loads(result.stdout)

    def test_three_languages_and_no_source_execution(self):
        (self.work / "evil.py").write_text('raise RuntimeError("must not execute")\ndef safe(): pass\n')
        result, data = self.run_query("map")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual({item["language"] for item in data["results"]}, {"python", "rust", "kotlin"})
        self.assertTrue(any(item["name"] == "safe" for item in data["results"]))
        self.assertEqual(data["analysis"], "structural-not-semantic")

    def test_callers_ignore_comments_and_strings(self):
        result, data = self.run_query("callers", "--name", "hello", "--path", "main.py")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([item["line"] for item in data["results"]], [3])

    def test_symbol_name_filters_and_missing_names(self):
        _, data = self.run_query("symbols", "--path", "main.kt", "--name", "greet")
        self.assertEqual([item["name"] for item in data["results"]], ["greet"])
        result, data = self.run_query("callers")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(data["error"], "NAME_REQUIRED")

    def test_escape_and_symlink_are_rejected(self):
        outside = self.root / "outside.py"
        outside.write_text('def secret(): pass\n')
        (self.work / "linked.py").symlink_to(outside)
        for path in (str(outside), "../outside.py", "linked.py"):
            result, data = self.run_query("symbols", "--path", path)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(data["error"], "OUTSIDE_WORKSPACE")
        _, data = self.run_query("map")
        self.assertFalse(any(item["name"] == "secret" for item in data["results"]))

    def test_bounded_and_generated_files_excluded(self):
        build = self.work / "build"
        build.mkdir()
        (build / "gen.py").write_text('def generated(): pass\n')
        result, data = self.run_query("map", "--limit", "1")
        self.assertEqual(result.returncode, 0)
        self.assertEqual(len(data["results"]), 1)
        self.assertTrue(data["truncated"])
        self.assertFalse(any(item["name"] == "generated" for item in data["results"]))
        self.assertLessEqual(len(result.stdout), 24000)

    def test_edits_and_deletions_never_reuse_stale_symbols(self):
        _, first = self.run_query("symbols", "--path", "main.py")
        self.assertEqual(first["results"][0]["name"], "hello")
        (self.work / "main.py").write_text('def changed(): pass\n')
        _, second = self.run_query("symbols", "--path", "main.py")
        self.assertEqual(second["results"][0]["name"], "changed")
        self.assertNotEqual(first["files"][0]["sha256"], second["files"][0]["sha256"])
        (self.work / "main.py").unlink()
        result, data = self.run_query("symbols", "--path", "main.py")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(data["error"], "FILE_NOT_FOUND")

    def test_parse_errors_are_not_reported_as_clean(self):
        (self.work / "broken.py").write_text('def broken(:\n')
        _, data = self.run_query("map")
        self.assertTrue(any(item["file"] == "broken.py" for item in data["errors"]))
        self.assertTrue(data["partial"])

    def test_calls_on_definition_lines_are_retained(self):
        samples = {
            "inline.kt": "fun caller() = target()\nfun target() = target()\n",
            "inline.py": "def caller(): target()\ndef target(): target()\n",
            "inline.rs": "fn caller() { target(); }\nfn target() { target(); }\n",
        }
        for filename, source in samples.items():
            (self.work / filename).write_text(source)
            _, data = self.run_query("callers", "--path", filename, "--name", "target")
            self.assertEqual([item["line"] for item in data["results"]], [1, 2], filename)

    def test_broken_kotlin_and_rust_are_partial(self):
        for filename, source in (("broken.kt", "class Broken {\n"), ("broken.rs", "fn broken() {\n")):
            (self.work / filename).write_text(source)
            _, data = self.run_query("symbols", "--path", filename)
            self.assertTrue(data["partial"], filename)
            self.assertTrue(data["errors"], filename)

    def test_check_action_is_available(self):
        result = subprocess.run([sys.executable, "-I", str(QUERY), "check", "--path", "main.py"],
                                cwd=self.work, capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["analysis"], "lexical-not-compiler")

    def test_check_valid_single_files_never_execute_source(self):
        (self.work / "main.py").write_text('raise RuntimeError("must not execute")\n')
        for filename in ("main.py", "main.kt", "main.rs"):
            with self.subTest(filename=filename):
                result, data = self.run_query("check", "--path", filename)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertTrue(data["valid"])
                self.assertFalse(data["partial"])
                self.assertFalse(data["truncated"])
                self.assertEqual(data["errors"], [])
                self.assertEqual(data["results"], [])
                self.assertEqual(data["scanned_files"], 1)
                self.assertEqual(data["files"][0]["file"], filename)

    def test_check_reports_located_errors_with_success_exit(self):
        samples = {"bad.py": "def bad()\n    pass\n", "bad.kt": "class Bad {\n",
                   "bad.rs": "fn bad() {\n", "string.kt": 'val s = "unclosed\n',
                   "comment.rs": "/* unclosed\n"}
        for filename, source in samples.items():
            with self.subTest(filename=filename):
                (self.work / filename).write_text(source)
                result, data = self.run_query("check", "--path", filename)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIs(data["valid"], False)
                self.assertTrue(data["errors"])
                self.assertFalse(data["partial"])
                for error in data["errors"]:
                    self.assertEqual(error["file"], filename)
                    self.assertGreaterEqual(error["line"], 1)
                    self.assertGreaterEqual(error["column"], 1)

    def test_check_directory_ignores_result_limit_and_symbols(self):
        (self.work / "main.py").write_text("def hello(): pass\n" * 250)
        (self.work / "z_bad.rs").write_text("fn bad() {\n")
        result, data = self.run_query("check", "--limit", "1")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(data["scanned_files"], 4)
        self.assertEqual(data["results"], [])
        self.assertIs(data["valid"], False)
        self.assertFalse(data["truncated"])
        self.assertTrue(any(error["file"] == "z_bad.rs" for error in data["errors"]))

    def test_check_comments_and_strings_are_not_brackets(self):
        samples = {
            "strings.py": 's = "[(}"\n# }])\n',
            "strings.kt": '/* { /* [ */ } */\nval s = "[(}"\nval raw = """{[("""\n',
            "strings.rs": '/* { /* [ */ } */\nfn main() { let s = r#"[(}"#; }\n',
        }
        for filename, source in samples.items():
            (self.work / filename).write_text(source)
            _, data = self.run_query("check", "--path", filename)
            self.assertTrue(data["valid"], (filename, data["errors"]))

    def test_check_missing_escape_and_symlink_keep_original_errors(self):
        outside = self.root / "outside.py"
        outside.write_text("raise RuntimeError()\n")
        (self.work / "linked.py").symlink_to(outside)
        for path, expected in (("missing.py", "FILE_NOT_FOUND"),
                               ("../outside.py", "OUTSIDE_WORKSPACE"),
                               ("linked.py", "OUTSIDE_WORKSPACE")):
            result, data = self.run_query("check", "--path", path)
            self.assertEqual(result.returncode, 2)
            self.assertEqual(data["error"], expected)

    def test_check_oversized_binary_and_unsupported_are_incomplete(self):
        for filename, source in (("large.py", b"#" * 1_048_577),
                                 ("binary.kt", b"\x00"), ("unknown.txt", b"text")):
            (self.work / filename).write_bytes(source)
            result, data = self.run_query("check", "--path", filename)
            self.assertEqual(result.returncode, 0)
            self.assertIsNone(data["valid"])
            self.assertTrue(data["partial"])
            self.assertTrue(data["errors"])

    def test_check_file_limit_is_explicit(self):
        for index in range(402):
            (self.work / f"file_{index:03}.py").write_text("x = 1\n")
        result, data = self.run_query("check", "--limit", "1")
        self.assertEqual(result.returncode, 0)
        self.assertEqual(data["scanned_files"], 400)
        self.assertIsNone(data["valid"])
        self.assertTrue(data["partial"])
        self.assertTrue(data["truncated"])
        self.assertLessEqual(len(result.stdout), 24000)

    def test_check_diagnostics_overflow_still_scans_later_files(self):
        (self.work / "aaa.kt").write_text(")\n" * 12000)
        (self.work / "zzz.py").write_text("x = 1\n")
        result, data = self.run_query("check")
        self.assertEqual(result.returncode, 0)
        self.assertEqual(data["scanned_files"], 5)
        self.assertIs(data["valid"], False)
        self.assertTrue(data["partial"])
        self.assertTrue(data["truncated"])
        self.assertLessEqual(len(result.stdout), 24000)

    def test_check_does_not_extract_symbols(self):
        code = (
            "import runpy, sys; "
            f"module = runpy.run_path({str(QUERY)!r}); "
            "main = module['main']; "
            "main.__globals__['extract_symbols'] = lambda *a: (_ for _ in ()).throw(AssertionError('symbols')); "
            "sys.argv = ['query.py', 'check']; sys.exit(main())"
        )
        result = subprocess.run([sys.executable, "-I", "-c", code], cwd=self.work,
                                capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(json.loads(result.stdout)["valid"])

    def test_status_never_installs_or_starts_external_tools(self):
        result, data = self.run_query("status")
        self.assertEqual(result.returncode, 0)
        self.assertEqual(data["languages"], ["kotlin", "python", "rust"])
        self.assertFalse(data["installs_runtime"])
        self.assertEqual(data["index"], "fresh-bounded-snapshot")
        self.assertFalse((self.work / ".codegraph").exists())

    def test_oversized_and_binary_source_are_explicitly_skipped(self):
        (self.work / "large.py").write_bytes(b"#" * 1_048_577)
        (self.work / "binary.kt").write_bytes(b"\x00\xff")
        _, data = self.run_query("map")
        self.assertTrue(data["partial"])
        self.assertEqual({item["file"] for item in data["errors"]}, {"binary.kt", "large.py"})


if __name__ == "__main__":
    unittest.main()
