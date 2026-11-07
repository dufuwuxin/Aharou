"""三语言词法扫描。

把注释、字符串、字符字面量标记出来，产出一份「等长掩码」，
后续所有结构分析都在掩码上做，正则因此不会被注释里的
`fn foo(` 或字符串里的 "class Bar" 骗到。
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Optional

RUST = "rust"
PYTHON = "python"
KOTLIN = "kotlin"

EXT_LANG = {
    ".rs": RUST,
    ".py": PYTHON,
    ".pyi": PYTHON,
    ".kt": KOTLIN,
    ".kts": KOTLIN,
}


def detect_language(path: str) -> Optional[str]:
    dot = path.rfind(".")
    if dot < 0:
        return None
    return EXT_LANG.get(path[dot:].lower())


@dataclass
class Span:
    start: int
    end: int
    kind: str  # comment | string | char


@dataclass
class Issue:
    offset: int
    message: str


@dataclass
class Scan:
    language: str
    source: str
    spans: list[Span] = field(default_factory=list)
    issues: list[Issue] = field(default_factory=list)

    _masked: Optional[str] = None
    _line_starts: Optional[list[int]] = None

    def masked(self) -> str:
        """注释/字符串/字符内容替换为空格，长度与换行保持不变。"""
        if self._masked is None:
            buf = list(self.source)
            for sp in self.spans:
                for k in range(sp.start, min(sp.end, len(buf))):
                    if buf[k] != "\n":
                        buf[k] = " "
            self._masked = "".join(buf)
        return self._masked

    def line_starts(self) -> list[int]:
        if self._line_starts is None:
            starts = [0]
            for i, ch in enumerate(self.source):
                if ch == "\n":
                    starts.append(i + 1)
            self._line_starts = starts
        return self._line_starts

    def pos(self, offset: int) -> tuple[int, int]:
        """offset -> (行, 列)，均从 1 开始。"""
        starts = self.line_starts()
        lo, hi = 0, len(starts) - 1
        while lo < hi:
            mid = (lo + hi + 1) // 2
            if starts[mid] <= offset:
                lo = mid
            else:
                hi = mid - 1
        return lo + 1, offset - starts[lo] + 1

    def in_noncode(self, offset: int) -> bool:
        """该偏移是否落在注释/字符串内。"""
        for sp in self.spans:
            if sp.start <= offset < sp.end:
                return True
            if sp.start > offset:
                break
        return False


def _ident_char(ch: str) -> bool:
    return ch.isalnum() or ch == "_"


# ---------------------------------------------------------------- Rust

_RS_RAW = re.compile(r"(?:br|cr|r)(#*)\"")
_RS_PREFIXED = re.compile(r"(?:b|c)?\"")


def _scan_rust(src: str) -> Scan:
    scan = Scan(RUST, src)
    spans, issues = scan.spans, scan.issues
    n = len(src)
    i = 0

    while i < n:
        c = src[i]

        if c == "/" and i + 1 < n:
            nxt = src[i + 1]
            if nxt == "/":
                j = src.find("\n", i)
                j = n if j < 0 else j
                spans.append(Span(i, j, "comment"))
                i = j
                continue
            if nxt == "*":
                depth = 1
                j = i + 2
                while j < n and depth:
                    if src.startswith("/*", j):
                        depth += 1
                        j += 2
                    elif src.startswith("*/", j):
                        depth -= 1
                        j += 2
                    else:
                        j += 1
                if depth:
                    issues.append(Issue(i, "块注释未闭合"))
                spans.append(Span(i, j, "comment"))
                i = j
                continue

        # 原始字符串 r"..." / r#"..."# / br#"..."#
        if c in "bcr" and (i == 0 or not _ident_char(src[i - 1])):
            m = _RS_RAW.match(src, i)
            if m:
                hashes = m.group(1)
                close = '"' + hashes
                j = src.find(close, m.end())
                if j < 0:
                    issues.append(Issue(i, "原始字符串未闭合"))
                    j = n
                else:
                    j += len(close)
                spans.append(Span(i, j, "string"))
                i = j
                continue

        # 普通字符串 b"..." / c"..." / "..."
        if c == '"' or (c in "bc" and (i == 0 or not _ident_char(src[i - 1]))
                        and _RS_PREFIXED.match(src, i)):
            start = i
            m = _RS_PREFIXED.match(src, i)
            j = m.end() if m and c in "bc" else i + 1
            closed = False
            while j < n:
                ch = src[j]
                if ch == "\\":
                    j += 2
                    continue
                if ch == '"':
                    j += 1
                    closed = True
                    break
                j += 1
            if not closed:
                issues.append(Issue(start, "字符串未闭合"))
            spans.append(Span(start, j, "string"))
            i = j
            continue

        # 字符字面量与生命周期共用单引号
        if c == "'":
            if i + 1 < n and src[i + 1] == "\\":
                j = i + 2
                if j < n and src[j] == "u" and j + 1 < n and src[j + 1] == "{":
                    k = src.find("}", j + 2)
                    j = (k + 1) if k >= 0 else n
                elif j < n and src[j] == "x":
                    j += 3
                while j < n and src[j] != "'":
                    j += 1
                j = min(j + 1, n)
                spans.append(Span(i, j, "char"))
                i = j
                continue
            if i + 2 < n and src[i + 2] == "'":
                spans.append(Span(i, i + 3, "char"))
                i += 3
                continue
            # 'a 生命周期，不产生 span
            i += 1
            continue

        i += 1

    return scan


# ---------------------------------------------------------------- Kotlin

def _skip_kotlin_template(src: str, j: int) -> int:
    """从 `${` 之后的位置出发，返回匹配的 `}` 之后的位置。"""
    n = len(src)
    depth = 1
    while j < n:
        ch = src[j]
        if ch == '"':
            if src.startswith('"""', j):
                k = src.find('"""', j + 3)
                j = n if k < 0 else k + 3
            else:
                j += 1
                while j < n:
                    if src[j] == "\\":
                        j += 2
                        continue
                    if src[j] == '"':
                        j += 1
                        break
                    j += 1
            continue
        if ch == "'":
            j += 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == "'":
                    j += 1
                    break
                j += 1
            continue
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return j + 1
        j += 1
    return n


def _scan_kotlin(src: str) -> Scan:
    scan = Scan(KOTLIN, src)
    spans, issues = scan.spans, scan.issues
    n = len(src)
    i = 0

    while i < n:
        c = src[i]

        if c == "/" and i + 1 < n:
            nxt = src[i + 1]
            if nxt == "/":
                j = src.find("\n", i)
                j = n if j < 0 else j
                spans.append(Span(i, j, "comment"))
                i = j
                continue
            if nxt == "*":
                depth = 1
                j = i + 2
                while j < n and depth:
                    if src.startswith("/*", j):
                        depth += 1
                        j += 2
                    elif src.startswith("*/", j):
                        depth -= 1
                        j += 2
                    else:
                        j += 1
                if depth:
                    issues.append(Issue(i, "块注释未闭合"))
                spans.append(Span(i, j, "comment"))
                i = j
                continue

        # 原始字符串 """..."""
        if src.startswith('"""', i):
            k = src.find('"""', i + 3)
            if k < 0:
                issues.append(Issue(i, "原始字符串未闭合"))
                j = n
            else:
                j = k + 3
            spans.append(Span(i, j, "string"))
            i = j
            continue

        if c == '"':
            start = i
            j = i + 1
            closed = False
            while j < n:
                ch = src[j]
                if ch == "\\":
                    j += 2
                    continue
                if ch == "$" and j + 1 < n and src[j + 1] == "{":
                    j = _skip_kotlin_template(src, j + 2)
                    continue
                if ch == '"':
                    j += 1
                    closed = True
                    break
                j += 1
            if not closed:
                issues.append(Issue(start, "字符串未闭合"))
            spans.append(Span(start, j, "string"))
            i = j
            continue

        # 反引号标识符，内部可含任意字符
        if c == "`":
            k = src.find("`", i + 1)
            if k < 0:
                issues.append(Issue(i, "反引号标识符未闭合"))
                i = n
            else:
                i = k + 1
            continue

        if c == "'":
            start = i
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == "'":
                    j += 1
                    break
                if src[j] == "\n":
                    break
                j += 1
            spans.append(Span(start, j, "char"))
            i = j
            continue

        i += 1

    return scan


# ---------------------------------------------------------------- Python

_PY_PREFIX = re.compile(r"(?i)(?:rb|br|fr|rf|r|b|u|f)?(?:'''|\"\"\"|'|\")")


def _scan_python(src: str) -> Scan:
    scan = Scan(PYTHON, src)
    spans, issues = scan.spans, scan.issues
    n = len(src)
    i = 0

    while i < n:
        c = src[i]

        if c == "#":
            j = src.find("\n", i)
            j = n if j < 0 else j
            spans.append(Span(i, j, "comment"))
            i = j
            continue

        is_prefix = (
            c in "rbfuRBFU"
            and (i == 0 or not _ident_char(src[i - 1]))
        )
        if c in "'\"" or is_prefix:
            m = _PY_PREFIX.match(src, i)
            if not m:
                i += 1
                continue
            quote = m.group(0)[-1]
            triple = m.group(0)[-3:] in ("'''", '"""')
            start = i
            j = m.end()
            closed = False
            while j < n:
                ch = src[j]
                if ch == "\\":
                    j += 2
                    continue
                if triple:
                    if src.startswith(quote * 3, j):
                        j += 3
                        closed = True
                        break
                else:
                    if ch == quote:
                        j += 1
                        closed = True
                        break
                    if ch == "\n":
                        break
                j += 1
            if not closed:
                issues.append(Issue(start, "字符串未闭合"))
            spans.append(Span(start, j, "string"))
            i = j
            continue

        i += 1

    return scan


# ---------------------------------------------------------------- 括号配对

_PAIRS = {"(": ")", "[": "]", "{": "}"}
_CLOSERS = {v: k for k, v in _PAIRS.items()}


def bracket_issues(scan: Scan) -> list[Issue]:
    """在掩码上做括号配对检查。"""
    masked = scan.masked()
    stack: list[tuple[str, int]] = []
    out: list[Issue] = []
    for i, ch in enumerate(masked):
        if ch in _PAIRS:
            stack.append((ch, i))
        elif ch in _CLOSERS:
            if not stack:
                out.append(Issue(i, f"多余的闭合符号 '{ch}'"))
            elif stack[-1][0] != _CLOSERS[ch]:
                opened, pos = stack.pop()
                out.append(
                    Issue(i, f"'{ch}' 与第 {scan.pos(pos)[0]} 行的 '{opened}' 不匹配")
                )
            else:
                stack.pop()
    for opened, pos in stack:
        line, col = scan.pos(pos)
        out.append(Issue(pos, f"'{opened}' 未闭合（第 {line} 行第 {col} 列）"))
    return out


def scan_source(src: str, language: str) -> Scan:
    if language == RUST:
        return _scan_rust(src)
    if language == KOTLIN:
        return _scan_kotlin(src)
    if language == PYTHON:
        return _scan_python(src)
    raise ValueError(f"不支持的语言: {language}")
