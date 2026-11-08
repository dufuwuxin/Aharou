#!/usr/bin/env python3
"""Isolated Python fixtures only: never run sudo/apt/npm/pnpm/cargo/rustup."""

import io
import json
import os
import subprocess
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import build_language_extensions as build
import vendor_language_extensions as vendor

FAKE_LSP = '''import json, sys
while True:
    headers = {}
    while True:
        line = sys.stdin.buffer.readline()
        if not line: sys.exit(2)
        if line == b"\\r\\n": break
        key, value = line.decode().split(":", 1)
        headers[key.lower()] = value.strip()
    message = json.loads(sys.stdin.buffer.read(int(headers['content-length'])))
    method = message.get('method')
    if method == 'exit': sys.exit(0)
    if 'id' in message:
        result = {'capabilities': {}} if method == 'initialize' else None
        data = json.dumps({'jsonrpc': '2.0', 'id': message['id'], 'result': result}).encode()
        sys.stdout.buffer.write(('Content-Length: %d\\r\\n\\r\\n' % len(data)).encode() + data)
        sys.stdout.buffer.flush()
'''


class BuildTests(unittest.TestCase):
    def test_real_stdio_handshake_and_exit(self):
        build.probe_lsp([sys.executable, "-c", FAKE_LSP], timeout=3)

    def test_bad_handshake_and_exit_are_rejected(self):
        for program in ("print('garbage')", FAKE_LSP.replace("sys.exit(0)", "sys.exit(4)"),
                        FAKE_LSP.replace("{'capabilities': {}}", "{}")):
            with self.subTest(program=program[:30]), self.assertRaises((ValueError, KeyError)):
                build.probe_lsp([sys.executable, "-c", program], timeout=3)

    def test_relative_node_dependency_graph_survives_move(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source = root / "source"
            dep = source / "node_modules/dep"
            dep.mkdir(parents=True)
            (source / "package.json").write_text(json.dumps({"name": "app", "dependencies": {"dep": "1"}}))
            (source / "main.js").write_text("require('dep')")
            (dep / "package.json").write_text(json.dumps({"name": "dep", "main": "index.js"}))
            (dep / "index.js").write_text("module.exports=1")
            payload = root / "payload"
            payload.mkdir()
            main = build.package_node(source, payload)
            relative = main.relative_to(payload)
            moved = root / "new path"
            payload.rename(moved)
            link = moved / relative / "node_modules/dep"
            self.assertFalse(os.path.isabs(os.readlink(link)))
            self.assertEqual("module.exports=1", (link / "index.js").read_text())
            self.assertEqual([], build.audit_payload(moved))

    def test_python_runtime_has_relative_launch_not_venv(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            release = root / "release"
            module = release / "lib/pylsp"
            module.mkdir(parents=True)
            (module / "__init__.py").write_text("")
            (module / "__main__.py").write_text(FAKE_LSP)
            payload = root / "payload"
            bundle = vendor.BUNDLES / "python/bundle"
            build.assemble("python", release, payload, bundle)
            moved = root / "space runtime"
            payload.rename(moved)
            self.assertNotIn(str(release), (moved / "launch.sh").read_text())
            build.probe_lsp([str(moved / "launch.sh")], timeout=3)
            archive = root / "python.tar.gz"
            build.pack(moved, archive)
            with tarfile.open(archive) as packed:
                self.assertIn("launch.sh", packed.getnames())
                self.assertFalse(any("venv" in name for name in packed.getnames()))

    def test_outside_links_and_wrong_arch_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            link = root / "outside"
            link.symlink_to("/etc/passwd")
            with self.assertRaises(ValueError):
                build.audit_payload(root)
            link.unlink()
            (root / "wrong-arch").write_bytes(b"\x7fELF\x02\x01" + b"\x00" * 14)
            with self.assertRaises(ValueError):
                build.audit_payload(root)

    def test_source_traversal_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            with tarfile.open(root / "source.tar.gz", "w:gz") as archive:
                member = tarfile.TarInfo("root/../../escape")
                member.size = 1
                archive.addfile(member, io.BytesIO(b"x"))
            with self.assertRaises((ValueError, tarfile.TarError)):
                build.extract_source(root, root / "extracted")
            self.assertFalse((root / "escape").exists())

    def test_failed_build_leaves_no_success_metadata(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            stale = root / "python.json"
            stale.write_text("stale")
            with patch.dict(os.environ, {"GITHUB_ACTIONS": "true"}), \
                 patch.object(build.platform, "machine", return_value="aarch64"), \
                 patch.object(build.platform, "freedesktop_os_release", return_value={"ID": "ubuntu", "VERSION_ID": "24.04"}), \
                 patch.object(build.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "fake-command")) as runner:
                with self.assertRaises(subprocess.CalledProcessError):
                    build.build("python", root)
                self.assertEqual(1, runner.call_count)
            self.assertFalse(stale.exists())
            self.assertFalse((root / "python-linux-arm64.tar.gz").exists())

    def test_manifest_requires_all_seven_and_matching_hashes(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder)
            catalog = json.loads((vendor.ASSETS / "catalog.json").read_text())
            for source in catalog["extensions"]:
                artifact = output / f"{source['id']}-linux-arm64.tar.gz"
                artifact.write_bytes(b"isolated-test-fixture")
                entry = {key: source[key] for key in ("id", "command", "revision", "sourceSha256", "version")}
                entry.update(fileName=artifact.name, sha256=vendor.digest(artifact), sizeBytes=artifact.stat().st_size)
                (output / f"{source['id']}.json").write_text(json.dumps(entry))
            build.manifest(output)
            self.assertEqual(7, len(json.loads((output / "manifest.json").read_text())["extensions"]))
            (output / "lua-linux-arm64.tar.gz").write_bytes(b"tampered")
            with self.assertRaises(ValueError):
                build.manifest(output)
            self.assertFalse((output / "manifest.json").exists())
            (output / "lua.json").unlink()
            with self.assertRaises(FileNotFoundError):
                build.manifest(output)
            self.assertFalse((output / "manifest.json").exists())


if __name__ == "__main__":
    unittest.main()
