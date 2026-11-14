#!/usr/bin/env python3
"""Static delivery gates, not Android runtime/inference tests."""
import argparse
import base64
import hashlib
import json
import re
import unittest
from pathlib import Path

from prepare_translation_models import MANIFESTS, validate_manifest

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets/translation"
SOURCE = ROOT / "app/src/main/java/com/aharou/core/translation"


class TranslationDeliveryTest(unittest.TestCase):
    def test_runtime_bytes_match_lock(self):
        manifest = json.loads((ASSETS / "runtime-manifest.json").read_text())
        self.assertEqual(manifest["transformers"], "2.17.2")
        self.assertEqual(manifest["onnxruntimeWeb"], "1.14.0")
        for item in manifest["files"]:
            data = (ASSETS / item["path"]).read_bytes()
            self.assertEqual(len(data), item["size"])
            self.assertEqual(hashlib.sha256(data).hexdigest(), item["sha256"])
        self.assertGreater((ASSETS / "THIRD_PARTY_NOTICES.txt").stat().st_size, 1000)

    def test_model_manifest_fixed_and_not_bundled(self):
        for name, model in zip(MANIFESTS, ("opus-mt-en-zh", "opus-mt-zh-en")):
            manifest = validate_manifest(json.loads((ASSETS / name).read_text()))
            self.assertEqual(manifest["model"], model)
            for item in manifest["files"]:
                if item["path"].endswith(".onnx"):
                    self.assertFalse((ASSETS / item["path"]).exists())
            if model == "opus-mt-en-zh":
                self.assertEqual(manifest["revision"], "046f55aec303cdee3e0318604406d4df20f1e8ea")
                self.assertEqual(sum(x["size"] for x in manifest["files"]), 119495576)

    def test_sdk_not_download_owner(self):
        service = (SOURCE / "OfflineTranslation.kt").read_text()
        storage = (SOURCE / "TranslationModelFiles.kt").read_text()
        self.assertNotIn("mlkit", service)
        self.assertNotIn("downloadModelIfNeeded", service)
        self.assertNotIn("DownloadManager", storage)
        for evidence in ["MessageDigest.getInstance(\"SHA-256\")", "Os.rename", "file.fd.sync()", "count <= artifact.size", "invokeOnCancellation"]:
            self.assertIn(evidence, storage)
        self.assertLess(service.index("models.install(target)"), service.index("engine.prepare(", service.index("models.install(target)")))
        self.assertIn("model-manifest-zh-en.json", storage)

    def test_sandbox_and_exact_csp(self):
        html = (ASSETS / "index.html").read_text()
        engine = (SOURCE / "TranslationWebEngine.kt").read_text()
        script = re.search(r'<script type="module">(.*?)</script>', html, re.S).group(1)
        digest = base64.b64encode(hashlib.sha256(script.encode()).digest()).decode()
        self.assertIn("sha256-" + digest, engine)
        for setting in ["allowRemoteModels = false", "allowLocalModels = true", "useBrowserCache = false", "numThreads = 1", "local_files_only: true"]:
            self.assertIn(setting, html)
        for setting in ["allowFileAccess = false", "allowContentAccess = false", "blockNetworkLoads = true", "JSONObject.quote(text)", "uri.encodedPath", "return blocked()"]:
            self.assertIn(setting, engine)
        self.assertNotIn("https://", html)
        self.assertNotIn("unsafe-inline", engine)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets-only", action="store_true", help="Exclude Kotlin integration gates before worktree merge")
    args = parser.parse_args()
    names = ("test_runtime_bytes_match_lock", "test_model_manifest_fixed_and_not_bundled") if args.assets_only else unittest.defaultTestLoader.getTestCaseNames(TranslationDeliveryTest)
    suite = unittest.TestSuite(TranslationDeliveryTest(name) for name in names)
    if not unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful():
        raise SystemExit(1)
