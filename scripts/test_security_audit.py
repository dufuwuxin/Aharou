"""Behavioral fixtures for the bundled offline audit CLI; no external dependencies."""
from __future__ import annotations

import contextlib
import hashlib
import importlib.util
import io
import json
import socket
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "app/src/main/assets/skills/security-audit/scripts/audit.py"
SPEC = importlib.util.spec_from_file_location("offline_security_audit", SCRIPT)
assert SPEC and SPEC.loader
AUDIT = importlib.util.module_from_spec(SPEC)
with patch.object(sys, "dont_write_bytecode", True):
    SPEC.loader.exec_module(AUDIT)


class SecurityAuditTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def sample(self, data: bytes, name: str = "sample.bin") -> Path:
        path = self.root / name
        path.write_bytes(data)
        return path

    def cli(self, mode: str, path: Path | None = None) -> tuple[int, dict]:
        args = [mode] if path is None else [mode, str(path), "--root", str(self.root)]
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = AUDIT.main(args)
        return code, json.loads(output.getvalue())

    def archive(self, names: dict[str, bytes], compression=zipfile.ZIP_STORED) -> Path:
        path = self.root / "sample.zip"
        with zipfile.ZipFile(path, "w", compression=compression) as archive:
            for name, data in names.items():
                archive.writestr(name, data)
        return path

    def test_sha256_and_unknown_format(self):
        data = b"ordinary file"
        code, result = self.cli("inventory", self.sample(data))
        self.assertEqual(code, 0)
        self.assertEqual(result["sha256"], hashlib.sha256(data).hexdigest())
        self.assertEqual(result["size_bytes"], len(data))
        self.assertEqual(result["metadata"]["format"], "unknown")

    def test_elf_header(self):
        header = bytearray(64)
        header[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<HH", header, 16, 3, 183)
        code, result = self.cli("inventory", self.sample(header))
        self.assertEqual(code, 0)
        self.assertEqual(result["metadata"]["machine"], 183)
        self.assertEqual(result["metadata"]["bits"], 64)

    def test_pe_header(self):
        header = bytearray(88)
        header[:2] = b"MZ"
        struct.pack_into("<I", header, 60, 64)
        header[64:68] = b"PE\0\0"
        struct.pack_into("<HH", header, 68, 0x8664, 3)
        code, result = self.cli("inventory", self.sample(header))
        self.assertEqual(code, 0)
        self.assertEqual(result["metadata"]["sections"], 3)

    def test_dex_header(self):
        header = bytearray(112)
        header[:8] = b"dex\n039\0"
        struct.pack_into("<III", header, 32, 112, 112, 0x12345678)
        code, result = self.cli("inventory", self.sample(header))
        self.assertEqual(code, 0)
        self.assertEqual(result["metadata"]["version"], "039")
        self.assertFalse(result["metadata"]["integrity_verified"])

    def test_malformed_binary_headers(self):
        for data in (b"MZ", b"\x7fELF", b"dex\n999\0", b"PK\x03\x04bad"):
            with self.subTest(data=data):
                code, result = self.cli("inventory", self.sample(data))
                self.assertEqual(code, 2)
                self.assertEqual(result["assessment"], "not_assessed")

    def test_apk_candidate_does_not_read_members(self):
        path = self.archive({"AndroidManifest.xml": b"opaque", "classes.dex": b"not a dex"})
        with patch.object(zipfile.ZipFile, "open", side_effect=AssertionError("member read")), \
                patch.object(zipfile.ZipFile, "extractall", side_effect=AssertionError("extraction")):
            code, result = self.cli("inventory", path)
        self.assertEqual(code, 0)
        self.assertTrue(result["metadata"]["apk_candidate"])
        self.assertFalse(result["metadata"]["signature_verified"])
        self.assertFalse(result["metadata"]["contents_read"])

    def test_empty_zip(self):
        code, result = self.cli("inventory", self.archive({}))
        self.assertEqual(code, 0)
        self.assertEqual(result["metadata"]["entry_count"], 0)

    def test_zip_bomb_and_traversal_metadata_only(self):
        path = self.archive({"../../outside": b"A" * 100000, "nested.zip": b"PK\x03\x04"}, zipfile.ZIP_DEFLATED)
        code, result = self.cli("inventory", path)
        self.assertEqual(code, 0)
        self.assertEqual(result["metadata"]["unsafe_path_count"], 1)
        self.assertGreater(result["metadata"]["high_ratio_count"], 0)
        self.assertFalse((self.root.parent / "outside").exists())
        self.assertFalse(result["metadata"]["nested_archives_inspected"])

    def test_zip_declared_expansion_limit(self):
        path = self.archive({"tiny": b"x"})
        raw = bytearray(path.read_bytes())
        offset = raw.index(b"PK\x01\x02")
        struct.pack_into("<I", raw, offset + 24, AUDIT.MAX_EXPANDED + 1)
        path.write_bytes(raw)
        code, result = self.cli("inventory", path)
        self.assertEqual(code, 0)
        self.assertTrue(result["metadata"]["expansion_limit_exceeded"])

    def test_zip_directory_limit_before_parser(self):
        path = self.archive({"one": b"x"})
        with patch.object(AUDIT, "MAX_ENTRIES", 0), \
                patch.object(zipfile, "ZipFile", side_effect=AssertionError("allocated early")):
            code, result = self.cli("inventory", path)
        self.assertEqual(code, 2)
        self.assertEqual(result["error"], "zip_directory_limit")

    def test_zip_count_lie_rejected(self):
        path = self.archive({"one": b"x"})
        raw = bytearray(path.read_bytes())
        offset = raw.rindex(b"PK\x05\x06")
        struct.pack_into("<HH", raw, offset + 8, 0, 0)
        path.write_bytes(raw)
        code, result = self.cli("inventory", path)
        self.assertEqual(code, 2)
        self.assertEqual(result["error"], "invalid_zip_directory")

    def test_zip64_and_multidisk_are_explicitly_unsupported(self):
        for count, disk, expected in ((65535, 0, "zip64_unsupported"),
                                      (1, 1, "multidisk_zip_unsupported")):
            path = self.archive({"one": b"x"})
            raw = bytearray(path.read_bytes())
            offset = raw.rindex(b"PK\x05\x06")
            struct.pack_into("<H", raw, offset + 4, disk)
            struct.pack_into("<HH", raw, offset + 8, count, count)
            path.write_bytes(raw)
            code, result = self.cli("inventory", path)
            self.assertEqual(code, 2)
            self.assertEqual(result["error"], expected)

    def test_zip_names_are_not_reported_and_links_are_counted(self):
        path = self.root / "sample.zip"
        with zipfile.ZipFile(path, "w") as archive:
            entry = zipfile.ZipInfo("private-marker")
            entry.create_system = 3
            entry.external_attr = 0o120777 << 16
            archive.writestr(entry, "outside")
        code, result = self.cli("inventory", path)
        self.assertEqual(code, 0)
        self.assertEqual(result["metadata"]["symlink_count"], 1)
        self.assertNotIn("private-marker", json.dumps(result))

    def test_changed_sample_is_not_accepted(self):
        path = self.sample(b"before")
        original = AUDIT.binary_metadata

        def change_file(stream, size):
            result = original(stream, size)
            path.write_bytes(b"changed and longer")
            return result

        with patch.object(AUDIT, "binary_metadata", side_effect=change_file):
            code, result = self.cli("inventory", path)
        self.assertEqual(code, 2)
        self.assertEqual(result["error"], "sample_changed_during_read")

    def test_all_rule_groups_are_hints_without_raw_values(self):
        text = '''db.execute("SELECT " + user)
node.innerHTML = input
@csrf_exempt
requests.get(user_url)
os.system(command)
open(base + user)
<!DOCTYPE unsafe>
password = "fixture-secret-do-not-print"
<uses-permission android:name="anything"/>
BOOT_COMPLETED
'''
        code, result = self.cli("source", self.sample(text.encode(), "sample.py"))
        self.assertEqual(code, 0)
        self.assertEqual({finding["group"] for finding in result["findings"]},
                         {group for _, group, _, _ in AUDIT.RULES})
        self.assertTrue(all(f["status"] == "manual_review_required" for f in result["findings"]))
        self.assertNotIn("fixture-secret-do-not-print", json.dumps(result))
        self.assertNotIn("user_url", json.dumps(result))
        self.assertEqual(result["findings"][0]["line"], 1)

    def test_no_findings_is_not_safe(self):
        code, result = self.cli("source", self.sample(b"print(42)\n"))
        self.assertEqual(code, 0)
        self.assertEqual(result["findings"], [])
        self.assertIn("no_safety_verdict", result["coverage"])

    def test_unicode_and_bom_source(self):
        code, result = self.cli("source", self.sample("\ufeff# 中文注释\nemail = 'fixture'\n".encode()))
        self.assertEqual(code, 0)
        self.assertEqual(result["findings"][0]["line"], 2)

    def test_source_encoding_and_line_limits(self):
        for data, expected in ((b"\xff", "source_utf8_required"), (b"a\0b", "source_text_required"),
                               (b"x" * 16385, "source_line_limit")):
            code, result = self.cli("source", self.sample(data))
            self.assertEqual(code, 2)
            self.assertEqual(result["error"], expected)

    def test_finding_limit_is_explicit(self):
        path = self.sample(b"os.system(command)\n" * 501)
        code, result = self.cli("source", path)
        self.assertEqual(code, 0)
        self.assertEqual(len(result["findings"]), 500)
        self.assertTrue(result["findings_truncated"])

    def test_boundaries_and_credentials_rejected_before_open(self):
        outside = self.root.parent / "not-authorized"
        for path, expected in ((outside, "outside_authorized_root"),
                               (self.root / "tokens.json", "credential_path_not_allowed"),
                               (self.root / ".env.production", "credential_path_not_allowed")):
            with patch.object(AUDIT.os, "open", side_effect=AssertionError("opened")):
                code, result = self.cli("inventory", path)
            self.assertEqual(code, 2)
            self.assertEqual(result["error"], expected)

    def test_symlink_and_directory_rejected(self):
        target = self.sample(b"ordinary")
        link = self.root / "link"
        link.symlink_to(target)
        code, result = self.cli("inventory", link)
        self.assertEqual(code, 2)
        self.assertEqual(result["error"], "symlink_not_allowed")
        code, result = self.cli("inventory", self.root)
        self.assertEqual(code, 2)
        self.assertEqual(result["error"], "regular_file_required")

    def test_size_limit(self):
        path = self.sample(b"12345")
        with patch.object(AUDIT, "MAX_SAMPLE", 4):
            code, result = self.cli("inventory", path)
        self.assertEqual(code, 2)
        self.assertEqual(result["error"], "sample_size_limit")

    def test_no_network_no_execution_no_install_and_tool_discovery(self):
        path = self.sample(b"import os\nos.system('never execute this')\n")
        with patch.object(socket, "socket", side_effect=AssertionError("network")), \
                patch.object(socket, "create_connection", side_effect=AssertionError("network")), \
                patch.object(subprocess, "Popen", side_effect=AssertionError("execution")), \
                patch.object(AUDIT.os, "system", side_effect=AssertionError("execution")), \
                patch.object(AUDIT.shutil, "which", return_value=None) as discover:
            code, result = self.cli("source", path)
            self.assertEqual(code, 0)
            code, tools = self.cli("tools")
            self.assertEqual(code, 0)
            self.assertEqual(discover.call_count, len(AUDIT.TOOLS))
            self.assertFalse(any(tools["tools"].values()))
            self.assertFalse(tools["executed"])
            self.assertFalse(tools["installed"])
        self.assertEqual(path.read_bytes(), b"import os\nos.system('never execute this')\n")

    def test_report_identity_and_evidence_kind(self):
        path = self.sample(b"ordinary UTF-8 text")
        for mode, kind in (("inventory", "static_metadata"),
                           ("source", "static_heuristics"), ("tools", "tool_discovery")):
            with self.subTest(mode=mode):
                code, result = self.cli(mode, None if mode == "tools" else path)
                self.assertEqual(code, 0)
                self.assertEqual(result["report_schema"], 1)
                self.assertEqual(result["analyzer"]["name"], "aharou-offline-audit")
                self.assertRegex(result["analyzer"]["version"], r"^\d+\.\d+\.\d+$")
                self.assertRegex(result["analyzer"]["ruleset_sha256"], r"^[a-f0-9]{64}$")
                self.assertEqual(result["evidence"], {
                    "kind": kind, "dynamic_analysis_performed": False})

    def test_failed_report_is_versioned_and_not_assessed(self):
        for mode in ("inventory", "source"):
            with self.subTest(mode=mode):
                code, result = self.cli(mode, self.root / "private-marker-missing")
                self.assertEqual(code, 2)
                self.assertEqual(result["report_schema"], 1)
                self.assertEqual(result["analyzer"]["name"], "aharou-offline-audit")
                self.assertEqual(result["evidence"], {
                    "kind": "not_assessed", "dynamic_analysis_performed": False})
                self.assertNotIn(str(self.root), json.dumps(result))

    def test_ruleset_fingerprint_tracks_executed_patterns(self):
        path = self.sample(b"ordinary text")
        _, before = self.cli("source", path)
        _, repeat = self.cli("source", path)
        self.assertEqual(before["analyzer"], repeat["analyzer"])
        with patch.object(AUDIT, "COMPILED_RULES", AUDIT.COMPILED_RULES[:-1]):
            _, changed = self.cli("source", path)
        self.assertNotEqual(before["analyzer"]["ruleset_sha256"],
                            changed["analyzer"]["ruleset_sha256"])
        self.assertEqual(before["sha256"], changed["sha256"])

    def test_tool_matrix_distinguishes_discovery_from_execution(self):
        with patch.object(AUDIT.shutil, "which", side_effect=lambda name:
                          "/private-marker/bin/" + name if name == "jadx" else None), \
                patch.object(subprocess, "Popen", side_effect=AssertionError("execution")):
            code, result = self.cli("tools")
        self.assertEqual(code, 0)
        self.assertEqual(set(result["tool_matrix"]), {"native", "android", "firmware"})
        self.assertEqual(result["tool_matrix"]["android"]["jadx"], {
            "status": "located_unverified", "version": None})
        self.assertEqual(result["tool_matrix"]["android"]["apktool"], {
            "status": "missing", "version": None})
        covered = {name for domain in result["tool_matrix"].values() for name in domain}
        self.assertEqual(covered, set(AUDIT.TOOLS))
        for domain in result["tool_matrix"].values():
            for name, entry in domain.items():
                self.assertEqual(result["tools"][name], entry["status"] == "located_unverified")
        self.assertNotIn("private-marker", json.dumps(result))
        self.assertFalse(result["executed"])
        self.assertFalse(result["installed"])

    def test_argument_errors_keep_argparse_contract(self):
        for args in ([], ["source", "sample.py"], ["unsupported"]):
            with self.subTest(args=args):
                output, errors = io.StringIO(), io.StringIO()
                with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
                    with self.assertRaises(SystemExit) as raised:
                        AUDIT.main(args)
                self.assertEqual(raised.exception.code, 2)
                self.assertEqual(output.getvalue(), "")
                self.assertIn("error:", errors.getvalue())

    def test_errors_do_not_echo_paths_or_contents(self):
        code, result = self.cli("inventory", self.root / "private-marker-not-present")
        self.assertEqual(code, 2)
        self.assertNotIn("private-marker", json.dumps(result))
        self.assertNotIn(str(self.root), json.dumps(result))


if __name__ == "__main__":
    unittest.main()
