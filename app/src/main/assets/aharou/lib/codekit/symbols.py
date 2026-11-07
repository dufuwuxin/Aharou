"""符号提取。Python 走 ast，Rust/Kotlin 走掩码文本 + 大括号归属。"""

from __future__ import annotations

import ast
import re
from dataclasses import dataclass
from typing import Optional

from .lexer import KOTLIN, PYTHON, RUST, scan_source

KIND_ORDER = [
    "mod", "impl", "trait", "class", "interface", "object", "struct", "enum",
    "union", "type", "const", "static", "macro", "fn", "method", "field",
]


@dataclass
class Symbol:
    name: str
    kind: str
    line: int
    col: int
    signature: str = ""
    container: Optional[str] = None
    children: list["Symbol"] = None

    def __post_init__(self):
        if self.children is None:
            self.children = []


# ---------------------------------------------------------------- Python

_PY_PUBLIC = {"name", "kind", "line", "col", "signature", "children", "container"}


def _py_signature(node: ast.AST, src_lines: list[str]) -> str:
    line = src_lines[node.lineno - 1].strip() if node.lineno - 1 < len(src_lines) else ""
    return line[:120]


def _py_doc_skip(body: list[ast.stmt]) -> list[ast.stmt]:
    if body and isinstance(body[0], ast.Expr) and isinstance(body[0].value, ast.Constant) \
            and isinstance(body[0].value.value, str):
        return body[1:]
    return body


def _py_symbols(node: ast.AST, src_lines: list[str], container: Optional[str] = None) -> list[Symbol]:
    out: list[Symbol] = []
    for child in ast.iter_child_nodes(node):
        if isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef)):
            sym = Symbol(
                name=child.name,
                kind="method" if container else "fn",
                line=child.lineno,
                col=child.col_offset + 1,
                signature=_py_signature(child, src_lines),
                container=container,
            )
            sym.children = _py_symbols(child, src_lines, child.name)
            out.append(sym)
        elif isinstance(child, ast.ClassDef):
            sym = Symbol(
                name=child.name,
                kind="class",
                line=child.lineno,
                col=child.col_offset + 1,
                signature=_py_signature(child, src_lines),
            )
            sym.children = _py_symbols(child, src_lines, child.name)
            out.append(sym)
        elif isinstance(child, (ast.If, ast.Try, ast.With, ast.For, ast.While)):
            out.extend(_py_symbols(child, src_lines, container))
    return out


def python_symbols(src: str) -> tuple[list[Symbol], Optional[str]]:
    try:
        tree = ast.parse(src)
    except SyntaxError as exc:
        return [], f"第 {exc.lineno} 行: {exc.msg}"
    return _py_symbols(tree, src.splitlines()), None


# ---------------------------------------------------------------- Rust

_RS_DEFS: list[tuple[str, re.Pattern]] = [
    ("mod", re.compile(r"^\s*(?:pub(?:\([^)]*\))?\s+)?mod\s+([A-Za-z_]\w*)")),
    ("macro", re.compile(r"^\s*(?:pub(?:\([^)]*\))?\s+)?macro_rules!\s*([A-Za-z_]\w*)")),
    ("trait", re.compile(r"^\s*(?:pub(?:\([^)]*\))?\s+)?(?:unsafe\s+)?(?:auto\s+)?trait\s+([A-Za-z_]\w*)")),
    ("struct", re.compile(r"^\s*(?:pub(?:\([^)]*\))?\s+)?struct\s+([A-Za-z_]\w*)")),
    ("enum", re.compile(r"^\s*(?:pub(?:\([^)]*\))?\s+)?enum\s+([A-Za-z_]\w*)")),
    ("union", re.compile(r"^\s*(?:pub(?:\([^)]*\))?\s+)?union\s+([A-Za-z_]\w*)")),
    ("type", re.compile(r"^\s*(?:pub(?:\([^)]*\))?\s+)?type\s+([A-Za-z_]\w*)")),
    ("const", re.compile(r"^\s*(?:pub(?:\([^)]*\))?\s+)?const\s+([A-Za-z_]\w*)")),
    ("static", re.compile(r"^\s*(?:pub(?:\([^)]*\))?\s+)?static\s+(?:mut\s+)?([A-Za-z_]\w*)")),
]

_RS_FN = re.compile(
    r"^\s*(?:pub(?:\s*\([^)]*\))?\s+)?(?:default\s+)?(?:const\s+)?(?:async\s+)?"
    r"(?:unsafe\s+)?(?:extern\s+\"[^\"]*\"\s+)?fn\s+([A-Za-z_]\w*)"
)
_RS_IMPL = re.compile(
    r"^\s*(?:unsafe\s+)?impl(?:<[^>]*>)?\s+(.+?)(?:\s+for\s+(.+?))?\s*(?:where\b.*)?\{?\s*$"
)


def _brace_delta(line: str) -> tuple[int, int]:
    return line.count("{"), line.count("}")


def rust_symbols(src: str) -> tuple[list[Symbol], Optional[str]]:
    scan = scan_source(src, RUST)
    masked = scan.masked()
    lines = masked.splitlines()
    raw_lines = src.splitlines()

    top: list[Symbol] = []
    stack: list[tuple[Symbol, int]] = []  # (符号, 开启时的深度)
    depth = 0

    for idx, line in enumerate(lines, 1):
        stripped = line.strip()
        if not stripped:
            continue

        container = stack[-1][0] if stack else None
        sym: Optional[Symbol] = None
        opens_block = False

        if stripped.startswith("impl") or re.match(r"^\s*(?:unsafe\s+)?impl\b", line):
            m = _RS_IMPL.match(line)
            if m:
                target = (m.group(1) or "").strip()
                tr = (m.group(2) or "").strip()
                name = f"{tr} for {target}" if tr else target
                sym = Symbol(
                    name=name, kind="impl", line=idx,
                    col=line.index("impl") + 1,
                    signature=raw_lines[idx - 1].strip()[:120],
                )
                opens_block = sym_end_opens(line)
        elif container and container.kind in ("impl", "trait", "mod"):
            m = _RS_FN.match(line)
            if m:
                kind = "method" if container.kind in ("impl", "trait") else "fn"
                sym = Symbol(
                    name=m.group(1), kind=kind, line=idx,
                    col=line.index("fn") + 1,
                    signature=raw_lines[idx - 1].strip()[:120],
                    container=container.name,
                )
                opens_block = sym_end_opens(line)
        if sym is None:
            m = _RS_FN.match(line)
            if m:
                sym = Symbol(
                    name=m.group(1), kind="fn", line=idx,
                    col=line.index("fn") + 1,
                    signature=raw_lines[idx - 1].strip()[:120],
                    container=container.name if container else None,
                )
                opens_block = sym_end_opens(line)

        if sym is None:
            for kind, pat in _RS_DEFS:
                m = pat.match(line)
                if m:
                    sym = Symbol(
                        name=m.group(1), kind=kind, line=idx,
                        col=line.find(m.group(1)) + 1,
                        signature=raw_lines[idx - 1].strip()[:120],
                        container=container.name if container else None,
                    )
                    opens_block = sym_end_opens(line)
                    break

        if sym is not None:
            target = container.children if container is not None else top
            target.append(sym)
            if opens_block:
                stack.append((sym, depth))

        o, c = _brace_delta(line)
        depth += o - c
        while stack and depth <= stack[-1][1]:
            stack.pop()

    return top, None


def sym_end_opens(line: str) -> bool:
    return line.rstrip().endswith("{")


# ---------------------------------------------------------------- Kotlin

_KT_DEFS: list[tuple[str, re.Pattern]] = [
    ("object", re.compile(r"^\s*(?:@\w+\s+)*(?:public|private|internal|protected|open|abstract|sealed|final)?\s*(companion\s+)?object\s*(`[^`]+`|[A-Za-z_]\w*)?")),
    ("interface", re.compile(r"^\s*(?:@\w+\s+)*(?:public|private|internal|protected)?\s*(?:sealed\s+)?(?:fun\s+)?interface\s+(`[^`]+`|[A-Za-z_]\w*)")),
    ("class", re.compile(r"^\s*(?:@\w+\s+)*(?:public|private|internal|protected)?\s*(?:(?:data|sealed|enum|open|abstract|inner|value|annotation)\s+)*class\s+(`[^`]+`|[A-Za-z_]\w*)")),
    ("type", re.compile(r"^\s*(?:public|private|internal)?\s*typealias\s+(`[^`]+`|[A-Za-z_]\w*)")),
]
_KT_FUN = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:public|private|internal|protected)?\s*"
    r"(?:override\s+|open\s+|abstract\s+|final\s+|inline\s+|suspend\s+|operator\s+|infix\s+|tailrec\s+|external\s+|actual\s+|expect\s+)*"
    r"fun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.]*\.)?(`[^`]+`|[A-Za-z_]\w*)"
)
_KT_PROP = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:public|private|internal|protected)?\s*"
    r"(?:override\s+|open\s+|abstract\s+|const\s+|lateinit\s+|actual\s+|expect\s+)*"
    r"(val|var)\s+(`[^`]+`|[A-Za-z_]\w*)"
)


def _paren_open(line: str) -> bool:
    return line.count("(") > line.count(")")


def kotlin_symbols(src: str) -> tuple[list[Symbol], Optional[str]]:
    scan = scan_source(src, KOTLIN)
    masked = scan.masked()
    lines = masked.splitlines()
    raw_lines = src.splitlines()

    top: list[Symbol] = []
    stack: list[tuple[Symbol, int]] = []
    depth = 0
    pending: Optional[Symbol] = None

    for idx, line in enumerate(lines, 1):
        if not line.strip():
            continue
        container = stack[-1][0] if stack else None
        sym: Optional[Symbol] = None
        opens_block = False

        m = _KT_FUN.match(line)
        if m:
            kind = "method" if container and container.kind in ("class", "object", "interface") else "fn"
            name = m.group(1)
            sym = Symbol(
                name=name, kind=kind, line=idx,
                col=line.find(name) + 1,
                signature=raw_lines[idx - 1].strip()[:120],
                container=container.name if container else None,
            )
            opens_block = line.rstrip().endswith("{")

        if sym is None:
            for kind, pat in _KT_DEFS:
                m = pat.match(line)
                if m:
                    if kind == "object":
                        companion, nm = m.group(1), m.group(2)
                        name = nm or ("Companion" if companion else "anonymous")
                    else:
                        name = m.group(1)
                    sym = Symbol(
                        name=name, kind=kind, line=idx,
                        col=line.find(name) + 1,
                        signature=raw_lines[idx - 1].strip()[:120],
                        container=container.name if container else None,
                    )
                    opens_block = line.rstrip().endswith("{")
                    break

        if sym is None and container and container.kind in ("class", "object", "interface"):
            m = _KT_PROP.match(line)
            if m:
                name = m.group(2)
                sym = Symbol(
                    name=name, kind="field", line=idx,
                    col=line.find(name) + 1,
                    signature=raw_lines[idx - 1].strip()[:120],
                    container=container.name,
                )

        if sym is not None:
            pending = None
            target = container.children if container is not None else top
            target.append(sym)
            if opens_block:
                stack.append((sym, depth))
            elif _paren_open(line):
                # 多行签名：等后续行的 `{` 再建立作用域
                pending = sym
        elif pending is not None:
            # 多行签名结束于 `) {` 时才建立作用域，否则继续等
            if line.rstrip().endswith("{"):
                stack.append((pending, depth))
                pending = None

        o, c = _brace_delta(line)
        depth += o - c
        while stack and depth <= stack[-1][1]:
            stack.pop()

    return top, None


# ---------------------------------------------------------------- 统一入口


def extract_symbols(src: str, language: str) -> tuple[list[Symbol], Optional[str]]:
    if language == PYTHON:
        return python_symbols(src)
    if language == RUST:
        return rust_symbols(src)
    if language == KOTLIN:
        return kotlin_symbols(src)
    raise ValueError(f"不支持的语言: {language}")


def flatten(symbols: list[Symbol], depth: int = 0) -> list[tuple[Symbol, int]]:
    out: list[tuple[Symbol, int]] = []
    for s in symbols:
        out.append((s, depth))
        out.extend(flatten(s.children, depth + 1))
    return out


_KIND_LABEL = {
    "impl": "impl", "trait": "trait", "class": "class", "interface": "interface",
    "object": "object", "struct": "struct", "enum": "enum", "union": "union",
    "mod": "mod", "type": "type", "const": "const", "static": "static",
    "macro": "macro", "fn": "fn", "method": "fn", "field": "prop",
}


def render_symbols(symbols: list[Symbol], filename: str) -> str:
    if not symbols:
        return f"{filename}: 未找到符号"
    out = [f"{filename}:"]
    for sym, depth in flatten(symbols):
        pad = "  " * (depth + 1)
        label = _KIND_LABEL.get(sym.kind, sym.kind)
        sig = sym.signature
        out.append(f"{pad}{label} {sym.name}  :{sym.line}   {sig}")
    return "\n".join(out)
