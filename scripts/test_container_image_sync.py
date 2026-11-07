#!/usr/bin/env python3
"""Offline rootfs sync regressions; no real network or upload is allowed."""

import contextlib
import copy
import hashlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("container_sync", ROOT / "scripts/sync-container-images.py")
SYNC = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SYNC)


class ContainerImageSyncTest(unittest.TestCase):
    def setUp(self):
        self.network = patch("urllib.request.urlopen", side_effect=AssertionError("real network forbidden"))
        self.network.start()
        self.addCleanup(self.network.stop)
        self.entry = {
            "id": "void-glibc", "name": "Void Linux (glibc)", "distro": "void",
            "version": "0", "sizeBytes": 0,
            "path": "live/current/void-{abi}-ROOTFS-0.tar.xz",
            "paths": {"aharou": "void-glibc-{abi}.tar.xz"},
            "abiNames": {"arm64": "aarch64"},
        }
        self.payload = b"isolated rootfs payload"
        self.digest = hashlib.sha256(self.payload).hexdigest()
        self.info = {
            "version": "20250202", "base": "https://repo-default.voidlinux.org",
            "urls": {"arm64": "https://repo-default.voidlinux.org/live/current/void-aarch64-ROOTFS-20250202.tar.xz"},
            "sizes": {"arm64": len(self.payload)}, "extra": "fixture",
        }

    def test_retire_offline_removes_alpine_and_foreign_architectures(self):
        original = {"sources": {"official": {"distros": {"alpine": "old", "ubuntu": "keep"}}},
                    "images": [dict(self.entry),
                               dict(self.entry, id="alpine-old", distro="alpine"),
                               dict(self.entry, id="amd64-only", abiNames={"x86_64": "amd64"})]}
        original["images"][0]["sha256ByAbi"] = {"arm64": "preserved", "x86_64": "remove"}
        with tempfile.TemporaryDirectory() as directory:
            paths = [str(Path(directory) / name) for name in ("one.json", "two.json")]
            for path in paths:
                Path(path).write_text(json.dumps(original))
            with patch.object(SYNC, "MANIFESTS", paths), patch("sys.argv", ["sync", "--retire-unsupported"]):
                self.assertEqual(0, SYNC.main())
                first = Path(paths[0]).read_bytes()
                self.assertEqual(0, SYNC.main())
                self.assertEqual(first, Path(paths[1]).read_bytes())
            data = json.loads(first)
            self.assertEqual(["void-glibc"], [e["id"] for e in data["images"]])
            self.assertEqual({"arm64": "preserved"}, data["images"][0]["sha256ByAbi"])
            self.assertEqual({"ubuntu": "keep"}, data["sources"]["official"]["distros"])

    def test_void_is_a_supported_cli_distro(self):
        with patch("sys.argv", ["sync", "--distro", "void"]):
            self.assertEqual(SYNC.parse_args().distro, "void")

    def test_void_path_is_glibc_rootfs_not_iso_or_musl(self):
        self.assertEqual(SYNC.path_for(self.entry, "20250202"),
                         "live/current/void-{abi}-ROOTFS-20250202.tar.xz")

    def test_discovery_excludes_musl_iso_and_platformfs(self):
        entries = {"void-aarch64-ROOTFS-20250202.tar.xz", "void-aarch64-ROOTFS-20240101.tar.xz",
                   "void-aarch64-musl-ROOTFS-20990101.tar.xz", "void-live-aarch64-20990101-base.iso",
                   "void-rpi-aarch64-PLATFORMFS-20990101.tar.xz"}
        with patch.object(SYNC, "dir_entries", return_value=entries):
            found = SYNC.discover(self.entry, self.info["base"])
        self.assertEqual(found["version"], "20250202")
        self.assertEqual(found["urls"], self.info["urls"])

    def test_discovery_requires_glibc_candidate(self):
        with patch.object(SYNC, "dir_entries", return_value={"void-aarch64-musl-ROOTFS-20250202.tar.xz"}):
            with self.assertRaises(RuntimeError):
                SYNC.discover(self.entry, self.info["base"])

    def test_bsd_checksum_is_matched_by_exact_filename(self):
        text = (f"SHA256 (void-aarch64-musl-ROOTFS-20250202.tar.xz) = {'f' * 64}\n"
                f"SHA256 (void-aarch64-ROOTFS-20250202.tar.xz) = {self.digest.upper()}\n")
        with patch.object(SYNC, "http_text", return_value=text) as fetch:
            self.assertEqual(SYNC.fetch_checksums(self.entry, self.info), {"arm64": self.digest})
        fetch.assert_called_once_with(self.info["base"] + "/live/current/sha256sum.txt")

    def test_missing_malformed_or_duplicate_checksum_is_rejected(self):
        good = f"SHA256 (void-aarch64-ROOTFS-20250202.tar.xz) = {self.digest}\n"
        for text in ("", good.replace(self.digest, "oops"), good + good):
            with self.subTest(text=text), patch.object(SYNC, "http_text", return_value=text):
                with self.assertRaises(RuntimeError):
                    SYNC.fetch_checksums(self.entry, self.info)

    def run_apply(self, *, checksum=None, upload_ok=True, download_ok=True, size=None):
        change = {"entry": self.entry, "info": copy.deepcopy(self.info)}
        before = copy.deepcopy(self.entry)

        def fake_download(url, dest):
            Path(dest).write_bytes(self.payload)
            return download_ok

        release = {"id": "fixture", "assets": [{"name": "void-glibc-aarch64.tar.xz", "size": len(self.payload)}]}
        with patch.object(SYNC, "ensure_release", return_value=release), \
                patch.object(SYNC, "fetch_checksums", return_value={"arm64": checksum or self.digest}), \
                patch.object(SYNC, "download", side_effect=fake_download) as download, \
                patch.object(SYNC, "upload", return_value=upload_ok) as upload, \
                contextlib.redirect_stdout(io.StringIO()):
            if size is not None:
                change["info"]["sizes"]["arm64"] = size
            applied, failed = SYNC.apply_changes([change])
        return applied, failed, download, upload, before

    def test_apply_rechecks_same_size_asset_before_manifest_update(self):
        applied, failed, download, upload, _ = self.run_apply()
        self.assertEqual(len(applied), 1)
        self.assertEqual(failed, [])
        download.assert_called_once()
        upload.assert_called_once()
        self.assertEqual(self.entry["sha256ByAbi"], {"arm64": self.digest})
        self.assertEqual(self.entry["version"], "20250202")
        self.assertEqual(self.entry["sizeBytes"], len(self.payload))

    def test_checksum_mismatch_never_uploads_or_changes_entry(self):
        applied, failed, _, upload, before = self.run_apply(checksum="0" * 64)
        self.assertEqual(applied, [])
        self.assertTrue(failed)
        upload.assert_not_called()
        self.assertEqual(self.entry, before)

    def test_download_size_or_upload_failure_keeps_entry_unchanged(self):
        for kwargs in ({"download_ok": False}, {"size": 999}, {"upload_ok": False}):
            with self.subTest(kwargs=kwargs):
                applied, failed, _, _, before = self.run_apply(**kwargs)
                self.assertEqual(applied, [])
                self.assertTrue(failed)
                self.assertEqual(self.entry, before)

    def test_missing_checksum_prevents_download_and_upload(self):
        change = {"entry": self.entry, "info": self.info}
        with patch.object(SYNC, "ensure_release", return_value={"id": "fixture"}), \
                patch.object(SYNC, "fetch_checksums", side_effect=RuntimeError("missing")), \
                patch.object(SYNC, "download") as download, patch.object(SYNC, "upload") as upload, \
                contextlib.redirect_stdout(io.StringIO()):
            applied, failed = SYNC.apply_changes([change])
        self.assertEqual(applied, [])
        self.assertTrue(failed)
        download.assert_not_called()
        upload.assert_not_called()

    def run_main(self, apply=False, token="", upload_ok=True):
        data = json.loads((ROOT / "data/container-images.json").read_text())
        before = copy.deepcopy(data)
        with tempfile.TemporaryDirectory() as directory, contextlib.ExitStack() as stack:
            manifests = [str(Path(directory) / name) for name in ("assets.json", "data.json")]
            for path in manifests:
                Path(path).write_text(json.dumps(data))
            stack.enter_context(patch.object(SYNC, "MANIFESTS", manifests))
            stack.enter_context(patch.object(SYNC, "parse_args", return_value=SimpleNamespace(
                apply=apply, distro="void", prune_unsupported_abis=False)))
            stack.enter_context(patch.object(SYNC, "CNB_TOKEN", token))
            stack.enter_context(patch.object(SYNC, "probe_versions", return_value=(copy.deepcopy(self.info), None)))
            stack.enter_context(patch.object(SYNC, "probe_artifacts", return_value=(copy.deepcopy(self.info), None)))
            stack.enter_context(patch.object(SYNC, "sync_eol_fields", return_value=[]))
            stack.enter_context(patch.object(SYNC, "report_unlisted_series"))
            stack.enter_context(patch.object(SYNC, "ensure_release", return_value={"id": "fixture"}))
            stack.enter_context(patch.object(SYNC, "fetch_checksums", return_value={"arm64": self.digest}))
            stack.enter_context(patch.object(SYNC, "upload", return_value=upload_ok))

            def fake_download(url, dest):
                Path(dest).write_bytes(self.payload)
                return True

            download = stack.enter_context(patch.object(SYNC, "download", side_effect=fake_download))
            stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
            rc = SYNC.main()
            self.assertEqual(Path(manifests[0]).read_bytes(), Path(manifests[1]).read_bytes())
            return rc, before, json.loads(Path(manifests[0]).read_text()), download.call_count

    def test_dry_run_never_publishes_seed_or_writes_manifest(self):
        rc, before, after, count = self.run_main()
        self.assertEqual(rc, 0)
        self.assertEqual(before, after)
        self.assertEqual(count, 0)

    def test_no_token_never_claims_void_downloadable(self):
        rc, before, after, count = self.run_main(apply=True)
        self.assertEqual(rc, 1)
        self.assertEqual(before, after)
        self.assertEqual(count, 0)

    def test_success_generates_both_manifests_preserving_existing_images(self):
        rc, before, after, count = self.run_main(apply=True, token="fixture")
        self.assertEqual(rc, 0)
        self.assertEqual(count, 1)
        self.assertEqual(after["images"][:-1], before["images"])
        void = after["images"][-1]
        self.assertEqual(void["distro"], "void")
        self.assertEqual(void["sha256ByAbi"], {"arm64": self.digest})
        self.assertEqual(void["abiNames"], {"arm64": "aarch64"})
        self.assertEqual(after["sources"]["official"]["distros"]["void"], self.info["base"])
        self.assertIn("void", after["sources"]["aharou"]["distros"])

    def test_failed_upload_does_not_publish_seed(self):
        rc, before, after, _ = self.run_main(apply=True, token="fixture", upload_ok=False)
        self.assertEqual(rc, 1)
        self.assertEqual(before, after)


if __name__ == "__main__":
    unittest.main()
