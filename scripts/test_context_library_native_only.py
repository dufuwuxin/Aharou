#!/usr/bin/env python3
"""Native-library source contracts and isolated regression controls; no Gradle."""
import argparse
import re
import tempfile
from pathlib import Path

BASE = Path("app/src/main/java/com/aharou")
DOMAIN = BASE / "feature/agent/domain"
UI = BASE / "feature/settings/presentation/component"
SECTION = UI / "ContextLibrarySection.kt"
EDITOR = UI / "ContextLibraryEditor.kt"
NAVIGATION = BASE / "feature/settings/presentation/ContextLibraryNavigation.kt"
DRAFT_SAVER = UI / "ContextEntryState.kt"
ENTRY = DOMAIN / "context/ContextEntry.kt"
REPOSITORY = DOMAIN / "context/ContextLibraryRepository.kt"
PROVIDER = DOMAIN / "prompt/SystemPromptProvider.kt"
CATALOG = DOMAIN / "prompt/PromptFragmentCatalog.kt"
CONFIG = BASE / "feature/agent/data/ConfigContextLibraryFields.kt"
TOOL = DOMAIN / "tool/memory/MemoryTool.kt"
SOURCES = (SECTION, ENTRY, REPOSITORY, PROVIDER, CATALOG, CONFIG, TOOL, NAVIGATION, DRAFT_SAVER,
           UI / "ContextLibraryComponents.kt", EDITOR,
           DOMAIN / "context/ContextLibraryPlanner.kt")


def without_comments(text: str) -> str:
    # Preserve strings and line numbers so comments cannot satisfy positive contracts.
    tokens = r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|/\*[\s\S]*?\*/|//[^\n]*'
    return re.sub(tokens, lambda m: re.sub(r"[^\n]", " ", m[0])
                  if m[0].startswith(("//", "/*")) else m[0], text)


def violations(root: Path) -> list[str]:
    errors: list[str] = []
    texts: dict[Path, str] = {}
    for path in SOURCES:
        try:
            texts[path] = without_comments((root / path).read_text(encoding="utf-8"))
        except FileNotFoundError:
            errors.append(f"{path}: missing required source")
            texts[path] = ""

    def forbid(path: Path, pattern: str, reason: str) -> None:
        for match in re.finditer(pattern, texts[path]):
            line = texts[path].count("\n", 0, match.start()) + 1
            errors.append(f"{path}:{line}: {reason}: {match[0]}")

    def require(path: Path, pattern: str, reason: str) -> None:
        if not re.search(pattern, texts[path], re.DOTALL):
            errors.append(f"{path}: {reason}")

    forbid(SECTION, r"\b(?:maintenance|LegacyMemorySection|onOpenSource|ContextSourceRow)\b|"
           r"context_(?:tab_sources|sources_\w+|source_\w+|compatibility_\w+)|"
           r"SettingsSection\.(?:Soul|Knowledge|Prompts)", "old page or source navigation")
    for name in ("context_tab_library", "context_tab_preview", "ContextLibraryPreview"):
        require(SECTION, rf"\b{name}\b", f"missing native library/preview: {name}")
    forbid(EDITOR, r"enabled\s*=\s*[^\n)]*entry\s*==\s*null|entry\?\.editable",
           "native editor fields must remain editable")
    require(SECTION, r"\.copy\s*\([^)]*\btitle\s*=\s*name\b", "renamed title must reach repository save")
    for name in ("contextChildFolders", "contextParentFolder", "contextFolderEntries"):
        require(NAVIGATION, rf"\bfun\s+{name}\s*\(", f"navigation helper missing: {name}")
        require(SECTION, rf"\b{name}\s*\(", f"navigation helper not wired: {name}")
    for name in ("ContextFolderRow", "context_folder_up"):
        require(SECTION, rf"\b{name}\b", f"hierarchical folder UI missing: {name}")
    forbid(SECTION, r"\bcontext_folder_all\b|folder\s*==\s*null\s*\|\|", "folder browser must not flatten all folders")
    require(SECTION, r"if\s*\(\s*currentFolder\s*==\s*null\s*\)\s*emptyList\s*\(",
            "folder overview must not include document bodies")
    forbid(NAVIGATION, r"folder\s*==\s*null\s*\|\|",
           "folder entry selection must stay at one level")
    require(NAVIGATION, r"\b(?:data\s+)?class\s+ContextEditorDraft\b", "structured editor draft missing")
    require(NAVIGATION, r"\bfun\s+changeCategory\s*\(", "atomic category transition missing")
    require(DRAFT_SAVER, r"Saver\s*<\s*ContextEditorDraft", "draft persistence saver missing")
    require(EDITOR, r"\bContextEditorDraft\b", "editor does not use structured draft")
    require(EDITOR, r"\bchangeCategory\s*\(", "category control bypasses draft transition")
    require(EDITOR, r"stringResource\s*\(\s*(?:draft\.)?category\.explanation\s*\(\s*\)\s*\)",
            "category explanation must resolve current category resource")
    for path in (SECTION, EDITOR):
        require(path, r"\bBackHandler\s*\(", "nested page must consume its own back navigation")
    require(ENTRY, r"enum\s+class\s+ContextCategory\s*\{\s*RULE\s*,\s*TEMPORARY\s*,\s*LONG_TERM\s*,\s*INJECTED\s*\}",
            "four native categories must remain")
    require(ENTRY, r"enum\s+class\s+ContextLoad\s*\{\s*FULL\s*,\s*INDEX\s*,\s*SEARCH\s*,\s*OFF\s*\}",
            "only FULL/INDEX/SEARCH/OFF loading is allowed")
    require(ENTRY, r"enum\s+class\s+ContextOrigin\s*\{\s*MEMORY\s*\}",
            "only native MEMORY origin is allowed")
    for path in (ENTRY, REPOSITORY, DOMAIN / "context/ContextLibraryPlanner.kt"):
        forbid(path, r"\b(?:LEGACY|legacyReason|ContextPolicyStore)\b", "legacy loading or sidecar")
    forbid(REPOSITORY, r"\b(?:SoulStore|SoulFile|SystemPromptBuilder|KnowledgeRepository|"
           r"PromptFragmentCatalog|PromptFragmentSource|RuleFileRepository|AharouMemoryStore|"
           r"ContainerInstaller|InjectionBlock)\b|context-library|\.disabledBlocks\s*\(",
           "old source or switch dependency")
    require(REPOSITORY, r"\bMemoryRepository\b", "native memory repository dependency missing")
    for name in ("withPromptSnapshot", "list", "save", "create", "delete", "search", "preview", "render"):
        require(REPOSITORY, rf"\bfun\s+{name}\s*\(", f"native API missing: {name}")
    for path in (UI / "ContextLibraryComponents.kt", UI / "ContextLibraryEditor.kt"):
        forbid(path, r"\bsourceLabel\b|context_(?:source_detail|origin_\w+)|ContextLoad\.LEGACY|"
               r"ContextOrigin\.(?:RULE|PROMPT|KNOWLEDGE|PERSONA)", "legacy source label or loading")
    for name in ("entries", "folders"):
        require(CONFIG, rf'"context_library\.{name}"', f"config channel missing: {name}")
    for action in ("library_list", "library_search", "library_read", "library_save", "library_delete"):
        require(TOOL, rf'"{action}"\s*->|"{action}"\s*,', f"memory library action missing: {action}")
    forbid(PROVIDER, r"\b(?:SoulSource|AharouMemorySource|CoreArchiveSource|MemoryPromptRenderer)\b|"
           r"\blistMemoriesForPrompt\s*\(|\b(?:personaPrompt|nativeArchive|renderArchive|legacyRuleText)\s*\(|"
           r"ContextOrigin\.(?:RULE|PROMPT|KNOWLEDGE|PERSONA)", "old persistent prompt consumer")
    render_calls = list(re.finditer(r"\bcontextLibrary\.render\s*\(", texts[PROVIDER]))
    if len(render_calls) != 1:
        errors.append(f"{PROVIDER}: expected one shared native render call, found {len(render_calls)}")
    require(PROVIDER, r"class\s+MemoryListSource\b", "shared memory consumer missing")
    require(PROVIDER, r"\bresolvePrompt\s*\(\s*MEMORY_DISCIPLINE_FILE\s*\)", "memory discipline asset consumption missing")
    require(PROVIDER, r"\bwithPromptSnapshot\s*\(", "prompt snapshot privacy boundary missing")
    require(PROVIDER, r"InjectPart\.MEMORY\s+in\s+definition\.inject", "subagent memory opt-in missing")
    require(PROVIDER, r"\bif\s*\(\s*(?:isGhostSession\(agentContext\)|ghost)\s*\)\s*null\s*else\s*memoryListSource\.build",
            "ghost memory privacy gate missing")
    forbid(CATALOG, r"\b(?:contextPolicies|ContextPolicyStore)\b", "prompt sidecar adapter")
    for path in (DOMAIN / "context/ContextPolicyStore.kt",
                 Path("app/src/test/java/com/aharou/feature/agent/domain/context/ContextPolicyStoreTest.kt")):
        if (root / path).exists():
            errors.append(f"{path}: obsolete sidecar must be removed, not stubbed")
    return errors


def negative_controls(root: Path) -> None:
    mutations = (
        (SECTION, "private fun nativeOnlyRegression(open: (SettingsSection) -> Unit) { open(SettingsSection.Soul) }\n",
         "old page or source navigation: SettingsSection.Soul"),
        (REPOSITORY, "private fun nativeOnlyRegression(entry: ContextEntry): Boolean { return entry.loading == ContextLoad.LEGACY }\n",
         "legacy loading or sidecar: LEGACY"),
        (PROVIDER, "private fun nativeOnlyRegression(ctx: AgentContext) = memoryRepository.listMemoriesForPrompt(ctx.projectRoot, ctx.currentUserText)\n",
         "old persistent prompt consumer: listMemoriesForPrompt("),
        (EDITOR, "private fun nativeOnlyRegression(entry: ContextEntry?) { AppTextField(value = entry?.title.orEmpty(), onValueChange = {}, enabled = entry == null) }\n",
         "native editor fields must remain editable: enabled = entry == null"),
        (SECTION, "private fun nativeOnlyRegression(entries: List<ContextEntry>, folder: String?) = entries.filter { folder == null || it.folder == folder }\n",
         "folder browser must not flatten all folders: folder == null ||"),
    )
    with tempfile.TemporaryDirectory(prefix="aharou-native-contract-") as temporary:
        target = Path(temporary)
        for path in SOURCES:
            if not (root / path).is_file():
                continue
            destination = target / path
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text((root / path).read_text(encoding="utf-8"), encoding="utf-8")
        for path, code, expected in mutations:
            destination = target / path
            original = destination.read_text(encoding="utf-8")
            insertion = original.rfind("}")
            if insertion < 0:
                raise AssertionError(f"No real Kotlin body found: {path}")
            # Insert executable Kotlin, not comment tokens; assert the new line is rejected.
            mutated = original[:insertion] + "\n" + code + original[insertion:]
            destination.write_text(mutated, encoding="utf-8")
            line = original[:insertion].count("\n") + 2
            assert any(f"{path}:{line}:" in error and expected in error for error in violations(target)), \
                f"Regression escaped: {path}: {expected}"
            destination.write_text(original, encoding="utf-8")
            print(f"NEGATIVE_CONTROL PASS: {path.name}: {expected}")
        assert without_comments('// ContextLibraryPreview\n/* context_tab_library */').strip() == ""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--negative-controls", action="store_true", help="test isolated executable regressions only")
    args = parser.parse_args()
    if args.negative_controls:
        negative_controls(args.root)
        return 0
    errors = violations(args.root)
    for error in errors:
        print(f"FAIL {error}")
    if errors:
        print(f"NATIVE_ONLY RED: {len(errors)} contract violations")
        return 1
    print("NATIVE_ONLY GREEN: UI, native repository, single consumer and config/tool contracts")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
