#!/usr/bin/env python3
"""Maintain fixed source bundles for explicit cloud-only extension builds."""

import argparse
import hashlib
import json
import re
import shlex
import tarfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets/language-extensions"
BUNDLES = ROOT / "extensions/language-servers"
PLATFORM = "linux-arm64-ubuntu24.04"
COMMANDS = {
    "lua": "lua-language-server", "python": "pylsp",
    "typescript": "typescript-language-server", "shell": "bash-language-server",
    "yaml": "yaml-language-server", "vue": "vue-language-server", "rust": "rust-analyzer",
}
BUILD = {
    "python": '''need python3
bundle=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
python3 -m pip install --require-hashes --only-binary=:all: --target "$release/lib" -r "$bundle/../requirements.lock"
PYTHONPATH="$release/lib" SETUPTOOLS_SCM_PRETEND_VERSION=SOURCE_VERSION python3 -m pip install --no-build-isolation --no-deps --target "$release/lib" "$src"
''',
    "typescript": '''need node; need pnpm
(cd "$src"; HUSKY=0 pnpm install --frozen-lockfile; pnpm run build)
''',
    "shell": '''need node; need pnpm
(cd "$src"; pnpm install --frozen-lockfile; pnpm run compile)
''',
    "yaml": '''need node; need npm
(cd "$src"; npm ci --no-audit --no-fund; npm run build)
''',
    "vue": '''need node; need pnpm
(cd "$src"; pnpm install --frozen-lockfile; pnpm run build)
''',
    "rust": '''need cargo; need rustc; need cc
[ "$(rustc --version | cut -d ' ' -f 2)" = "1.98.0" ] || { echo 'Rust 1.98.0 required' >&2; exit 69; }
(cd "$src"; cargo build --locked --release --package rust-analyzer --bin rust-analyzer)
''',
    "lua": '''need git; need cc; need c++; need ninja; need bash
# Gitlinks at the fixed parent commit pin all recursively fetched submodules.
git -C "$src" init -q
git -C "$src" remote add origin https://github.com/LuaLS/lua-language-server.git
git -C "$src" fetch --depth=1 origin REVISION
git -C "$src" reset --mixed FETCH_HEAD
(cd "$src"; git submodule update --init --recursive; bash make.sh)
''',
}


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def installer(name: str, meta: dict) -> str:
    build = BUILD[name].replace("REVISION", shlex.quote(meta["revision"]))
    build = build.replace("SOURCE_VERSION", shlex.quote(meta["version"]))
    return '''#!/bin/sh
set -eu
[ "${GITHUB_ACTIONS:-}" = true ] || { echo 'Cloud CI build only' >&2; exit 69; }
need() { command -v "$1" >/dev/null 2>&1 || { echo "Missing prerequisite: $1" >&2; exit 69; }; }
release=$1
src="$release/source"
''' + build


def source_set(entries: list[dict]) -> str:
    # UTF-8 canonical JSON, ID-sorted, no whitespace; includes every descriptor field.
    recipe = {name: digest(ROOT / name) for name in (
        "scripts/build_language_extensions.py", ".github/workflows/language-extensions.yml",
        "extensions/language-servers/python/requirements.lock")}
    data = json.dumps({"extensions": sorted(entries, key=lambda e: e["id"]), "recipe": recipe}, sort_keys=True,
                      separators=(",", ":"), ensure_ascii=True).encode("utf-8")
    return hashlib.sha256(data).hexdigest()


def descriptors(bundles: Path = BUNDLES) -> list[dict]:
    entries = []
    for name, command in COMMANDS.items():
        bundle = bundles / name / "bundle"
        meta = json.loads((bundle / "source.json").read_text())
        for filename, field in (("source.tar.gz", "sourceSha256"), ("LICENSE", "licenseSha256")):
            if digest(bundle / filename) != meta[field]:
                raise ValueError(f"{name}: {filename} hash mismatch")
        if meta["id"] != name or not re.fullmatch(r"[0-9a-f]{40}", meta["revision"]):
            raise ValueError(f"{name}: invalid fixed source identity")
        if (bundle / "install.sh").read_text() != installer(name, meta):
            raise ValueError(f"{name}: adapter needs --render")
        with tarfile.open(bundle / "source.tar.gz") as archive:
            for member in archive:
                if member.name.startswith("/") or ".." in Path(member.name).parts:
                    raise ValueError(f"unsafe archive member: {member.name}")
        entries.append({"id": name, "command": command, "repository": meta["repository"],
                        "revision": meta["revision"], "version": meta["version"],
                        "sourceSha256": meta["sourceSha256"], "adapterSha256": digest(bundle / "install.sh")})
    return entries


def verify(assets: Path = ASSETS, bundles: Path = BUNDLES) -> list[dict]:
    entries = descriptors(bundles)
    fingerprint = source_set(entries)
    expected = {"schemaVersion": 1, "platform": PLATFORM, "sourceSetSha256": fingerprint,
                "releaseTag": "language-extensions-" + fingerprint, "extensions": entries}
    if json.loads((assets / "catalog.json").read_text()) != expected:
        raise ValueError("catalog differs from verified fixed sources/adapters; run --render")
    return entries


def render() -> None:
    for name in COMMANDS:
        bundle = BUNDLES / name / "bundle"
        meta = json.loads((bundle / "source.json").read_text())
        (bundle / "install.sh").write_text(installer(name, meta))
    entries = descriptors()
    fingerprint = source_set(entries)
    catalog = {"schemaVersion": 1, "platform": PLATFORM, "sourceSetSha256": fingerprint,
               "releaseTag": "language-extensions-" + fingerprint, "extensions": entries}
    (ASSETS / "catalog.json").write_text(json.dumps(catalog, indent=2) + "\n")


def fetch() -> None:
    for name in COMMANDS:
        bundle = BUNDLES / name / "bundle"
        meta = json.loads((bundle / "source.json").read_text())
        repo = meta["repository"].removeprefix("https://github.com/")
        url = f"https://codeload.github.com/{repo}/tar.gz/{meta['revision']}"
        if meta["sourceUrl"] != url:
            raise ValueError("source URL differs from pinned official repository")
        request = urllib.request.Request(url, headers={"User-Agent": "Aharou-source-vendor/1.0", "Referer": meta["repository"]})
        temporary = bundle / "source.tar.gz.download"
        try:
            with urllib.request.urlopen(request, timeout=120) as response, temporary.open("wb") as stream:
                while chunk := response.read(1024 * 1024):
                    stream.write(chunk)
            if digest(temporary) != meta["sourceSha256"]:
                raise ValueError(f"{name}: downloaded bytes differ from pinned snapshot")
            temporary.replace(bundle / "source.tar.gz")
        finally:
            temporary.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--render", action="store_true")
    parser.add_argument("--fetch", action="store_true")
    args = parser.parse_args()
    if args.fetch:
        fetch()
    if args.render:
        render()
    print(f"OK: {len(verify())} fixed sources, licenses and CI adapters")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
