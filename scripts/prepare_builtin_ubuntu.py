#!/usr/bin/env python3
"""Prepare the unmodified official Ubuntu ARM64 archive; never execute its contents."""

import argparse
import hashlib
import json
import os
import re
import shutil
import tarfile
import tempfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FILENAME = "ubuntu-base-24.04.5-base-arm64.tar.gz"
URL = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/" + FILENAME
SHA256 = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"
ASSET = "app/src/_armAssets/container/arm/ubuntu-rootfs.bin"
METADATA = "app/src/main/assets/builtin-container.json"


def sha256_file(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def official_checksum(text: str) -> str:
    matches = []
    for line in text.splitlines():
        match = re.fullmatch(r"([0-9a-fA-F]{64})\s+\*?" + re.escape(FILENAME), line)
        if match:
            matches.append(match.group(1).lower())
    if matches != [SHA256]:
        raise ValueError("official SHA256SUMS lacks one exact, pinned Ubuntu ARM64 checksum")
    return matches[0]


def normalize(parts: list[str]) -> list[str]:
    result = []
    for part in parts:
        if part in ("", "."):
            continue
        if part == "..":
            if not result:
                raise ValueError("archive link escapes rootfs")
            result.pop()
        else:
            result.append(part)
    return result


def inspect_rootfs(path: Path) -> dict:
    with path.open("rb") as stream:
        if stream.read(2) != b"\x1f\x8b":
            raise ValueError("builtin rootfs must contain original gzip bytes")
    with tarfile.open(path, "r:gz") as archive:
        members = {}
        for index, member in enumerate(archive):
            if index >= 100000:
                raise ValueError("rootfs has too many archive members")
            name = "/".join(normalize(member.name.split("/")))
            if name in members:
                raise ValueError("duplicate rootfs archive member: " + name)
            members[name] = member
        if "etc/alpine-release" in members:
            raise ValueError("Alpine rootfs is not supported")

        def resolve(name: str) -> tarfile.TarInfo:
            pending = normalize(name.split("/"))
            resolved = []
            links = 0
            while pending:
                resolved.append(pending.pop(0))
                member = members.get("/".join(resolved))
                if member is None:
                    raise ValueError("missing rootfs path: " + "/".join(resolved))
                if member.issym() or member.islnk():
                    links += 1
                    if links > 40:
                        raise ValueError("rootfs link loop")
                    base = [] if member.islnk() or member.linkname.startswith("/") else resolved[:-1]
                    pending = normalize(base + member.linkname.split("/")) + pending
                    resolved = []
            member = members["/".join(resolved)]
            if not member.isfile():
                raise ValueError("rootfs path is not a regular file: " + name)
            return member

        def read(name: str, limit: int) -> bytes:
            member = resolve(name)
            stream = archive.extractfile(member)
            if stream is None:
                raise ValueError("unreadable rootfs file: " + name)
            with stream:
                return stream.read(limit)

        text = read("etc/os-release", 65537)
        if len(text) > 65536:
            raise ValueError("os-release is oversized")
        release = {}
        for line in text.decode("utf-8").splitlines():
            key, separator, value = line.partition("=")
            if separator:
                release[key] = value.strip('"\'')
        if release.get("ID") != "ubuntu" or release.get("VERSION_ID") != "24.04":
            raise ValueError("rootfs must be Ubuntu 24.04 LTS")
        shell = read("bin/sh", 64)
        if (len(shell) < 64 or shell[:6] != b"\x7fELF\x02\x01"
                or int.from_bytes(shell[18:20], "little") != 183):
            raise ValueError("rootfs /bin/sh must be native ELF64 AArch64 (e_machine=183)")
        return {"distro": "ubuntu", "version": "24.04.5", "architecture": "arm64",
                "osReleaseVersion": release["VERSION_ID"], "shellMachine": 183}


def metadata_for(path: Path) -> dict:
    if sha256_file(path) != SHA256:
        raise ValueError("builtin Ubuntu archive SHA-256 mismatch")
    details = inspect_rootfs(path)
    return {"url": URL, "sha256": SHA256, "sizeBytes": path.stat().st_size,
            "assetPath": "container/arm/ubuntu-rootfs.bin", **details}


def fetch(url: str, destination: Path) -> None:
    request = urllib.request.Request(url, headers={"User-Agent": "aharou-native-rootfs-verifier"})
    with urllib.request.urlopen(request, timeout=120) as response, destination.open("wb") as stream:
        shutil.copyfileobj(response, stream, length=1024 * 1024)


def prepare(root: Path, archive: Path | None = None, checksums: Path | None = None) -> dict:
    with tempfile.TemporaryDirectory(prefix="aharou-ubuntu-") as directory:
        temporary = Path(directory)
        if checksums is None:
            checksums = temporary / "SHA256SUMS"
            fetch(URL.rsplit("/", 1)[0] + "/SHA256SUMS", checksums)
        official_checksum(checksums.read_text(encoding="utf-8"))
        if archive is None:
            archive = temporary / FILENAME
            fetch(URL, archive)
        metadata = metadata_for(archive)
        # Publish only after all upstream/hash/OS/ELF checks; never unpack into the host.
        asset = root / ASSET
        asset.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=asset.parent, delete=False) as stream:
            pending = Path(stream.name)
            try:
                with archive.open("rb") as source:
                    shutil.copyfileobj(source, stream, length=1024 * 1024)
                stream.flush()
                if sha256_file(pending) != SHA256:
                    raise ValueError("archive changed while copying")
                os.replace(pending, asset)
            finally:
                pending.unlink(missing_ok=True)
        target = root / METADATA
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
        return metadata


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, help="already downloaded official bytes")
    parser.add_argument("--checksums", type=Path, help="saved official SHA256SUMS")
    args = parser.parse_args()
    try:
        print(json.dumps(prepare(ROOT, args.archive, args.checksums), indent=2))
    except (OSError, ValueError, tarfile.TarError) as error:
        parser.exit(1, f"Ubuntu preparation failed: {error}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
