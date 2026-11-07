#!/usr/bin/env python3
"""Read-only gate for the original Ubuntu ARM64 builtin asset and metadata."""

import json
import tarfile
from pathlib import Path

from prepare_builtin_ubuntu import ASSET, METADATA, metadata_for

ROOT = Path(__file__).resolve().parents[1]


def check(root: Path) -> dict:
    if (root / "app/src/_armAssets/container/arm/alpine-rootfs.bin").exists():
        raise ValueError("legacy Alpine builtin asset must not ship")
    actual = metadata_for(root / ASSET)
    recorded = json.loads((root / METADATA).read_text(encoding="utf-8"))
    if actual != recorded:
        raise ValueError("builtin-container.json differs from verified Ubuntu bytes")
    return actual


def main() -> int:
    try:
        result = check(ROOT)
    except (OSError, ValueError, tarfile.TarError) as error:
        print(f"FAIL builtin container: {error}")
        return 1
    print(f"OK builtin Ubuntu {result['version']} native ARM64: "
          f"{result['sizeBytes']} bytes sha256={result['sha256']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
