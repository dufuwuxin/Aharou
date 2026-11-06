#!/usr/bin/env python3
"""Generate or verify builtin seed fingerprints from the fixed upstream history.

Default: validate the checked-in JSON without writing files or using the network.
Use --generate to refresh it; --repo selects the Git tree and relative --output
paths are resolved inside that tree. Only committed agents/teams assets are read.
"""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path

BASE = "fe74e8b1f404becd3c33f646e7226a9372f7aa9a"
ASSETS = "app/src/main/assets/"
ROOTS = (ASSETS + "agents", ASSETS + "teams")
OUTPUT = ASSETS + "builtin-seed-history.json"


def git(repo: Path, *args: str) -> bytes:
    return subprocess.run(
        ["git", "--no-replace-objects", "-C", str(repo), *args],
        check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    ).stdout


def valid_path(path: str) -> bool:
    parts = path.split("/")
    return (
        len(parts) >= 2 and parts[0] in {"agents", "teams"}
        and all(part not in {"", ".", ".."} for part in parts)
        and not any(char in path for char in ("\\", "\x00", "\r", "\n"))
    )


def unique_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def validate_format(value: object) -> None:
    if not isinstance(value, dict) or set(value) != {"version", "sources", "files"}:
        raise ValueError("expected exactly version, sources and files")
    if type(value["version"]) is not int or value["version"] != 1:
        raise ValueError("version must be integer 1")
    sources = value["sources"]
    if (
        not isinstance(sources, list) or not sources
        or any(not isinstance(s, str) or not re.fullmatch(r"[0-9a-f]{40}", s) for s in sources)
        or sources != sorted(set(sources))
    ):
        raise ValueError("sources must be sorted unique full commit SHA-1 values")
    files = value["files"]
    if not isinstance(files, dict) or not files or list(files) != sorted(files):
        raise ValueError("files must be a nonempty path-sorted object")
    for path, hashes in files.items():
        if not valid_path(path):
            raise ValueError(f"invalid asset path: {path}")
        if (
            not isinstance(hashes, list) or not hashes
            or any(not isinstance(h, str) or not re.fullmatch(r"[0-9a-f]{64}", h) for h in hashes)
            or hashes != sorted(set(hashes))
        ):
            raise ValueError(f"invalid or unsorted SHA-256 values: {path}")


def history(repo: Path) -> dict[str, object]:
    git(repo, "merge-base", "--is-ancestor", BASE, "HEAD")
    # A shallow marker can remain after all objects were obtained through another
    # branch. Traverse a read-only alternate object view without changing it.
    objects = git(repo, "rev-parse", "--path-format=absolute", "--git-path", "objects").decode().strip()
    with tempfile.TemporaryDirectory(prefix="aharou-seed-history-") as folder:
        view = Path(folder)
        subprocess.run(["git", "init", "--bare", "--quiet", str(view)], check=True)
        (view / "objects/info/alternates").write_text(objects + "\n", encoding="utf-8")
        # Require the complete commit graph; collect_history validates every
        # historical agents/teams tree and blob, not unrelated repository blobs.
        git(view, "rev-list", BASE)
        return collect_history(view)


def collect_history(repo: Path) -> dict[str, object]:
    sources = sorted(set(git(repo, "log", BASE, "--format=%H", "--", *ROOTS).decode().splitlines()))
    if not sources:
        raise ValueError("no builtin seed history found")
    files: dict[str, set[str]] = {}
    for source in sources:
        # Tree IDs let us detect archive attributes that omit or rewrite blobs.
        entries: dict[str, str] = {}
        for record in git(repo, "ls-tree", "-rz", source, "--", *ROOTS).split(b"\0"):
            if not record:
                continue
            metadata, raw_path = record.split(b"\t", 1)
            mode, kind, blob = metadata.decode().split()
            path = raw_path.decode("utf-8")
            if mode not in {"100644", "100755"} or kind != "blob":
                raise ValueError(f"non-regular historical asset: {path}")
            if not path.startswith(ASSETS) or not valid_path(path[len(ASSETS):]):
                raise ValueError(f"invalid historical asset path: {path}")
            entries[path] = blob
        if not entries:
            continue
        roots = sorted({ASSETS + path[len(ASSETS):].split("/", 1)[0] for path in entries})
        with tempfile.TemporaryFile() as archive:
            subprocess.run(
                ["git", "--no-replace-objects", "-C", str(repo), "archive", "--format=tar", source, "--", *roots],
                stdout=archive, stderr=subprocess.PIPE, check=True,
            )
            archive.seek(0)
            seen: set[str] = set()
            with tarfile.open(fileobj=archive, mode="r|") as contents:
                for member in contents:
                    if member.isdir():
                        continue
                    if not member.isfile() or member.name not in entries or member.name in seen:
                        raise ValueError(f"unexpected archive member: {member.name}")
                    stream = contents.extractfile(member)
                    if stream is None:
                        raise ValueError(f"unreadable archive member: {member.name}")
                    digest = hashlib.sha256()
                    blob_digest = hashlib.sha1(f"blob {member.size}\0".encode())
                    with stream:
                        for chunk in iter(lambda: stream.read(1 << 20), b""):
                            digest.update(chunk)
                            blob_digest.update(chunk)
                    if blob_digest.hexdigest() != entries[member.name]:
                        raise ValueError(f"archive differs from Git blob: {member.name}")
                    seen.add(member.name)
                    files.setdefault(member.name[len(ASSETS):], set()).add(digest.hexdigest())
            if seen != set(entries):
                raise ValueError(f"archive omitted historical assets in {source}")
    result: dict[str, object] = {
        "version": 1, "sources": sources,
        "files": {path: sorted(hashes) for path, hashes in sorted(files.items())},
    }
    validate_format(result)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--output", type=Path, default=Path(OUTPUT))
    parser.add_argument("--generate", action="store_true")
    args = parser.parse_args()
    repo = args.repo.resolve()
    output = args.output if args.output.is_absolute() else repo / args.output
    try:
        if not args.generate:
            actual = json.loads(output.read_text(encoding="utf-8"), object_pairs_hook=unique_object)
            validate_format(actual)
        expected = history(repo)
        if args.generate:
            # Replace only after the complete history has been checked successfully.
            temporary = None
            try:
                with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=output.parent, delete=False) as handle:
                    temporary = Path(handle.name)
                    handle.write(json.dumps(expected, ensure_ascii=False, indent=2) + "\n")
                os.replace(temporary, output)
            finally:
                if temporary is not None and temporary.exists():
                    temporary.unlink()
        elif actual != expected:
            raise ValueError("manifest differs from complete seed commit history (sources or asset hashes)")
        print(f"[OK] {'generated' if args.generate else 'verified'} builtin seed history: "
              f"{len(expected['sources'])} sources, {len(expected['files'])} paths")
        return 0
    except (OSError, ValueError, tarfile.TarError, subprocess.CalledProcessError) as error:
        print(f"[FAIL] builtin seed history: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
