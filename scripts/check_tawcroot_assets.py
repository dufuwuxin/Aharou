#!/usr/bin/env python3
"""Verify the pinned upstream tawcroot executable before APK packaging."""

import hashlib
import json
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANIFEST_PATH = "app/src/main/assets/tawcroot-runtime.json"
BINARY_PATH = "app/src/_armJniLibs/arm64-v8a/libtawcroot.so"
LICENSE_PATH = "app/src/main/assets/licenses/tawcroot-MIT.txt"
APK_URL = "https://github.com/wmww/tawc/releases/download/v6/tawc-v6.apk"
APK_SHA256 = "31037eb3c080bcdb772fe01fd989a44cee525499accecdb591a3dc9baeace07d"
SOURCE_COMMIT = "bf81550ca5fd86bd82f9c9ecd588cdafb3e03459"
BINARY_SIZE = 116672
BINARY_SHA256 = "48eeb93c0083f33f6e53b722684e704ba9b2a34d90f681c560e06edfa871f323"
LICENSE_SHA256 = "9d3d5eaf56a806858bc38364d32aae696b6a94d5746f4b24d050ef4cfcbe02ad"


def elf_problems(data: bytes) -> list[str]:
    if len(data) < 64 or data[:7] != b"\x7fELF\x02\x01\x01":
        return ["expected an ELF64 little-endian executable"]
    elf_type, machine, version, entry, phoff = struct.unpack_from("<HHIQQ", data, 16)
    ehsize, phentsize, phnum = struct.unpack_from("<HHH", data, 52)
    if (elf_type, machine, version) != (2, 183, 1):
        return ["expected static non-PIE ET_EXEC for AArch64 (machine 183)"]
    if ehsize != 64 or phentsize != 56 or not 1 <= phnum <= 256:
        return ["invalid ELF program-header layout"]
    if phoff < ehsize or phoff + phentsize * phnum > len(data):
        return ["truncated ELF program headers"]
    executable_entry = False
    load_seen = False
    for index in range(phnum):
        kind, flags, offset, vaddr, _, filesz, memsz, _ = struct.unpack_from(
            "<IIQQQQQQ", data, phoff + index * phentsize
        )
        if kind in (2, 3):
            return ["static executable must not contain PT_DYNAMIC or PT_INTERP"]
        if offset + filesz > len(data):
            return ["ELF segment extends beyond file"]
        if kind == 1:
            load_seen = True
            if filesz > memsz:
                return ["ELF load segment exceeds its memory size"]
            if flags & 1 and vaddr <= entry < vaddr + filesz:
                executable_entry = True
    if not load_seen or not executable_entry:
        return ["ELF entry point is not inside an executable load segment"]
    return []


def validate_assets(root: Path) -> list[str]:
    try:
        manifest = json.loads((root / MANIFEST_PATH).read_text(encoding="utf-8"))
        if not isinstance(manifest, dict):
            return ["manifest must be a JSON object"]
        if manifest.get("schemaVersion") != 1:
            return ["unsupported manifest schema"]
        origin = manifest.get("origin")
        if not isinstance(origin, dict):
            return ["missing upstream origin"]
        if (
            origin.get("project") != "https://github.com/wmww/tawc"
            or origin.get("tag") != "v6"
            or origin.get("apkUrl") != APK_URL
            or origin.get("apkSha256") != APK_SHA256
            or origin.get("apkEntry") != "lib/arm64-v8a/libtawcroot.so"
            or origin.get("license") != "MIT"
            or origin.get("sourceCommit") != SOURCE_COMMIT
        ):
            return ["unexpected upstream release identity"]
        contract = manifest.get("runtimeContract")
        if not isinstance(contract, dict) or (
            contract.get("minimumAndroidApi") != 29
            or contract.get("processBits") != 64
            or contract.get("abi") != "arm64-v8a"
            or contract.get("cli") != "libtawcroot.so [-s] -r ROOTFS [-b SRC:DST[:ro]]... -- CMD [ARGS...]"
        ):
            return ["unexpected tawcroot runtime contract"]
        files = manifest.get("files")
        if not isinstance(files, list) or len(files) != 1 or not isinstance(files[0], dict):
            return ["expected exactly one tawcroot executable"]
        entry = files[0]
        if (
            entry.get("repoPath") != BINARY_PATH
            or entry.get("abi") != "arm64-v8a"
            or entry.get("role") != "tawcroot-executable"
            or entry.get("sizeBytes") != BINARY_SIZE
            or entry.get("sha256") != BINARY_SHA256
        ):
            return ["unexpected or unpinned runtime path, ABI, role, size or SHA-256"]
        license_entry = manifest.get("licenseFile")
        if (
            not isinstance(license_entry, dict)
            or license_entry.get("repoPath") != LICENSE_PATH
            or license_entry.get("sha256") != LICENSE_SHA256
        ):
            return ["missing or unpinned MIT license identity"]
        problems = []
        for descriptor in (entry, license_entry):
            path = root / descriptor["repoPath"]
            data = path.read_bytes()
            if descriptor.get("sizeBytes") != len(data):
                problems.append(f"{descriptor['repoPath']}: size mismatch")
            if descriptor.get("sha256") != hashlib.sha256(data).hexdigest():
                problems.append(f"{descriptor['repoPath']}: SHA-256 mismatch")
            if descriptor is entry:
                problems.extend(elf_problems(data))
        return problems
    except (OSError, ValueError, TypeError) as error:
        return [f"cannot validate tawcroot assets: {error}"]


def main() -> int:
    problems = validate_assets(ROOT)
    if problems:
        print("[FAIL] tawcroot assets:")
        for problem in problems:
            print(f"  - {problem}")
        return 1
    print("[OK] tawcroot v6: SHA-256, MIT license, static non-PIE ELF64 AArch64 verified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
