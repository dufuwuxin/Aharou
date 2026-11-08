#!/usr/bin/env python3
"""Build pinned LSP sources on native Ubuntu ARM64; never publish a release."""

import argparse
import json
import os
import platform
import queue
import shutil
import subprocess
import tarfile
import tempfile
import threading
from pathlib import Path

import vendor_language_extensions as vendor

NODE = {"typescript", "shell", "yaml", "vue"}
PACKAGE_ROOT = {"typescript": "", "shell": "server", "yaml": "", "vue": "packages/language-server"}
ENTRY = {"typescript": "lib/cli.mjs", "shell": "out/cli.js", "yaml": "bin/yaml-language-server",
         "vue": "bin/vue-language-server.js"}


def extract_source(bundle: Path, destination: Path) -> Path:
    destination.mkdir()
    with tarfile.open(bundle / "source.tar.gz") as archive:
        members = archive.getmembers()
        roots = {Path(m.name).parts[0] for m in members}
        if len(roots) != 1:
            raise ValueError("source archive must have one root")
        archive.extractall(destination, filter="data")
    source = destination / roots.pop()
    source.rename(destination / "source")
    return destination / "source"


def package_node(package: Path, target: Path, extra: tuple[str, ...] = ()) -> Path:
    """Copy the runtime dependency graph; replace pnpm store links with private relative links."""
    copied = {}
    store = target / "modules"
    store.mkdir()

    def resolve(start: Path, name: str) -> Path:
        for parent in (start, *start.parents):
            candidate = parent / "node_modules" / name
            if (candidate / "package.json").is_file():
                return candidate.resolve()
        raise ValueError(f"missing runtime Node dependency: {name}")

    def copy_module(module: Path, extras: tuple[str, ...] = ()) -> Path:
        module = module.resolve()
        if module in copied:
            return copied[module]
        dest = store / str(len(copied))
        copied[module] = dest
        shutil.copytree(module, dest, symlinks=False,
                        ignore=shutil.ignore_patterns("node_modules", ".git", "test", "tests", "__tests__", ".github"))
        meta = json.loads((module / "package.json").read_text())
        required = dict(meta.get("dependencies", {}))
        optional = dict(meta.get("optionalDependencies", {}))
        # Peer packages present in the locked installation can be required at runtime.
        peers = dict(meta.get("peerDependencies", {}))
        for name in dict.fromkeys([*required, *optional, *peers, *extras]):
            try:
                dependency = resolve(module, name)
            except ValueError:
                if name in optional or meta.get("peerDependenciesMeta", {}).get(name, {}).get("optional"):
                    continue
                raise
            dependency_dest = copy_module(dependency)
            link = dest / "node_modules" / name
            link.parent.mkdir(parents=True, exist_ok=True)
            link.symlink_to(os.path.relpath(dependency_dest, link.parent))
        return dest

    return copy_module(package, extra)


def assemble(name: str, release: Path, payload: Path, bundle: Path) -> None:
    payload.mkdir()
    source = release / "source"
    if name in NODE:
        module = package_node(source / PACKAGE_ROOT[name], payload,
                              ("typescript",) if name in {"typescript", "vue"} else ())
        entry = (module / ENTRY[name]).relative_to(payload).as_posix()
        command = f'exec node "$here/{entry}" "$@"'
    elif name == "python":
        shutil.copytree(release / "lib", payload / "lib", ignore=shutil.ignore_patterns("bin", "__pycache__"))
        command = 'export PYTHONPATH="$here/lib${PYTHONPATH:+:$PYTHONPATH}"\nexec python3 -m pylsp "$@"'
    elif name == "rust":
        (payload / "bin").mkdir()
        shutil.copy2(source / "target/release/rust-analyzer", payload / "bin/rust-analyzer")
        command = 'exec "$here/bin/rust-analyzer" "$@"'
    else:
        for item in ("bin", "script", "meta", "locale"):
            shutil.copytree(source / item, payload / item, symlinks=False)
        for item in ("main.lua", "debugger.lua"):
            if (source / item).is_file():
                shutil.copy2(source / item, payload / item)
        command = 'exec "$here/bin/lua-language-server" "$@"'
    for item in ("LICENSE", "source.json"):
        shutil.copy2(bundle / item, payload / item)
    (payload / "launch.sh").write_text('#!/bin/sh\nset -eu\nhere=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)\n' + command + "\n")
    (payload / "launch.sh").chmod(0o755)


def audit_payload(payload: Path) -> list[str]:
    libraries = set()
    for path in payload.rglob("*"):
        if path.is_symlink():
            if os.path.isabs(os.readlink(path)) or not path.resolve().is_relative_to(payload.resolve()):
                raise ValueError(f"non-relocatable symlink: {path}")
        elif path.is_file():
            with path.open("rb") as stream:
                header = stream.read(20)
            if header.startswith(b"\x7fELF"):
                if len(header) < 20 or header[4:6] != b"\x02\x01" or int.from_bytes(header[18:20], "little") != 183:
                    raise ValueError(f"not a native ARM64 ELF: {path}")
                result = subprocess.run(["ldd", str(path)], capture_output=True, text=True)
                text = result.stdout + result.stderr
                if "not found" in text:
                    raise ValueError(f"unresolved dynamic dependency: {path}: {text}")
                if result.returncode and "not a dynamic executable" not in text and "statically linked" not in text:
                    raise ValueError(f"ldd failed: {path}: {text}")
                libraries.update(line.strip().split(" => ")[0] for line in result.stdout.splitlines() if " => " in line)
    return sorted(libraries)


def probe_lsp(command: list[str], timeout: float = 90) -> None:
    messages = queue.Queue()
    with tempfile.TemporaryFile() as errors:
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=errors)

        def reader() -> None:
            try:
                while True:
                    headers = {}
                    while line := process.stdout.readline():
                        if line in (b"\r\n", b"\n"):
                            break
                        key, value = line.decode("ascii").split(":", 1)
                        headers[key.lower()] = value.strip()
                    if not line:
                        raise ValueError("LSP stdout closed before response")
                    size = int(headers["content-length"])
                    if not 0 < size <= 16 * 1024 * 1024:
                        raise ValueError("invalid LSP frame size")
                    body = process.stdout.read(size)
                    messages.put(json.loads(body))
            except (ValueError, KeyError, OSError) as exc:
                messages.put(exc)

        def send(message: dict) -> None:
            body = json.dumps({"jsonrpc": "2.0", **message}).encode()
            process.stdin.write(f"Content-Length: {len(body)}\r\n\r\n".encode() + body)
            process.stdin.flush()

        def response(identifier: int) -> dict:
            import time
            deadline = time.monotonic() + timeout
            while True:
                message = messages.get(timeout=max(0.001, deadline - time.monotonic()))
                if isinstance(message, Exception):
                    raise message
                if message.get("id") == identifier and "method" not in message:
                    if "error" in message:
                        raise ValueError(f"LSP request failed: {message['error']}")
                    return message
                if "id" in message and "method" in message:
                    send({"id": message["id"], "result": [] if message["method"] == "workspace/configuration" else None})

        threading.Thread(target=reader, daemon=True).start()
        try:
            send({"id": 1, "method": "initialize", "params": {"processId": None, "rootUri": None, "capabilities": {}}})
            initialized = response(1)
            if not isinstance(initialized.get("result", {}).get("capabilities"), dict):
                raise ValueError("LSP initialize missing capabilities")
            send({"method": "initialized", "params": {}})
            send({"id": 2, "method": "shutdown", "params": None})
            if response(2).get("result", "missing") is not None:
                raise ValueError("invalid LSP shutdown response")
            send({"method": "exit"})
            process.stdin.close()
            if process.wait(timeout=timeout) != 0:
                raise ValueError("LSP did not exit successfully")
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
            process.stdout.close()
            if not process.stdin.closed:
                process.stdin.close()


def pack(payload: Path, output: Path) -> None:
    audit_payload(payload)
    with tarfile.open(output, "w:gz", dereference=False) as archive:
        for path in sorted(payload.iterdir()):
            archive.add(path, arcname=path.name)


def build(name: str, output: Path) -> None:
    if os.environ.get("GITHUB_ACTIONS") != "true" or platform.machine() not in {"aarch64", "arm64"}:
        raise ValueError("build requires native ARM64 GitHub Actions")
    os_release = platform.freedesktop_os_release()
    if os_release.get("ID") != "ubuntu" or os_release.get("VERSION_ID") != "24.04":
        raise ValueError("build requires Ubuntu 24.04")
    entries = vendor.verify()
    entry = next(item for item in entries if item["id"] == name)
    bundle = vendor.BUNDLES / name / "bundle"
    output.mkdir(parents=True, exist_ok=True)
    filename = f"{name}-linux-arm64.tar.gz"
    # A failed retry must not leave a stale manifest that looks publishable.
    (output / f"{name}.json").unlink(missing_ok=True)
    (output / filename).unlink(missing_ok=True)
    with tempfile.TemporaryDirectory(prefix="aharou-lsp-") as folder:
        work = Path(folder)
        release = work / "build"
        extract_source(bundle, release)
        subprocess.run(["/bin/sh", str(bundle / "install.sh"), str(release)], check=True)
        payload = work / "payload"
        assemble(name, release, payload, bundle)
        # Validate after relocating, not while CI build paths are accidentally reachable.
        moved = work / "relocated payload"
        payload.rename(moved)
        libraries = audit_payload(moved)
        args = ["start"] if name == "shell" else ["--stdio"] if name in NODE else ["--locale=zh-cn"] if name == "lua" else []
        probe_lsp([str(moved / "launch.sh"), *args])
        temporary = output / (filename + ".part")
        try:
            pack(moved, temporary)
            temporary.replace(output / filename)
        finally:
            temporary.unlink(missing_ok=True)
    runtime = ["Ubuntu 24.04 ARM64"]
    if name in NODE:
        runtime.append("node >=24")
    if name == "python":
        runtime.append("python3.12")
    result = {key: entry[key] for key in ("id", "command", "revision", "sourceSha256", "version")}
    result.update(fileName=filename, sha256=vendor.digest(output / filename), sizeBytes=(output / filename).stat().st_size,
                  prerequisites=runtime + libraries, verification="LSP initialize/shutdown/exit passed after relocation")
    (output / f"{name}.json").write_text(json.dumps(result, indent=2) + "\n")


def manifest(output: Path) -> None:
    catalog = json.loads((vendor.ASSETS / "catalog.json").read_text())
    vendor.verify()
    destination = output / "manifest.json"
    destination.unlink(missing_ok=True)
    entries = []
    for source in catalog["extensions"]:
        name = source["id"]
        entry = json.loads((output / f"{name}.json").read_text())
        if any(entry[key] != source[key] for key in ("id", "command", "revision", "sourceSha256", "version")):
            raise ValueError(f"{name}: source identity mismatch")
        expected = f"{name}-linux-arm64.tar.gz"
        artifact = output / expected
        if not 0 < entry["sizeBytes"] <= 512 * 1024 * 1024:
            raise ValueError(f"{name}: exceeds application artifact budget")
        if entry["fileName"] != expected or entry["sizeBytes"] != artifact.stat().st_size or entry["sha256"] != vendor.digest(artifact):
            raise ValueError(f"{name}: artifact integrity mismatch")
        entries.append(entry)
    if sum(entry["sizeBytes"] for entry in entries) > 1536 * 1024 * 1024:
        raise ValueError("artifact set exceeds application total budget")
    result = {key: catalog[key] for key in ("schemaVersion", "platform", "sourceSetSha256")}
    result["extensions"] = entries
    destination.write_text(json.dumps(result, indent=2) + "\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--id", choices=vendor.COMMANDS)
    parser.add_argument("--manifest", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.manifest == bool(args.id):
        parser.error("choose exactly one of --id or --manifest")
    if args.manifest:
        manifest(args.output)
    else:
        build(args.id, args.output)


if __name__ == "__main__":
    main()
