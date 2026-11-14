#!/usr/bin/env python3
"""Deterministic gates plus optional real pinned-model inference (no synthetic BLEU)."""
import argparse
import copy
import hashlib
import io
import json
import re
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from prepare_translation_models import (
    ASSETS,
    FILES,
    MANIFESTS,
    download_artifact,
    lock_reverse_manifest,
    validate_manifest,
)

# These are concept checks, not reference translations or a corpus-level quality score.
FIXTURES = [
    {"target": "zh", "text": "Please save the file.", "concepts": [["保存", "存储"], ["文件", "档案"]]},
    {"target": "zh", "text": "The network connection failed.", "concepts": [["网络", "网路"], ["失败", "无法", "失效", "错误", "故障"]]},
    {"target": "en", "text": "请保存文件。", "concepts": [["save"], ["file", "document"]]},
    {"target": "en", "text": "网络连接失败。", "concepts": [["network"], ["fail", "error", "lost"]]},
]


class ModelPreparationTest(unittest.TestCase):
    def test_manifests_and_invalid_boundaries(self):
        for name in MANIFESTS:
            manifest = json.loads((ASSETS / name).read_text())
            validate_manifest(manifest)
            for key, value in [("revision", "main"), ("baseUrl", "https://example.com/")]:
                invalid = copy.deepcopy(manifest)
                invalid[key] = value
                with self.assertRaises(ValueError):
                    validate_manifest(invalid)
            invalid = copy.deepcopy(manifest)
            invalid["files"][0]["path"] = "../escape"
            with self.assertRaises(ValueError):
                validate_manifest(invalid)

    def test_lock_uses_real_response_hashes_without_weights(self):
        config = json.dumps({"model_type": "marian"}).encode()
        revision = "1" * 40
        siblings = [
            {"rfilename": name, "size": len(config)} if name == "config.json" else
            {"rfilename": name, "lfs": {"size": 42, "sha256": "a" * 64}}
            for name in sorted(FILES)
        ]
        metadata = json.dumps({"sha": revision, "siblings": siblings}).encode()
        with tempfile.TemporaryDirectory() as directory:
            with patch("prepare_translation_models.ASSETS", Path(directory)), patch(
                "urllib.request.urlopen", side_effect=[io.BytesIO(metadata), io.BytesIO(config)],
            ) as request:
                lock_reverse_manifest()
                self.assertEqual(request.call_count, 2)
            manifest = json.loads((Path(directory) / MANIFESTS[1]).read_text())
            self.assertEqual(manifest["revision"], revision)
            self.assertEqual(manifest["files"][0]["sha256"], hashlib.sha256(config).hexdigest())
            self.assertEqual(manifest["model"], "opus-mt-zh-en")

    def test_atomic_download_and_cache(self):
        data = b"controlled model fixture"
        item = {"path": "config.json", "size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "config.json"
            with patch("urllib.request.urlopen", return_value=io.BytesIO(data)) as request:
                download_artifact("https://example.invalid/config.json", destination, item)
                self.assertEqual(destination.read_bytes(), data)
                download_artifact("https://example.invalid/config.json", destination, item)
                self.assertEqual(request.call_count, 1)
            for corrupt in [b"short", data + b"overflow", b"x" * len(data)]:
                destination.write_bytes(b"previous")
                with patch("urllib.request.urlopen", return_value=io.BytesIO(corrupt)):
                    with self.assertRaises(ValueError):
                        download_artifact("https://example.invalid/config.json", destination, item)
                self.assertEqual(destination.read_bytes(), b"previous")
                self.assertEqual(list(Path(directory).glob("*.part")), [])
            with patch("urllib.request.urlopen", side_effect=TimeoutError("controlled timeout")):
                with self.assertRaises(TimeoutError):
                    download_artifact("https://example.invalid/config.json", destination, item)
            self.assertEqual(destination.read_bytes(), b"previous")


def bridge_test():
    html = (ASSETS / "index.html").read_text()
    script = re.search(r'<script type="module">(.*?)</script>', html, re.S).group(1)
    script = re.sub(r"^import .*?;\s*", "", script.lstrip(), count=1)
    harness = r"""
const assert = require('node:assert/strict');
const vm = require('node:vm');
const results = new Map();
const calls = [];
let active = 0;
let maximum = 0;
const pipeline = async (task, model, options) => {
  assert.equal(task, 'translation');
  assert.equal(options.local_files_only, true);
  assert.equal(options.quantized, true);
  active++; maximum = Math.max(maximum, active); calls.push(model);
  const translator = async (text, options) => {
    assert.equal(options.num_beams, 3);
    assert.equal(options.do_sample, false);
    if (text === 'throw') throw Error('controlled failure');
    if (text === 'empty') return [];
    return [{ translation_text: model + ':' + text }];
  };
  translator.dispose = async () => { active--; };
  return translator;
};
const env = { backends: { onnx: { wasm: {} } } };
const window = { location: { origin: 'https://translation.invalid', href: 'https://translation.invalid/index.html?request=boot&target=en' },
  TranslationResult: { finish: (id, ok, value) => { assert(!results.has(id)); results.set(id, {ok, value}); } } };
(async () => {
  vm.runInNewContext(SCRIPT, { env, pipeline, window, URL });
  await window.prepareLocal('ready', 'en');
  assert.equal(results.get('boot').ok, true);
  await Promise.all([
    window.translateLocal('zh', 'hello', 'zh'), window.translateLocal('en', '你好', 'en'),
    window.translateLocal('fail', 'throw', 'en'), window.translateLocal('empty', 'empty', 'en'),
    window.translateLocal('bad', 'hello', 'fr'), window.translateLocal('blank', ' ', 'zh')
  ]);
  await window.translateLocal('recover', 'again', 'en');
  assert.equal(results.get('zh').value, 'opus-mt-en-zh:hello');
  assert.equal(results.get('en').value, 'opus-mt-zh-en:你好');
  for (const id of ['fail', 'empty', 'bad', 'blank']) assert.equal(results.get(id).ok, false);
  assert.equal(results.get('recover').ok, true);
  assert.equal(maximum, 1);
  assert.deepEqual(calls, ['opus-mt-zh-en', 'opus-mt-en-zh', 'opus-mt-zh-en']);
  console.log('PASS controlled bridge: direction, serialization, disposal, errors, recovery');
})().catch(error => { console.error(error); process.exitCode = 1; });
""".replace("SCRIPT", json.dumps(script))
    subprocess.run(["node", "-e", harness], check=True, timeout=30)


def inference_test(models, runtime, report):
    code = r"""
import { pathToFileURL } from 'node:url';
const { env, pipeline } = await import(pathToFileURL(process.argv[1]).href);
env.allowRemoteModels = false;
env.allowLocalModels = true;
env.useBrowserCache = false;
env.localModelPath = process.argv[2].replace(/\/$/, '') + '/';
const fixtures = JSON.parse(process.argv[3]);
const results = [];
for (const target of ['zh', 'en']) {
  const translator = await pipeline('translation', target === 'zh' ? 'opus-mt-en-zh' : 'opus-mt-zh-en', {quantized: true, local_files_only: true});
  try {
    for (const fixture of fixtures.filter(item => item.target === target)) {
      const baseline = await translator(fixture.text, {num_beams: 1, max_new_tokens: 256, do_sample: false});
      const output = await translator(fixture.text, {num_beams: 3, max_new_tokens: 256, do_sample: false});
      results.push({...fixture, baseline: baseline?.[0]?.translation_text, output: output?.[0]?.translation_text});
    }
  } finally { await translator.dispose(); }
}
console.log('AHAROU_RESULTS=' + JSON.stringify(results));
"""
    execution = subprocess.run(
        ["node", "--input-type=module", "-e", code, str(runtime.resolve()), str(models.resolve()), json.dumps(FIXTURES)],
        check=True, capture_output=True, text=True, timeout=900,
    )
    lines = [line for line in execution.stdout.splitlines() if line.startswith("AHAROU_RESULTS=")]
    if len(lines) != 1:
        raise ValueError("Missing inference results")
    results = json.loads(lines[0].split("=", 1)[1])
    if len(results) != len(FIXTURES):
        raise ValueError("Incomplete inference results")
    passed = True
    for expected, result in zip(FIXTURES, results):
        if any(result.get(key) != value for key, value in expected.items()):
            raise ValueError("Inference fixture mismatch")
        output = result.get("output")
        result["passed"] = isinstance(output, str) and bool(output.strip()) and all(
            any(term in output.lower() for term in alternatives) for alternatives in expected["concepts"]
        )
        baseline = result.get("baseline")
        result["baseline_passed"] = isinstance(baseline, str) and bool(baseline.strip()) and all(
            any(term in baseline.lower() for term in alternatives) for alternatives in expected["concepts"]
        )
        passed = passed and result["passed"]
    # Write real outputs even when a concept gate fails; never turn failures into reference output.
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps({"runtime": "transformers.js 2.17.2 Node (not Android WebView)", "baseline_num_beams": 1, "candidate_num_beams": 3, "sample_size": len(results), "metric": "fixed concept checks, not BLEU or general translation quality", "results": results, "passed": passed}, ensure_ascii=False, indent=2) + "\n")
    if not passed:
        raise AssertionError("Real model concept checks failed; inspect " + str(report))
    print("PASS real model concept fixtures:", len(results))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bridge-test", action="store_true")
    parser.add_argument("--models", type=Path)
    parser.add_argument("--runtime", type=Path)
    parser.add_argument("--report", type=Path, default=Path("translation-quality.json"))
    args = parser.parse_args()
    if bool(args.models) != bool(args.runtime):
        parser.error("--models and --runtime are required together")
    outcome = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(ModelPreparationTest))
    if not outcome.wasSuccessful():
        raise SystemExit(1)
    if args.bridge_test:
        bridge_test()
    if args.models:
        inference_test(args.models, args.runtime, args.report)


if __name__ == "__main__":
    main()
