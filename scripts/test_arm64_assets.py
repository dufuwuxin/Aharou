import contextlib
import copy
import hashlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parent


def load_script(name, filename):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


sync = load_script("sync_container_images", "sync-container-images.py")
proot = load_script("check_proot_assets", "check_proot_assets.py")


class Arm64AssetsTest(unittest.TestCase):
    def test_generator_only_produces_arm64(self):
        self.assertEqual(("arm64",), sync.ABIS)

    def test_manifest_projection_preserves_arm64_and_is_idempotent(self):
        original = {
            "sources": {"official": {"base": "unchanged"}},
            "images": [{
                "id": "fixture", "distro": "ubuntu", "version": "1", "sizeBytes": 123,
                "abiNames": {"arm64": "aarch64", "x86_64": "amd64"},
                "sha256ByAbi": {"arm64": "arm-hash", "x86_64": "x86-hash"},
            }],
        }
        data = copy.deepcopy(original)
        with tempfile.TemporaryDirectory() as directory:
            paths = [str(Path(directory) / name) for name in ("built-in.json", "cloud.json")]
            with patch.object(sync, "MANIFESTS", paths):
                self.assertTrue(sync.save_manifests(data))
                first = Path(paths[0]).read_bytes()
                self.assertEqual(first, Path(paths[1]).read_bytes())
                self.assertTrue(sync.save_manifests(data))
                self.assertEqual(first, Path(paths[0]).read_bytes())
        expected = copy.deepcopy(original)
        expected["images"][0]["abiNames"] = {"arm64": "aarch64"}
        expected["images"][0]["sha256ByAbi"] = {"arm64": "arm-hash"}
        self.assertEqual(expected, data)

    def run_asset_gate(self, extra_abi=None, corrupt=False, corrupt_hash=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            content = b"runtime-fixture"
            (root / "runtime.so").write_bytes(content)
            entries = [{
                "abi": "arm64-v8a", "role": role, "repoPath": "runtime.so",
                "sizeBytes": len(content) + int(corrupt),
                "sha256": "0" * 64 if corrupt_hash else hashlib.sha256(content).hexdigest(),
            } for role in proot.REQUIRED_ROLES]
            if extra_abi:
                entries += [dict(entry, abi=extra_abi) for entry in entries]
            manifest = root / "manifest.json"
            manifest.write_text(json.dumps({"files": entries}))
            with patch.object(proot, "ROOT", root), patch.object(proot, "MANIFEST", manifest):
                with contextlib.redirect_stdout(io.StringIO()):
                    return proot.main()

    def test_complete_arm64_runtime_passes(self):
        self.assertEqual(0, self.run_asset_gate())

    def test_other_abi_is_rejected(self):
        self.assertEqual(1, self.run_asset_gate(extra_abi="x86_64"))

    def test_corrupt_runtime_is_rejected(self):
        self.assertEqual(1, self.run_asset_gate(corrupt=True))

    def test_wrong_hash_with_correct_size_is_rejected(self):
        self.assertEqual(1, self.run_asset_gate(corrupt_hash=True))


if __name__ == "__main__":
    unittest.main()
