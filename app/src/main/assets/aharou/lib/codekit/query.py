"""Bounded workspace snapshots; never executes source, compilers or project hooks."""
from __future__ import annotations

import argparse
import ast
import hashlib
import json
import os
import re
import shutil
import sys
import time
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from codekit.lexer import bracket_issues, detect_language, scan_source
from codekit.symbols import extract_symbols, flatten

MAX_BYTES = 1_048_576
MAX_FILES = 400
MAX_ENTRIES = 8000
MAX_OUTPUT = 24_000
MAX_CHECK_ERRORS = 200
SKIP = {".aharou", ".codegraph", ".kotlin", ".ruff_cache", ".git", "target", "build",
        "node_modules", "__pycache__", ".gradle", "dist", "out", ".idea", ".venv", "venv",
        ".mypy_cache", ".pytest_cache"}


def emit(data):
    data.setdefault("truncated", False)
    text = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    while len(text) > MAX_OUTPUT and any(data.get(key) for key in ("results", "files", "errors")):
        for key in ("results", "files", "errors"):
            if data.get(key):
                data[key].pop()
                break
        data["truncated"] = True
        data["partial"] = True
        if data.get("action") == "check" and data.get("valid") is True:
            data["valid"] = None
        text = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    print(text)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("status", "symbols", "map", "callers", "check"))
    parser.add_argument("--path", default=".")
    parser.add_argument("--name", default="")
    parser.add_argument("--limit", type=int, default=80)
    args = parser.parse_args()
    if args.action == "status":
        emit({"languages": ["kotlin", "python", "rust"], "analysis": "structural-not-semantic",
              "python": sys.version.split()[0], "codegraph_installed": shutil.which("codegraph") is not None,
              "installs_runtime": False, "index": "fresh-bounded-snapshot"})
        return 0
    if args.action == "callers" and not args.name.strip():
        emit({"error": "NAME_REQUIRED"})
        return 2
    root = Path.cwd().resolve()
    target = (root / args.path).resolve()
    if not target.is_relative_to(root):
        emit({"error": "OUTSIDE_WORKSPACE"})
        return 2
    if not target.exists():
        emit({"error": "FILE_NOT_FOUND"})
        return 2
    limit = max(1, min(args.limit, 200))
    deadline = time.monotonic() + 8
    data = {"action": args.action, "analysis": "structural-not-semantic", "index": "fresh-bounded-snapshot",
            "results": [], "files": [], "errors": [], "partial": False, "truncated": False,
            "scanned_files": 0, "visited_entries": 0}
    checking = args.action == "check"
    diagnostics_found = False
    if checking:
        data.update(analysis="lexical-not-compiler", valid=None, checked_files=0)
    pattern = re.compile(r"(?<![\w])" + re.escape(args.name) + r"\s*\(") if args.action == "callers" else None

    def candidates():
        if target.is_file():
            yield target
            return
        for directory, dirs, files in os.walk(target, followlinks=False):
            dirs[:] = sorted(d for d in dirs if d not in SKIP and not Path(directory, d).is_symlink())
            data["visited_entries"] += len(dirs) + len(files)
            if data["visited_entries"] > MAX_ENTRIES or time.monotonic() > deadline:
                data["truncated"] = True
                return
            for name in sorted(files):
                file = Path(directory, name)
                if detect_language(name) and not file.is_symlink():
                    yield file

    for path in candidates():
        if data["scanned_files"] >= MAX_FILES or time.monotonic() > deadline:
            data["truncated"] = True
            break
        if path.is_symlink() or not path.resolve().is_relative_to(root):
            continue
        language = detect_language(str(path))
        if language is None:
            data["errors"].append({"file": str(path.relative_to(root)), "error": "UNSUPPORTED_LANGUAGE"})
            data["partial"] = True
            continue
        rel = str(path.relative_to(root))
        data["scanned_files"] += 1
        try:
            with path.open("rb") as stream:
                raw = stream.read(MAX_BYTES + 1)
            if len(raw) > MAX_BYTES:
                raise ValueError("FILE_TOO_LARGE")
            source = raw.decode("utf-8")
            if "\x00" in source:
                raise ValueError("BINARY_SOURCE")
            data["files"].append({"file": rel, "sha256": hashlib.sha256(raw).hexdigest(), "language": language})
            if checking:
                scan = scan_source(source, language)
                issues = scan.issues + bracket_issues(scan)
                errors = []
                if language == "python":
                    try:
                        ast.parse(source, filename=rel)
                    except SyntaxError as error:
                        errors.append({"file": rel, "line": error.lineno or 1,
                                       "column": max(1, error.offset or 1), "error": error.msg[:160]})
                diagnostic_count = len(issues) + len(errors)
                diagnostics_found = diagnostics_found or diagnostic_count > 0
                remaining = max(0, MAX_CHECK_ERRORS - len(data["errors"]))
                for issue in issues[:remaining]:
                    line, column = scan.pos(issue.offset)
                    errors.append({"file": rel, "line": line, "column": column,
                                   "error": issue.message[:160]})
                if diagnostic_count > remaining:
                    data["truncated"] = True
                data["errors"].extend(errors[:remaining])
                data["checked_files"] += 1
                continue
            symbols, error = extract_symbols(source, language)
            if error:
                data["errors"].append({"file": rel, "error": str(error)[:160]})
            scan = scan_source(source, language)
            for issue in scan.issues + bracket_issues(scan):
                line, column = scan.pos(issue.offset)
                data["errors"].append({"file": rel, "line": line, "column": column, "error": issue.message[:160]})
            if pattern is not None:
                lines = source.splitlines()
                definitions = set()
                for symbol, _ in flatten(symbols):
                    offset = lines[symbol.line - 1].find(symbol.name, symbol.col - 1)
                    if offset >= 0:
                        definitions.add((symbol.line, offset + 1))
                for match in pattern.finditer(scan.masked()):
                    line, column = scan.pos(match.start())
                    if (line, column) not in definitions:
                        data["results"].append({"file": rel, "line": line, "column": column,
                                                "language": language, "name": args.name})
                    if len(data["results"]) > limit:
                        break
            else:
                for symbol, _ in flatten(symbols):
                    if args.name and args.name.casefold() not in symbol.name.casefold():
                        continue
                    data["results"].append({"file": rel, "language": language, "name": symbol.name,
                                            "kind": symbol.kind, "line": symbol.line,
                                            "container": symbol.container, "signature": symbol.signature[:160]})
                    if len(data["results"]) > limit:
                        break
        except (OSError, UnicodeError, ValueError, RecursionError) as error:
            data["errors"].append({"file": rel, "error": str(error)[:160]})
            data["partial"] = True
        if len(data["results"]) > limit:
            data["results"] = data["results"][:limit]
            data["truncated"] = True
            break
    if checking:
        data["partial"] = data["partial"] or data["truncated"]
        data["valid"] = (False if diagnostics_found else
                         None if data["partial"] or not data["checked_files"] else True)
    else:
        data["partial"] = bool(data["errors"]) or data["truncated"]
    emit(data)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
