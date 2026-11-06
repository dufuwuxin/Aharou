#!/usr/bin/env python3
"""Offline, bounded inventory and review hints. Never executes or uploads samples."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import struct
import sys
import zipfile
from contextlib import contextmanager
from pathlib import Path, PurePosixPath
from typing import BinaryIO, Iterator

MAX_SAMPLE = 128 * 1024 * 1024
MAX_SOURCE = 4 * 1024 * 1024
MAX_DIRECTORY = 2 * 1024 * 1024
MAX_ENTRIES = 2048
MAX_EXPANDED = 256 * 1024 * 1024
MAX_FINDINGS = 500
TOOLS = ("readelf", "objdump", "strings", "jadx", "apktool", "rizin", "radare2", "ghidra", "binwalk")
SENSITIVE_NAMES = {".env", "credentials", "credentials.json", "tokens.json", "accounts.json",
                   "keystore.properties", "id_rsa", "id_ed25519", "shadow"}
SENSITIVE_DIRS = {".ssh", ".aws", ".gnupg", ".config"}
SENSITIVE_SUFFIXES = {".pem", ".key", ".p12", ".pfx", ".jks", ".keystore"}
# Patterns deliberately identify review sites, not exploitable data flows.
RULES = (
    ("SQL-DYNAMIC", "sql", r"(?:execute|rawQuery|query)\s*\([^\n]*(?:\+|\$\{|\bf[\"'])", "Check bound parameters and untrusted input flow."),
    ("XSS-SINK", "xss", r"innerHTML\s*=|dangerouslySetInnerHTML|document\.write\s*\(", "Check contextual output encoding and sanitization."),
    ("CSRF-CONTROL", "csrf", r"csrf_exempt|csrf\s*\.\s*disable|csrf\s*\(\s*\)\s*\.\s*disable", "Check cookie authentication and anti-CSRF controls."),
    ("SSRF-CLIENT", "ssrf", r"requests\.(?:get|post)|urlopen\s*\(|fetch\s*\(|new\s+URL\s*\(", "Trace URL origin, redirects, DNS and destination allowlists."),
    ("COMMAND-SINK", "command", r"shell\s*=\s*True|os\.system\s*\(|Runtime\.getRuntime|ProcessBuilder\s*\(|\beval\s*\(|\bexec\s*\(", "Check argument boundaries and attacker-controlled code or commands."),
    ("PATH-SINK", "path", r"(?:open|readFile|writeFile|File)\s*\([^\n]*(?:\+|\$\{|\bf[\"'])|extractall\s*\(", "Check canonical containment, links and archive entry paths."),
    ("XML-PARSER", "xml", r"DOCTYPE|resolve_entities\s*=\s*True|DocumentBuilderFactory|XMLParser\s*\(", "Check external entities and entity expansion limits."),
    ("PII-FIELD", "pii", r"\b(?:email|phone|passport|id_card|access_token|api_key|password)\b", "Check collection scope, logging, retention and redaction; values omitted."),
    ("ANDROID-PERMISSION", "permissions", r"uses-permission|android:exported\s*=\s*[\"']true|setJavaScriptEnabled\s*\(\s*true", "Review permission necessity, exported components and WebView trust."),
    ("AUTOSTART-HOOK", "autostart", r"BOOT_COMPLETED|RECEIVE_BOOT_COMPLETED|/etc/cron|systemctl\s+enable|RunOnce", "Review authorized startup behavior; do not install persistence."),
)
COMPILED_RULES = tuple((key, group, re.compile(pattern, re.I), hint)
                       for key, group, pattern, hint in RULES)


class AuditError(Exception):
    """An error code safe to display without paths, contents or exception text."""


def checked_path(root: str, sample: str) -> Path:
    base = Path(os.path.abspath(root))
    path = Path(os.path.abspath(sample))
    try:
        path.relative_to(base)
    except ValueError:
        raise AuditError("outside_authorized_root") from None
    for part in (path, *path.parents):
        if part.is_symlink():
            raise AuditError("symlink_not_allowed")
    if not base.is_dir():
        raise AuditError("invalid_authorized_root")
    if any(part.lower() in SENSITIVE_DIRS for part in path.parts):
        raise AuditError("credential_path_not_allowed")
    name = path.name.lower()
    if (name in SENSITIVE_NAMES or name.startswith(".env.")
            or path.suffix.lower() in SENSITIVE_SUFFIXES):
        raise AuditError("credential_path_not_allowed")
    return path


@contextmanager
def sample_file(root: str, sample: str, maximum: int) -> Iterator[tuple[BinaryIO, os.stat_result]]:
    path = checked_path(root, sample)
    # O_NONBLOCK prevents a replaced FIFO from blocking before fstat; O_NOFOLLOW
    # protects the final component. Intermediate-directory races remain a limit.
    fd = os.open(path, os.O_RDONLY | os.O_NONBLOCK | os.O_NOFOLLOW)
    try:
        before = os.fstat(fd)
        if not stat.S_ISREG(before.st_mode):
            raise AuditError("regular_file_required")
        if before.st_size > maximum:
            raise AuditError("sample_size_limit")
        with os.fdopen(fd, "rb", closefd=False) as stream:
            yield stream, before
            after = os.fstat(fd)
            if (before.st_size, before.st_mtime_ns, before.st_ino) != (
                    after.st_size, after.st_mtime_ns, after.st_ino):
                raise AuditError("sample_changed_during_read")
    finally:
        os.close(fd)


def digest(stream: BinaryIO, maximum: int) -> str:
    result = hashlib.sha256()
    total = 0
    while block := stream.read(65536):
        total += len(block)
        if total > maximum:
            raise AuditError("sample_size_limit")
        result.update(block)
    stream.seek(0)
    return result.hexdigest()


def zip_metadata(stream: BinaryIO, size: int) -> dict:
    # Inspect EOCD before ZipFile allocates objects for every central entry.
    stream.seek(max(0, size - 65557))
    tail = stream.read(65557)
    offset = tail.rfind(b"PK\x05\x06")
    if offset < 0 or offset + 22 > len(tail):
        raise AuditError("invalid_zip_directory")
    _, disk, directory_disk, disk_count, count, directory_size, directory_offset, comment_size = struct.unpack_from("<4s4H2LH", tail, offset)
    absolute_offset = max(0, size - 65557) + offset
    if offset + 22 + comment_size != len(tail):
        raise AuditError("invalid_zip_directory")
    if disk or directory_disk or disk_count != count:
        raise AuditError("multidisk_zip_unsupported")
    if count == 65535 or directory_size == 0xFFFFFFFF or directory_offset == 0xFFFFFFFF:
        raise AuditError("zip64_unsupported")
    if count > MAX_ENTRIES or directory_size > MAX_DIRECTORY:
        raise AuditError("zip_directory_limit")
    if directory_offset + directory_size != absolute_offset:
        raise AuditError("invalid_zip_directory")
    # Count actual central records too: a hostile EOCD may understate the count.
    stream.seek(directory_offset)
    directory = stream.read(directory_size)
    position = actual = 0
    while position < len(directory):
        if directory[position:position + 4] != b"PK\x01\x02" or position + 46 > len(directory):
            raise AuditError("invalid_zip_directory")
        name_len, extra_len, note_len = struct.unpack_from("<3H", directory, position + 28)
        position += 46 + name_len + extra_len + note_len
        actual += 1
        if actual > MAX_ENTRIES:
            raise AuditError("zip_directory_limit")
    if position != len(directory) or actual != count:
        raise AuditError("invalid_zip_directory")
    stream.seek(0)
    with zipfile.ZipFile(stream) as archive:
        entries = archive.infolist()
        expanded = sum(entry.file_size for entry in entries)
        compressed = sum(entry.compress_size for entry in entries)
        unsafe = links = encrypted = high_ratio = dex_count = 0
        manifest = False
        for entry in entries:
            name = entry.filename.replace("\\", "/")
            parts = PurePosixPath(name).parts
            unsafe += int(name.startswith("/") or ".." in parts or bool(re.match(r"^[A-Za-z]:", name)))
            links += int(stat.S_ISLNK(entry.external_attr >> 16))
            encrypted += int(bool(entry.flag_bits & 1))
            high_ratio += int(entry.file_size > max(1, entry.compress_size) * 100)
            manifest |= name == "AndroidManifest.xml"
            dex_count += int(bool(re.fullmatch(r"classes(?:\d+)?\.dex", name)))
        return {"entry_count": len(entries), "declared_expanded_bytes": expanded,
                "declared_compressed_bytes": compressed, "unsafe_path_count": unsafe,
                "symlink_count": links, "encrypted_count": encrypted,
                "high_ratio_count": high_ratio, "expansion_limit_exceeded": expanded > MAX_EXPANDED,
                "apk_candidate": manifest and dex_count > 0, "manifest_present": manifest,
                "dex_entry_count": dex_count, "contents_read": False,
                "nested_archives_inspected": False, "signature_verified": False}


def binary_metadata(stream: BinaryIO, size: int) -> dict:
    header = stream.read(112)
    if header.startswith(b"\x7fELF"):
        if len(header) < 20 or header[4] not in (1, 2) or header[5] not in (1, 2):
            raise AuditError("invalid_elf_header")
        endian = "<" if header[5] == 1 else ">"
        kind, machine = struct.unpack_from(endian + "HH", header, 16)
        return {"format": "ELF", "bits": 32 if header[4] == 1 else 64,
                "byte_order": "little" if header[5] == 1 else "big",
                "object_type": kind, "machine": machine, "header_only": True}
    if header.startswith(b"MZ"):
        if len(header) < 64:
            raise AuditError("invalid_pe_header")
        offset = struct.unpack_from("<I", header, 60)[0]
        if offset < 64 or offset + 24 > size:
            raise AuditError("invalid_pe_header")
        stream.seek(offset)
        coff = stream.read(24)
        if coff[:4] != b"PE\0\0":
            raise AuditError("invalid_pe_header")
        machine, sections = struct.unpack_from("<HH", coff, 4)
        return {"format": "PE", "machine": machine, "sections": sections, "header_only": True}
    if header.startswith(b"dex\n"):
        if len(header) < 112 or not re.fullmatch(rb"dex\n\d{3}\x00", header[:8]):
            raise AuditError("invalid_dex_header")
        file_size, header_size, endian = struct.unpack_from("<III", header, 32)
        if file_size != size or header_size != 112 or endian != 0x12345678:
            raise AuditError("invalid_dex_header")
        return {"format": "DEX", "version": header[4:7].decode("ascii"), "header_only": True,
                "integrity_verified": False}
    if header.startswith((b"PK\x03\x04", b"PK\x05\x06")):
        return {"format": "ZIP", **zip_metadata(stream, size)}
    return {"format": "unknown", "header_only": True}


def inventory(root: str, sample: str) -> dict:
    with sample_file(root, sample, MAX_SAMPLE) as (stream, info):
        sha256 = digest(stream, MAX_SAMPLE)
        metadata = binary_metadata(stream, info.st_size)
        result = {"mode": "inventory", "sha256": sha256, "size_bytes": info.st_size,
                  "metadata": metadata, "assessment": "metadata_only_not_a_safety_verdict"}
    return result


def source_audit(root: str, sample: str) -> dict:
    with sample_file(root, sample, MAX_SOURCE) as (stream, info):
        sha256 = digest(stream, MAX_SOURCE)
        raw = stream.read(MAX_SOURCE + 1)
        if len(raw) > MAX_SOURCE:
            raise AuditError("sample_size_limit")
        if b"\0" in raw:
            raise AuditError("source_text_required")
        try:
            text = raw.decode("utf-8-sig")
        except UnicodeDecodeError:
            raise AuditError("source_utf8_required") from None
        findings = []
        truncated = False
        for number, line in enumerate(text.splitlines(), 1):
            if len(line) > 16384:
                raise AuditError("source_line_limit")
            for key, group, pattern, hint in COMPILED_RULES:
                if pattern.search(line):
                    if len(findings) == MAX_FINDINGS:
                        truncated = True
                        break
                    findings.append({"rule": key, "group": group, "line": number,
                                     "status": "manual_review_required", "hint": hint})
            if truncated:
                break
        result = {"mode": "source", "sha256": sha256, "size_bytes": info.st_size,
                  "findings": findings, "findings_truncated": truncated,
                  "assessment": "heuristic_hints_not_proven_vulnerabilities",
                  "coverage": "single_utf8_file_regex_no_dataflow_no_safety_verdict"}
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="mode", required=True)
    for mode in ("inventory", "source"):
        command = sub.add_parser(mode)
        command.add_argument("sample", help="Explicit authorized local regular file; never a URL")
        command.add_argument("--root", required=True, help="Authorized local directory boundary")
    sub.add_parser("tools", help="Check PATH only; never run or install tools")
    args = parser.parse_args(argv)
    try:
        if args.mode == "tools":
            result = {"mode": "tools", "tools": {name: shutil.which(name) is not None for name in TOOLS},
                      "executed": False, "installed": False}
        elif args.mode == "inventory":
            result = inventory(args.root, args.sample)
        else:
            result = source_audit(args.root, args.sample)
    except AuditError as error:
        result = {"error": str(error), "assessment": "not_assessed"}
        code = 2
    except (OSError, ValueError, zipfile.BadZipFile, struct.error):
        result = {"error": "unreadable_or_malformed_sample", "assessment": "not_assessed"}
        code = 2
    else:
        code = 0
    print(json.dumps(result, ensure_ascii=True, sort_keys=True))
    return code


if __name__ == "__main__":
    sys.exit(main())
