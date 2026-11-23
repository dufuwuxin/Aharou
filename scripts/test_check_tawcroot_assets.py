#!/usr/bin/env python3
"""Exercise identity failures independently of Android and the NDK."""

import copy
import hashlib
import importlib.util
import json
import struct
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "check_tawcroot_assets", Path(__file__).with_name("check_tawcroot_assets.py")
)
assert SPEC is not None and SPEC.loader is not None
assets = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(assets)


def executable() -> bytes:
    data = bytearray(120)
    data[:7] = b"\x7fELF\x02\x01\x01"
    struct.pack_into("<HHIQQ", data, 16, 2, 183, 1, 0x2000000040, 64)
    struct.pack_into("<HHH", data, 52, 64, 56, 1)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0x2000000000, 0, 120, 120, 4096)
    return bytes(data)


class TawcrootAssetsTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="tawc-native-test-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.manifest = json.loads((assets.ROOT / assets.MANIFEST_PATH).read_text())
        for rel in (assets.BINARY_PATH, assets.LICENSE_PATH):
            path = self.root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes((assets.ROOT / rel).read_bytes())

    def update_identity(self):
        for entry in (self.manifest["files"][0], self.manifest["licenseFile"]):
            data = (self.root / entry["repoPath"]).read_bytes()
            entry.update(sizeBytes=len(data), sha256=hashlib.sha256(data).hexdigest())

    def check(self):
        path = self.root / assets.MANIFEST_PATH
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(self.manifest))
        return assets.validate_assets(self.root)

    def test_real_vendored_asset_passes(self):
        self.assertEqual([], assets.validate_assets(assets.ROOT))

    def test_valid_static_fixture_has_expected_elf_shape(self):
        self.assertEqual([], assets.elf_problems(executable()))

    def test_same_size_binary_tamper_is_rejected(self):
        data = bytearray(executable())
        data[9] ^= 1
        (self.root / assets.BINARY_PATH).write_bytes(data)
        self.assertTrue(any("SHA-256 mismatch" in item for item in self.check()))

    def test_size_mismatch_is_rejected(self):
        path = self.root / assets.BINARY_PATH
        path.write_bytes(path.read_bytes()[:-1])
        self.assertTrue(any("size mismatch" in item for item in self.check()))

    def test_license_tamper_is_rejected(self):
        (self.root / assets.LICENSE_PATH).write_text("changed license")
        self.assertTrue(any("SHA-256 mismatch" in item for item in self.check()))

    def test_missing_binary_is_rejected(self):
        (self.root / assets.BINARY_PATH).unlink()
        self.assertTrue(self.check())

    def test_release_identity_changes_are_rejected(self):
        original = copy.deepcopy(self.manifest)
        for field, value in (("tag", "main"), ("sourceCommit", "missing"),
                             ("apkUrl", "https://example.com/runtime.apk"),
                             ("apkSha256", "0" * 64), ("license", "GPL-3.0"),
                             ("sourceCommit", "0" * 40)):
            with self.subTest(field=field):
                self.manifest = copy.deepcopy(original)
                self.manifest["origin"][field] = value
                self.assertTrue(self.check())

    def test_wrong_abi_path_and_role_are_rejected(self):
        for key, value in (("abi", "x86_64"), ("repoPath", "../escape.so"),
                           ("role", "testhost")):
            with self.subTest(key=key):
                original = self.manifest["files"][0][key]
                self.manifest["files"][0][key] = value
                self.assertTrue(self.check())
                self.manifest["files"][0][key] = original

    def test_runtime_contract_changes_are_rejected(self):
        self.manifest["runtimeContract"]["minimumAndroidApi"] = 26
        self.assertTrue(any("runtime contract" in item for item in self.check()))

    def test_empty_and_duplicate_files_are_rejected(self):
        original = self.manifest["files"]
        for entries in ([], original * 2):
            self.manifest["files"] = entries
            self.assertTrue(self.check())

    def test_malformed_manifest_is_rejected(self):
        path = self.root / assets.MANIFEST_PATH
        path.write_text("{")
        self.assertTrue(assets.validate_assets(self.root))
        for value in ([], None, {"schemaVersion": 2}):
            path.write_text(json.dumps(value))
            self.assertTrue(assets.validate_assets(self.root))

    def test_elf_shape_tampering_rejected_even_with_updated_hash(self):
        changes = ((4, "B", 1), (5, "B", 2), (16, "H", 3), (18, "H", 62),
                   (24, "Q", 0), (32, "Q", 4096), (54, "H", 1), (56, "H", 0),
                   (64, "I", 2), (64, "I", 3), (96, "Q", 4096), (104, "Q", 1))
        for offset, fmt, value in changes:
            with self.subTest(offset=offset, value=value):
                data = bytearray(executable())
                struct.pack_into("<" + fmt, data, offset, value)
                (self.root / assets.BINARY_PATH).write_bytes(data)
                self.update_identity()
                self.assertTrue(self.check())

    def test_truncated_elf_is_rejected(self):
        for size in (0, 7, 63, 119):
            with self.subTest(size=size):
                self.assertTrue(assets.elf_problems(executable()[:size]))


if __name__ == "__main__":
    unittest.main()
