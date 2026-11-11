#!/usr/bin/env python3
"""Check native-memory source contracts, not Kotlin runtime behavior.

Uses comment/string-aware body extraction and local call traversal, not a Kotlin
compiler. Unknown source shapes fail with a diagnostic; passing is static evidence
only. Run isolated controls with scripts/test_native_memory_contract.py.
"""

import argparse
import re
from pathlib import Path

BASE = Path("app/src/main/java/com/aharou/feature")
DOMAIN = BASE / "agent/domain"
PROVIDER = DOMAIN / "prompt/SystemPromptProvider.kt"
RESOLVER = DOMAIN / "prompt/PromptFileResolver.kt"
REPOSITORY = DOMAIN / "context/ContextLibraryRepository.kt"
BUILTINS = DOMAIN / "context/BuiltinContextEntries.kt"
SPEC = DOMAIN / "tool/subagent/TaskToolSpec.kt"
TASK = DOMAIN / "tool/subagent/TaskTool.kt"
TEAMS = DOMAIN / "tool/subagent/TeamTasks.kt"
RUNNER = DOMAIN / "tool/subagent/TeamRunner.kt"
SESSIONS = DOMAIN / "tool/session/SessionTool.kt"
SETTINGS = BASE / "settings/presentation/component/SettingsScreen.kt"
MENU = BASE / "settings/presentation/component/SettingsMenuScreen.kt"
PROJECTS = BASE / "agent/presentation/component/ProjectListScreen.kt"
GROUPS = BASE / "settings/presentation/component/SettingsGroupScreens.kt"
REQUIRED = (PROVIDER, RESOLVER, REPOSITORY, BUILTINS, SPEC, TASK, TEAMS,
            RUNNER, SESSIONS, SETTINGS, PROJECTS)
TOKEN = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|/\*[\s\S]*?\*/|//[^\n]*')


def masked(text: str, strings: bool = False) -> str:
    """Keep offsets/newlines stable; comments never satisfy any assertion."""
    return TOKEN.sub(lambda m: re.sub(r"[^\n]", " ", m[0])
                     if strings or m[0].startswith(("//", "/*")) else m[0], text)


def executable(text: str) -> str:
    """Include Kotlin string-template expressions, not inert quoted examples."""
    def replace(match: re.Match[str]) -> str:
        token = match[0]
        if token.startswith(('//', '/*')):
            return re.sub(r"[^\n]", " ", token)
        expressions = re.findall(r"\$\{([^}]*)\}", token)
        return " " + " ".join(expressions) + " "
    return TOKEN.sub(replace, text)


def balanced(text: str, start: int, left: str, right: str) -> int:
    depth = 0
    for pos in range(start, len(text)):
        if text[pos] == left:
            depth += 1
        elif text[pos] == right:
            depth -= 1
            if depth == 0:
                return pos + 1
    raise ValueError(f"unbalanced {left}{right} at offset {start}")


def bodies(text: str, name: str) -> list[str]:
    code = masked(text, strings=True)
    results = []
    for match in re.finditer(rf"\bfun\s+(?:<[^>]+>\s*)?{re.escape(name)}\s*\(", code):
        end = balanced(code, code.index("(", match.start()), "(", ")")
        tail = re.search(r"[={]", code[end:])
        if tail is None:
            continue
        start = end + tail.start()
        if code[start] == "{":
            stop = balanced(code, start, "{", "}")
        else:
            # An expression can contain lambdas, so stop at the next declaration
            # rather than its first newline. This deliberately overapproximates.
            declaration = re.search(r"\n\s*(?:(?:private|internal|override|suspend)\s+)*fun\s+", code[start:])
            stop = start + declaration.start() if declaration else len(code)
        results.append(text[start:stop])
    return results


def reachable(text: str, entry: str) -> str:
    pending, seen, parts = [entry], set(), []
    names = set(re.findall(r"\bfun\s+(\w+)\s*\(", masked(text, strings=True)))
    while pending:
        name = pending.pop()
        if name in seen:
            continue
        seen.add(name)
        for body in bodies(text, name):
            parts.append(body)
            calls = set(re.findall(r"\b(\w+)\s*\(", masked(body, strings=True)))
            pending.extend((calls & names) - seen)
    return "\n".join(parts)


def violations(root: Path) -> list[str]:
    errors: list[str] = []
    texts: dict[Path, str] = {}
    for path in (*REQUIRED, MENU, GROUPS):
        try:
            texts[path] = masked((root / path).read_text(encoding="utf-8"))
        except FileNotFoundError:
            texts[path] = ""
            if path in REQUIRED:
                errors.append(f"{path}: missing required source")

    def require(path: Path, text: str, pattern: str, reason: str) -> None:
        if not re.search(pattern, text, re.DOTALL):
            errors.append(f"{path}: {reason}")

    def forbid(path: Path, text: str, pattern: str, reason: str) -> None:
        for match in re.finditer(pattern, text):
            errors.append(f"{path}: {reason}: {match[0].strip()}")

    provider = masked(texts[PROVIDER], strings=True)
    forbid(PROVIDER, provider, r"\b(?:renderStatic|renderCustomOnly|StaticRuleSource|SubAgentListSource)\s*\(|"
           r"\b(?:definition|agentDefinition)\s*\??\.\s*(?:prompt|systemPrompt)\b|"
           r"\b(?:staticRuleSource|subAgentListSource|projectRuleSource)\s*\.\s*build\s*\(",
           "old prompt/role source remains executable")
    require(PROVIDER, masked(reachable(texts[PROVIDER], "build"), strings=True),
            r"\bcontextLibrary\s*\.\s*render\s*\(", "build must reach native library render")
    require(PROVIDER, masked(reachable(texts[PROVIDER], "resolvePrompt"), strings=True),
            r"\b(?:promptFileResolver|contextLibrary)\s*\.\s*(?:resolve|resolvePrompt)\s*\(",
            "resolvePrompt must delegate to native resolution")
    resolver = texts[RESOLVER]
    forbid(RESOLVER, resolver, r'"(?:prompts(?:\.custom)?(?:/[^"\n]*)?|\.no-builtin)"|'
           r'\b(?:PromptFragmentResolver|customFragmentsByNumber|readFileOrNull)\b|\.assets\s*\.\s*open\s*\(',
           "legacy prompt directory/assets fallback")
    for entry in ("resolve", "resolveRaw"):
        require(RESOLVER, masked(reachable(resolver, entry), strings=True),
                r"\bcontextLibrary\s*\??\.\s*resolvePrompt\s*\(", f"{entry} must reach native prompt entries")
    repository = texts[REPOSITORY]
    seed_fields = re.findall(r"\b(?:val|var)\s+(\w+)\s*:\s*BuiltinContextEntries\b", repository)
    list_path = masked(reachable(repository, "list"), strings=True)
    if not any(re.search(rf"\b{field}\s*\??\.\s*\w+\s*\(", list_path) for field in seed_fields):
        errors.append(f"{REPOSITORY}: list must reach BuiltinContextEntries seeding, not just declare its type")
    require(BUILTINS, masked(texts[BUILTINS], strings=True), r"\b(?:memories|memoryRepository)\s*\.\s*(?:writeMemory|save|saveMemory)\s*\(",
            "builtins must persist entries through MemoryRepository")

    action = re.search(r'"action"\s+to\s+ToolParameter\s*\(', texts[SPEC])
    if action:
        start = texts[SPEC].index("(", action.start())
        stop = balanced(masked(texts[SPEC], strings=True), start, "(", ")")
        parameter = texts[SPEC][start:stop]
    else:
        parameter = ""
    enum = re.search(r"\benum\s*=\s*(?:listOf|setOf)\s*\(([^)]*)\)", parameter)
    values = re.findall(r'"([^"\\]*)"', enum[1]) if enum else []
    if set(values) != {"teams", "team_run"} or len(values) != 2:
        errors.append(f"{SPEC}: action enum must contain exactly teams/team_run, found {values}")
    dispatch = "\n".join(bodies(texts[TASK], "executeWithContext"))
    require(TASK, dispatch, r'"teams"\s*->', "teams dispatch missing")
    require(TASK, dispatch, r'"team_run"\s*->', "team_run dispatch missing")
    forbid(TASK, dispatch, r'"(?:create|read|send|roles|pipeline|grant|list|team|tell|stop|del)"\s*(?:,|->)',
           "old task action remains reachable")
    # Main-facing list may delegate to renderList, but never to execution details.
    listing = reachable(texts[TEAMS], "list")
    if re.search(r"\bTeamRunner\s*\.\s*renderList\s*\(", executable(listing)):
        listing += reachable(texts[RUNNER], "renderList")
    forbid(TEAMS, executable(listing), r"\.\s*(?:renderDetail|stages|members|admins|referencedMembers|prompt|systemPrompt|taskCount|rejectEdges)\b",
           "team listing exposes execution details")
    for field in ("name", "description"):
        require(TEAMS, executable(listing), rf"\.\s*{field}\b", f"team listing missing {field}")

    session = texts[SESSIONS]
    require(SESSIONS, executable(reachable(session, "listSessions")),
            r"\.getAllSessionsByWorkspaceOnce\s*\(\s*workspace\s*\)",
            "list must include member sessions in the current workspace")
    search = executable(reachable(session, "searchMessages"))
    for pattern in (r"\.(?:filter|filterNot|mapNotNull)\b", r"\.getSessionById\s*\(",
                    r"\.workspacePath\s*==\s*workspace"):
        require(SESSIONS, search, pattern, "search must verify target workspace")
    for handler in ("readSession", "inspectSession"):
        target = executable(reachable(session, handler))
        require(SESSIONS, target, r"\.getSessionById\s*\(", f"{handler} must resolve target ownership")
        require(SESSIONS, target,
                r"if\s*\([^)]*\.workspacePath\s*!=\s*workspace[^)]*\)\s*(?:\{\s*)?return\s+ToolResult\.Error\s*\(",
                f"{handler} must reject cross-workspace targets")
        forbid(SESSIONS, target, r"\.parent(?:Session)?Id\s*!=\s*null",
               f"{handler} must allow saved member process access")

    settings = texts[SETTINGS]
    menu_source = settings + texts[MENU] + texts[GROUPS]
    menus = reachable(menu_source, "SettingsMenu")
    if texts[MENU] and not menus:
        menus = texts[MENU]
    if not menus:
        errors.append(f"{SETTINGS}: settings menu implementation missing")
    forbid(SETTINGS, menus, r"SettingsSection\.(?:Memory|Soul|Knowledge|Prompts|Rules)\b",
           "top-level menu must not duplicate native memory/configuration entries")
    require(SETTINGS, settings, r"SettingsSection\.Memory\s*->\s*ContextLibrarySection\s*\(",
            "native memory section missing")
    require(SETTINGS, settings, r"SettingsSection\.Memory\s*->\s*\(?configHome",
            "memory must return to configuration home")
    if texts[GROUPS]:
        count = len(re.findall(r"onOpen\s*\(\s*SettingsSection\.Memory\s*\)", texts[GROUPS]))
        if count != 1:
            errors.append(f"{GROUPS}: expected one configuration memory entry, found {count}")

    project = texts[PROJECTS]
    code = masked(project, strings=True)
    require(PROJECTS, code, r"\.combinedClickable\s*\(", "project row requires combinedClickable")
    long_click = re.search(r"\bonLongClick\s*=\s*\{", code)
    menu_state = ""
    if long_click:
        start = code.index("{", long_click.start())
        callback = code[start:balanced(code, start, "{", "}")]
        assignment = re.search(r"\b(\w+)\s*=\s*(?:project|true)\b", callback)
        menu_state = assignment[1] if assignment else ""
    if not menu_state or not re.search(rf"DropdownMenu\s*\([^)]*expanded\s*=\s*[^,)]*\b{menu_state}\b", code):
        errors.append(f"{PROJECTS}: long-press state must control project DropdownMenu")
    require(PROJECTS, code, r"DropdownMenuItem\s*\(", "project deletion menu item missing")
    forbid(PROJECTS, code, r"(?:TextButton|IconButton)\s*\(\s*onClick\s*=\s*\{\s*deletingProject\s*=\s*project",
           "always-visible project delete button remains")
    require(PROJECTS, project, r"AlertDialog\s*\([\s\S]*?confirmButton\s*=\s*\{[\s\S]*?viewModel\.deleteProject\s*\(project\.id\)",
            "existing deletion confirmation must remain")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    try:
        errors = violations(args.root)
    except (OSError, ValueError) as exc:
        print(f"FAIL: cannot inspect sources: {exc}")
        return 1
    for error in errors:
        print(f"FAIL: {error}")
    print(f"Native memory static contract: {'FAIL' if errors else 'PASS'} ({len(errors)} violations; not runtime verification)")
    return int(bool(errors))


if __name__ == "__main__":
    raise SystemExit(main())
