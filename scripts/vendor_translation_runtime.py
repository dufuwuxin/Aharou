#!/usr/bin/env python3
"""Vendor pinned, public translation runtimes; never download model weights."""
import hashlib
import json
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets/translation"
BASE = "https://cdn.jsdelivr.net/npm/"
SOURCES = {
    "transformers.min.js": ("@xenova/transformers@2.17.2/dist/transformers.min.js", "bcf7cf304e51f470ed59409622b9d6ffbad80dfcf5baf6a40c919e4b9c4ff812"),
    "ort-wasm-simd.wasm": ("onnxruntime-web@1.14.0/dist/ort-wasm-simd.wasm", "9bd07bababc65f53d061f457233eeae501be7ceb8a2adb9eef52d87fe776d865"),
    "ort-wasm.wasm": ("onnxruntime-web@1.14.0/dist/ort-wasm.wasm", "bbdcb6b3c7d294577d806077630460be4e13ca35a345acb5e81188e9649fa74a"),
}


def fetch(path):
    response = requests.get(path if path.startswith("https://") else BASE + path, timeout=(15, 90))
    response.raise_for_status()
    if len(response.content) > 40000000:
        raise ValueError("Runtime artifact exceeds limit")
    return response.content


def main():
    ASSETS.mkdir(parents=True, exist_ok=True)
    manifest = {"transformers": "2.17.2", "onnxruntimeWeb": "1.14.0", "files": []}
    previous = ASSETS / "runtime-manifest.json"
    locked = {}
    if previous.exists():
        locked = {f["path"]: f["sha256"] for f in json.loads(previous.read_text())["files"]}
    staged = {}
    for name, (path, expected) in SOURCES.items():
        data = fetch(path)
        digest = hashlib.sha256(data).hexdigest()
        expected = expected or locked.get(name)
        if expected and digest != expected:
            raise ValueError("Digest mismatch: " + name)
        staged[name] = data
        manifest["files"].append({"path": name, "url": BASE + path, "size": len(data), "sha256": digest})
        print(name, len(data), digest, flush=True)
    notices = []
    for package, url in [
        ("@xenova/transformers@2.17.2", BASE + "@xenova/transformers@2.17.2/LICENSE"),
        ("onnxruntime-web@1.14.0", "https://cdn.jsdelivr.net/gh/microsoft/onnxruntime@v1.14.0/LICENSE"),
    ]:
        license_text = fetch(url).decode("utf-8")
        notices.append(package + "\n" + url + "\n\n" + license_text)
    for direction in ("en-zh", "zh-en"):
        notices.append(f"Model: Xenova/opus-mt-{direction}, based on Helsinki-NLP/opus-mt-{direction}.\n"
                       "Base model metadata declares Apache-2.0. Model weights are downloaded separately.\n"
                       f"https://huggingface.co/Helsinki-NLP/opus-mt-{direction}\n")
    for name, data in staged.items():
        (ASSETS / name).write_bytes(data)
    (ASSETS / "THIRD_PARTY_NOTICES.txt").write_text("\n\n".join(notices))
    previous.write_text(json.dumps(manifest, indent=2) + "\n")


if __name__ == "__main__":
    main()
