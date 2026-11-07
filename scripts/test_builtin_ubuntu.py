#!/usr/bin/env python3
"""Offline isolated fixtures; never execute or extract a rootfs."""

import hashlib
import io
import json
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import check_builtin_container as gate
import prepare_builtin_ubuntu as prepare


class BuiltinUbuntuTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.archive = self.root / "fixture.tar.gz"
        self.network = patch("urllib.request.urlopen", side_effect=AssertionError("real network forbidden"))
        self.network.start()
        self.addCleanup(self.network.stop)

    def fixture(self, *, machine=183, elf_class=2, distro="ubuntu", version="24.04",
                target="dash", missing=False, alpine=False, directory_link=False):
        with tarfile.open(self.archive, "w:gz") as archive:
            def file(name, data):
                info = tarfile.TarInfo(name)
                info.size = len(data)
                archive.addfile(info, io.BytesIO(data))

            for name in ("etc", "usr", "usr/bin"):
                info = tarfile.TarInfo(name)
                info.type = tarfile.DIRTYPE
                archive.addfile(info)
            if directory_link:
                info = tarfile.TarInfo("bin")
                info.type = tarfile.SYMTYPE
                info.linkname = "usr/bin"
            else:
                info = tarfile.TarInfo("bin")
                info.type = tarfile.DIRTYPE
            archive.addfile(info)
            file("etc/os-release", f'ID={distro}\nVERSION_ID="{version}"\n'.encode())
            if alpine:
                file("etc/alpine-release", b"3.24")
            prefix = "usr/bin" if directory_link else "bin"
            if not missing:
                info = tarfile.TarInfo(prefix + "/sh")
                info.type = tarfile.SYMTYPE
                info.linkname = target
                archive.addfile(info)
            elf = bytearray(64)
            elf[:6] = b"\x7fELF" + bytes([elf_class, 1])
            elf[18:20] = machine.to_bytes(2, "little")
            file(prefix + "/dash", bytes(elf))
        return hashlib.sha256(self.archive.read_bytes()).hexdigest()

    def test_native_arm64_shell_accepts_relative_absolute_and_directory_links(self):
        for target, directory in (("dash", False), ("/bin/dash", False), ("dash", True),
                                  ("/usr/bin/dash", True)):
            with self.subTest(target=target, directory=directory):
                self.fixture(target=target, directory_link=directory)
                self.assertEqual(183, prepare.inspect_rootfs(self.archive)["shellMachine"])

    def test_wrong_isa_class_alpine_version_missing_shell_and_escape_are_rejected(self):
        for kwargs in ({"machine": 62}, {"machine": 40, "elf_class": 1}, {"elf_class": 1},
                       {"distro": "alpine"}, {"version": "22.04"}, {"missing": True},
                       {"alpine": True}, {"target": "../../outside"}, {"target": "sh"}):
            with self.subTest(kwargs=kwargs):
                self.fixture(**kwargs)
                with self.assertRaises(ValueError):
                    prepare.inspect_rootfs(self.archive)

    def test_prepare_and_readonly_gate_match_original_bytes(self):
        digest = self.fixture(directory_link=True)
        sums = self.root / "SHA256SUMS"
        sums.write_text(f"{digest} *{prepare.FILENAME}\n")
        with patch.object(prepare, "SHA256", digest):
            metadata = prepare.prepare(self.root, self.archive, sums)
            self.assertEqual(self.archive.read_bytes(), (self.root / prepare.ASSET).read_bytes())
            self.assertEqual(metadata, gate.check(self.root))
            snapshot = (self.root / prepare.METADATA).read_bytes()
            self.assertEqual(metadata, prepare.prepare(self.root, self.archive, sums))
            self.assertEqual(snapshot, (self.root / prepare.METADATA).read_bytes())
            metadata["architecture"] = "amd64"
            (self.root / prepare.METADATA).write_text(json.dumps(metadata))
            with self.assertRaises(ValueError):
                gate.check(self.root)

    def test_bad_hash_bad_elf_or_missing_official_target_never_publishes(self):
        for kwargs, bad_hash, no_target in (({}, True, False), ({"machine": 62}, False, False),
                                            ({}, False, True)):
            with self.subTest(kwargs=kwargs, bad_hash=bad_hash, no_target=no_target):
                digest = self.fixture(**kwargs)
                sums = self.root / "SHA256SUMS"
                sums.write_text("" if no_target else f"{digest}  {prepare.FILENAME}\n")
                with patch.object(prepare, "SHA256", "0" * 64 if bad_hash else digest):
                    with self.assertRaises(ValueError):
                        prepare.prepare(self.root, self.archive, sums)
                self.assertFalse((self.root / prepare.ASSET).exists())
                self.assertFalse((self.root / prepare.METADATA).exists())

    def test_archive_hash_mismatch_keeps_previous_asset_and_metadata(self):
        digest = self.fixture()
        sums = self.root / "SHA256SUMS"
        sums.write_text(f"{digest}  {prepare.FILENAME}\n")
        asset = self.root / prepare.ASSET
        metadata = self.root / prepare.METADATA
        asset.parent.mkdir(parents=True)
        metadata.parent.mkdir(parents=True)
        asset.write_bytes(b"previous-asset")
        metadata.write_bytes(b"previous-metadata")
        self.archive.write_bytes(self.archive.read_bytes() + b"corruption")
        with patch.object(prepare, "SHA256", digest), self.assertRaisesRegex(ValueError, "SHA-256"):
            prepare.prepare(self.root, self.archive, sums)
        self.assertEqual(b"previous-asset", asset.read_bytes())
        self.assertEqual(b"previous-metadata", metadata.read_bytes())

    def test_upstream_without_target_does_not_download_archive_or_write_outputs(self):
        def missing_target(url, destination):
            self.assertTrue(url.endswith("/SHA256SUMS"))
            destination.write_text("no arm64 target\n")

        with patch.object(prepare, "fetch", side_effect=missing_target) as fetch:
            with self.assertRaises(ValueError):
                prepare.prepare(self.root)
        self.assertEqual(1, fetch.call_count)
        self.assertFalse((self.root / prepare.ASSET).exists())
        self.assertFalse((self.root / prepare.METADATA).exists())

    def test_gate_rejects_remaining_alpine_asset(self):
        old = self.root / "app/src/_armAssets/container/arm/alpine-rootfs.bin"
        old.parent.mkdir(parents=True)
        old.write_bytes(b"old")
        with self.assertRaisesRegex(ValueError, "Alpine"):
            gate.check(self.root)

    def test_checksum_requires_one_exact_filename(self):
        good = f"{prepare.SHA256}  {prepare.FILENAME}\n"
        for text in ("", good + good, good.replace("arm64", "amd64")):
            with self.subTest(text=text), self.assertRaises(ValueError):
                prepare.official_checksum(text)


if __name__ == "__main__":
    unittest.main()
