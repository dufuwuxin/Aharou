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

    def test_pinned_lua_shutdown_requires_explicit_true_profile(self):
        program = FAKE_LSP.replace("else None", "else True")
        with self.assertRaises(ValueError):
            build.probe_lsp([sys.executable, "-c", program], timeout=3)
        build.probe_lsp([sys.executable, "-c", program], timeout=3, shutdown_result=True)
        with self.assertRaises(ValueError):
            build.probe_lsp([sys.executable, "-c", program.replace("else True", "else False")], timeout=3, shutdown_result=True)

    def test_failed_handshake_preserves_server_diagnostic(self):
        with self.assertRaisesRegex(ValueError, "fixture-runtime-error"):
            build.probe_lsp([sys.executable, "-c", "import sys; print('fixture-runtime-error', file=sys.stderr)"], timeout=3)

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

    def test_direct_transitive_import_is_linked_into_relocated_root(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source = root / "source"
            dep = source / "node_modules/vscode-languageserver-protocol"
            dep.mkdir(parents=True)
            (source / "package.json").write_text(json.dumps({"name": "yaml-language-server"}))
            (dep / "package.json").write_text(json.dumps({"name": "vscode-languageserver-protocol", "main": "index.js"}))
            (dep / "index.js").write_text("exports.protocol = true")
            payload = root / "payload"
            payload.mkdir()
            module = build.package_node(source, payload, ("vscode-languageserver-protocol",))
            link = module / "node_modules/vscode-languageserver-protocol"
            self.assertTrue(link.is_symlink())
            self.assertFalse(os.path.isabs(os.readlink(link)))
            self.assertEqual("exports.protocol = true", (link / "index.js").read_text())

    def test_private_node_launch_survives_move_without_global_node(self):
        for name in sorted(build.NODE):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                release = root / "release"
                package = release / "source" / build.PACKAGE_ROOT[name]
                package.mkdir(parents=True)
                (package / "package.json").write_text(json.dumps({"name": name}))
                entry = package / build.ENTRY[name]
                entry.parent.mkdir(parents=True, exist_ok=True)
                entry.write_text("fixture")
                for dependency in ("typescript", "vscode-languageserver-protocol"):
                    dep = package / "node_modules" / dependency
                    dep.mkdir(parents=True)
                    (dep / "package.json").write_text(json.dumps({"name": dependency}))
                binary = root / "build-node"
                binary.write_text(f'#!{sys.executable}\nimport sys\n'
                                  f'if sys.argv[1:] == ["--version"]: print("v{build.NODE_VERSION}"); sys.exit(0)\n' + FAKE_LSP)
                binary.chmod(0o755)
                payload = root / "payload"
                with patch.object(build.shutil, "which", return_value=str(binary)):
                    build.assemble(name, release, payload, vendor.BUNDLES / name / "bundle")
                moved = root / "new location with spaces"
                payload.rename(moved)
                self.assertEqual(vendor.digest(binary), vendor.digest(moved / "bin/node"))
                identity = json.loads((moved / "source.json").read_text())
                self.assertEqual(build.NODE_VERSION, identity["privateNodeVersion"])
                self.assertEqual(vendor.digest(binary), identity["privateNodeSha256"])
                self.assertNotIn(str(release), (moved / "launch.sh").read_text())
                tools = root / "tools"
                tools.mkdir()
                (tools / "dirname").symlink_to("/usr/bin/dirname")
                with patch.dict(os.environ, {"PATH": str(tools)}):
                    self.assertIsNone(build.shutil.which("node"))
                    build.probe_lsp([str(moved / "launch.sh")], timeout=3)

    def test_node_packaging_rejects_unpinned_runtime(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source = root / "release/source"
            source.mkdir(parents=True)
            (source / "package.json").write_text('{}')
            with patch.object(build.shutil, "which", return_value="/fake/node"), \
                 patch.object(build.subprocess, "check_output", return_value="v18.0.0\n"), \
                 patch.object(build, "package_node", return_value=root / "payload/modules/0"):
                with self.assertRaisesRegex(ValueError, "expected Node 24.22.2"):
                    build.assemble("yaml", root / "release", root / "payload", vendor.BUNDLES / "yaml/bundle")

    def test_clean_verification_rejects_global_node_and_clears_stale_success(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder)
            artifact = output / "python-linux-arm64.tar.gz"
            with tarfile.open(artifact, "w:gz") as archive:
                member = tarfile.TarInfo("launch.sh")
                member.size = 1
                archive.addfile(member, io.BytesIO(b"x"))
            metadata = output / "python.json"
            metadata.write_text(json.dumps({"sizeBytes": artifact.stat().st_size,
                                            "sha256": vendor.digest(artifact), "cleanUbuntuVerified": True}))
            with patch.dict(os.environ, {"GITHUB_ACTIONS": "true"}), \
                 patch.object(build.platform, "machine", return_value="aarch64"), \
                 patch.object(build.platform, "freedesktop_os_release", return_value={"ID": "ubuntu", "VERSION_ID": "24.04"}), \
                 patch.object(build.shutil, "which", return_value="/fake/node") as which, \
                 patch.object(build, "probe_lsp", side_effect=ValueError("fake handshake failure")) as probe:
                with self.assertRaisesRegex(ValueError, "global Node"):
                    build.verify_clean("python", output)
                probe.assert_not_called()
                which.return_value = None
                with self.assertRaisesRegex(ValueError, "fake handshake failure"):
                    build.verify_clean("python", output)
                self.assertFalse(json.loads(metadata.read_text())["cleanUbuntuVerified"])
                probe.side_effect = None
                build.verify_clean("python", output)
                self.assertTrue(json.loads(metadata.read_text())["cleanUbuntuVerified"])

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
            commands = root / "commands"
            commands.mkdir()
            (commands / "python3.12").symlink_to(sys.executable)
            with patch.dict(os.environ, {"PATH": str(commands) + os.pathsep + os.environ["PATH"]}):
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
                entry.update(fileName=artifact.name, sha256=vendor.digest(artifact), sizeBytes=artifact.stat().st_size,
                             cleanUbuntuVerified=True)
                if source["id"] in build.NODE:
                    entry.update(privateNodeVersion=build.NODE_VERSION, privateNodeSha256="a" * 64)
                (output / f"{source['id']}.json").write_text(json.dumps(entry))
            build.manifest(output)
            self.assertEqual(7, len(json.loads((output / "manifest.json").read_text())["extensions"]))
            metadata = output / "yaml.json"
            valid = metadata.read_text()
            for field, bad in (("cleanUbuntuVerified", False), ("privateNodeVersion", "18.0.0"),
                               ("privateNodeSha256", "invalid")):
                entry = json.loads(valid)
                entry[field] = bad
                metadata.write_text(json.dumps(entry))
                with self.assertRaises(ValueError):
                    build.manifest(output)
                self.assertFalse((output / "manifest.json").exists())
            metadata.write_text(valid)
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
