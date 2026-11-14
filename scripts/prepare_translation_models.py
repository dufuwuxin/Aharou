#!/usr/bin/env python3
"""Verify pinned translation manifests; explicitly download models for cloud tests."""
import argparse
import hashlib
import json
import re
import tempfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets/translation"
MANIFESTS = ("model-manifest.json", "model-manifest-zh-en.json")
FILES = {
    "config.json", "generation_config.json", "tokenizer.json", "tokenizer_config.json",
    "onnx/encoder_model_quantized.onnx", "onnx/decoder_model_merged_quantized.onnx",
}


def validate_manifest(manifest):
    model = manifest["model"]
    if model not in {"opus-mt-en-zh", "opus-mt-zh-en"}:
        raise ValueError("Unsupported model")
    revision = manifest["revision"]
    if not re.fullmatch(r"[a-f0-9]{40}", revision):
        raise ValueError("Model revision must be a commit SHA")
    repository = "Xenova/" + model
    if manifest["repository"] != repository:
        raise ValueError("Unexpected model repository")
    if manifest["baseUrl"] != f"https://huggingface.co/{repository}/resolve/{revision}/":
        raise ValueError("Model URL must match pinned repository and revision")
    items = manifest["files"]
    if len(items) != len(FILES) or {item["path"] for item in items} != FILES:
        raise ValueError("Exactly six runtime artifacts required")
    for item in items:
        if type(item["size"]) is not int or not 0 < item["size"] < 150_000_000:
            raise ValueError("Invalid artifact size")
        if not re.fullmatch(r"[a-f0-9]{64}", item["sha256"]):
            raise ValueError("Invalid artifact SHA-256")
    return manifest


def lock_reverse_manifest():
    """Resolve a public upstream commit and hashes; never invent unavailable metadata."""
    repository = "Xenova/opus-mt-zh-en"
    api = f"https://huggingface.co/api/models/{repository}?blobs=true"
    with urllib.request.urlopen(api, timeout=30) as response:
        payload = response.read(1_000_001)
    if len(payload) > 1_000_000:
        raise ValueError("Unexpectedly large model metadata")
    metadata = json.loads(payload)
    revision = metadata["sha"]
    if not re.fullmatch(r"[a-f0-9]{40}", revision):
        raise ValueError("Missing upstream commit SHA")
    base = f"https://huggingface.co/{repository}/resolve/{revision}/"
    upstream = {item["rfilename"]: item for item in metadata["siblings"]}
    artifacts = []
    for name in sorted(FILES):
        item = upstream[name]
        lfs = item.get("lfs")
        if lfs:
            size, digest = lfs["size"], lfs["sha256"]
        else:
            if name.endswith(".onnx"):
                raise ValueError("Missing LFS hash for model weights")
            with urllib.request.urlopen(base + name, timeout=30) as response:
                data = response.read(10_000_001)
            if len(data) > 10_000_000:
                raise ValueError("Unexpectedly large model configuration")
            size, digest = len(data), hashlib.sha256(data).hexdigest()
            if size != item["size"]:
                raise ValueError("Upstream metadata size mismatch")
            if name == "config.json" and json.loads(data).get("model_type") != "marian":
                raise ValueError("Expected Marian model for transformers.js 2.17.2")
        artifacts.append({"path": name, "size": size, "sha256": digest})
    manifest = validate_manifest({"model": "opus-mt-zh-en", "repository": repository,
                                  "revision": revision, "baseUrl": base, "files": artifacts})
    destination = ASSETS / MANIFESTS[1]
    # Fetch and validate every entry before writing any manifest bytes.
    with tempfile.NamedTemporaryFile(dir=ASSETS, mode="w", encoding="utf-8", delete=False) as stream:
        temporary = Path(stream.name)
        stream.write(json.dumps(manifest, indent=2) + "\n")
    try:
        temporary.replace(destination)
    finally:
        if temporary.exists():
            temporary.unlink()
    print("Locked official metadata:", repository, revision)


def digest_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def matches(path, item):
    return path.is_file() and path.stat().st_size == item["size"] and digest_file(path) == item["sha256"]


def download_artifact(url, destination, item):
    if matches(destination, item):
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        request = urllib.request.Request(url, headers={"User-Agent": "Aharou-translation-validation/1.0"})
        with urllib.request.urlopen(request, timeout=90) as response, tempfile.NamedTemporaryFile(
            dir=destination.parent, prefix=destination.name + ".", suffix=".part", delete=False,
        ) as stream:
            temporary = Path(stream.name)
            count = 0
            digest = hashlib.sha256()
            while chunk := response.read(1024 * 1024):
                count += len(chunk)
                if count > item["size"]:
                    raise ValueError("Artifact exceeds pinned size: " + item["path"])
                digest.update(chunk)
                stream.write(chunk)
            if count != item["size"] or digest.hexdigest() != item["sha256"]:
                raise ValueError("Artifact integrity mismatch: " + item["path"])
        temporary.replace(destination)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="Explicit cloud model cache; omit for manifest checks only")
    parser.add_argument("--lock-zh-en", action="store_true", help="Explicitly lock reverse manifest from official public API (no weights)")
    args = parser.parse_args()
    if args.lock_zh_en:
        lock_reverse_manifest()
    for name in MANIFESTS:
        manifest = validate_manifest(json.loads((ASSETS / name).read_text(encoding="utf-8")))
        for item in manifest["files"]:
            if args.output:
                destination = args.output / manifest["model"] / item["path"]
                download_artifact(manifest["baseUrl"] + item["path"], destination, item)
        print(manifest["model"], manifest["revision"], sum(item["size"] for item in manifest["files"]), "verified" if args.output else "manifest-only")


if __name__ == "__main__":
    main()
