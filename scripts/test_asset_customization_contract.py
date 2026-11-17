#!/usr/bin/env python3
"""Source-only asset customization contracts; no Kotlin/runtime/release claims.

Run from any directory, optionally against an integration tree:
  python3 scripts/test_asset_customization_contract.py --root /path/to/tree
Controls use synthetic source text and never modify a checkout or runtime config.
"""

import argparse
import re
import unittest
from dataclasses import dataclass
from pathlib import Path

from check_native_memory_contract import masked, reachable

FEATURE = "app/src/main/java/com/aharou/feature/"
SKILL = FEATURE + "agent/domain/skill/"
SETTINGS = FEATURE + "settings/presentation/"
ROOT = Path(__file__).resolve().parents[1]


@dataclass(frozen=True)
class Contract:
    path: str
    entry: str | None
    pattern: str
    reason: str
    fixture: str

    def accepts(self, source: str) -> bool:
        # Reuse the existing comment-aware scanner; this is not a Kotlin parser.
        body = reachable(source, self.entry) if self.entry else source
        return re.search(self.pattern, masked(body), re.DOTALL) is not None


def rule(path: str, entry: str | None, pattern: str, reason: str, body: str) -> Contract:
    fixture = f"fun {entry}() {{ {body} }}" if entry else body
    return Contract(path, entry, pattern, reason, fixture)


CONTRACTS = (
    rule(SKILL + "Skill.kt", None, r"val\s+tags\s*:\s*List<String>\s*=\s*emptyList\(\)",
         "legacy Skill/SkillForm tags default", "val tags: List<String> = emptyList()"),
    rule(SKILL + "Skill.kt", None, r"val\s+typeId\s*:\s*String\?\s*=\s*null",
         "legacy type is unclassified", "val typeId: String? = null"),
    rule(SKILL + "SkillParser.kt", "parseText", r'frontmatter\["tags"\]',
         "parser consumes saved tags", 'val tags = frontmatter["tags"]'),
    rule(SKILL + "SkillParser.kt", "parseText", r'frontmatter\["type_id"\]',
         "parser consumes saved type reference", 'val type = frontmatter["type_id"]'),
    rule(SKILL + "SkillParser.kt", "serialize", r"tags\.(?:joinToString|forEach).*?(?:Frontmatter\.quote|quoteMetadata)\(",
         "serialize quotes tag values", 'tags.joinToString { quoteMetadata(it) }'),
    rule(SKILL + "SkillParser.kt", "serialize", r"(?:Frontmatter\.quote\(typeId\)|typeId\?.*?quoteMetadata\(it\))",
         "serialize quotes type reference", "typeId?.let { quoteMetadata(it) }"),
    rule(SKILL + "SkillRepository.kt", "save", r"(?:tags\s*=\s*form\.tags|val\s+tags\s*=\s*form\.tags.*?tags\s*=\s*tags)",
         "save preserves form tags", "val tags = form.tags; SkillParser.serialize(tags = tags)"),
    rule(SKILL + "SkillRepository.kt", "save", r"(?:typeId\s*=\s*form\.typeId|val\s+typeId\s*=\s*form\.typeId.*?typeId\s*=\s*typeId)",
         "save preserves form type", "val typeId = form.typeId; SkillParser.serialize(typeId = typeId)"),
    rule(SKILL + "SkillRepository.kt", "exportBuiltin", r"tags\s*=\s*skill\.tags.*?typeId\s*=\s*skill\.typeId",
         "builtin export preserves metadata", "SkillParser.serialize(tags = skill.tags, typeId = skill.typeId)"),
    rule(SKILL + "SkillTypeRepository.kt", None, r"fun\s+list\(.*?fun\s+(?:save|get)\(",
         "types have independent repository", "fun list() {} fun save() {}"),
    rule(SKILL + "SkillTypeRepository.kt", "save", r"\b(?:writeFile|writeText)\(",
         "types persist beyond editor state", 'writeFile("types.json", text)'),
    rule(SKILL + "SkillRepository.kt", "loadInstructions", r"\b\w*[Tt]ype\w*\.(?:get|resolve|instructions)\(",
         "instruction loader consumes type repository", "skillTypeRepository.get(id, scope)"),
    rule(FEATURE + "agent/data/ConfigSkillFields.kt", "registerInto", r"\bregistry\.register\(\w*[Tt]ype\w*\(",
         "type config collection is registered", "registry.register(TypeCollection())"),
    rule(SETTINGS + "SkillSettingsController.kt", "refreshSkills", r"\btags\s*=\s*(?:\w+\.)+tags.*?\btypeId\s*=\s*(?:\w+\.)+typeId",
         "list receives metadata from real skills", "SkillUiEntry(tags = skill.tags, typeId = skill.typeId)"),
    rule(SETTINGS + "SkillModels.kt", None, r'"tag:".*?"type:"',
         "installed list model exposes explicit tag/type query", 'val tag = "tag:"; val type = "type:"'),
    rule(SETTINGS + "component/SkillsSection.kt", None, r"\.matchesSkillQuery\(",
         "installed list calls the real query predicate", "entry.matchesSkillQuery(query)"),
    rule(SETTINGS + "component/SkillEditorScreen.kt", None, r"\bSkillForm\(.*?tags\s*=.*?typeId\s*=",
         "editor submits tags and type", "SkillForm(tags = tags, typeId = typeId)"),
    rule(FEATURE + "agent/domain/context/ContextSearch.kt", "search", r'\btagQuery\(query\)',
         "memory search consumes explicit tag parser", 'val parsed = tagQuery(query)'),
    rule(FEATURE + "agent/domain/context/ContextSearch.kt", "tagQuery", r'"tag:"',
         "memory tag parser recognizes explicit tag syntax", 'val prefix = "tag:"'),
    rule("app/src/main/java/com/aharou/di/AgentModule.kt", None,
         r'\bregister\("prompt_edit",\s*\w*[Pp]romptEdit\w*\)',
         "prompt_edit has a real registry consumer", 'registry.register("prompt_edit", promptEditTool)'),
    rule(SKILL + "SkillRepository.kt", "loadInstructions",
         r"listOf\(type\.semantics,\s*type\.functions,\s*body\)",
         "common type guidance precedes specialty body", "listOf(type.semantics, type.functions, body)"),
    rule(SKILL + "SkillRepository.kt", "save",
         r"skillTypes\.get\(typeId,\s*scope\)\s*==\s*null.*?return@save",
         "unknown type references are rejected before persistence",
         "if (skillTypes.get(typeId, scope) == null) return@save error"),
    rule(SKILL + "SkillTypeRepository.kt", "list",
         r"if\s*\(scope\s*==\s*SkillScope\.PROJECT\).*?listLocal\(scope\).*?else\s+global",
         "only project callers may inherit project type definitions",
         "return if (scope == SkillScope.PROJECT) listLocal(scope) else global"),
    rule("app/src/main/java/com/aharou/AIEditorApp.kt", "onCreate",
         r"\b\w*[Uu]pgrade\w*\.(?:start|prepare|request|check|initialize)\(",
         "startup invokes an upgrade-consent coordinator before managed asset writes",
         "assetUpgradeCoordinator.start()"),
)


def violations(root: Path) -> list[str]:
    failures = []
    sources = {}
    for contract in CONTRACTS:
        if contract.path not in sources:
            path = root / contract.path
            sources[contract.path] = path.read_text(encoding="utf-8") if path.is_file() else ""
        if not contract.accepts(sources[contract.path]):
            failures.append(f"{contract.path}: {contract.reason}")
    # Both public models must retain compatible defaults, not just one of them.
    model = masked(sources[SKILL + "Skill.kt"])
    for name in ("Skill", "SkillForm"):
        match = re.search(rf"data class {name}\((.*?)\n\)", model, re.DOTALL)
        fields = match[1] if match else ""
        for field in ("tags", "typeId"):
            if not re.search(rf"\bval\s+{field}\s*:", fields):
                failures.append(f"{SKILL}Skill.kt: {name} missing {field}")
    startup = root / "app/src/main/java/com/aharou/AIEditorApp.kt"
    if startup.is_file():
        body = masked(reachable(startup.read_text(encoding="utf-8"), "onCreate"))
        gate = re.search(r"\b\w*[Uu]pgrade\w*\.(?:start|prepare|request|check|initialize)\(", body)
        writes = re.search(r"ContainerInstaller\.extract(?:Skills|Agents|Teams)\(", body)
        if writes and (gate is None or gate.start() > writes.start()):
            failures.append("AIEditorApp.kt: managed startup writes precede upgrade coordinator")
    return failures


class ContractControls(unittest.TestCase):
    def test_each_positive_fixture_is_recognized(self):
        for contract in CONTRACTS:
            with self.subTest(reason=contract.reason):
                self.assertTrue(contract.accepts(contract.fixture))

    def test_missing_implementation_is_rejected(self):
        for contract in CONTRACTS:
            with self.subTest(reason=contract.reason):
                self.assertFalse(contract.accepts(""))

    def test_comment_only_implementation_is_rejected(self):
        for contract in CONTRACTS:
            with self.subTest(reason=contract.reason):
                self.assertFalse(contract.accepts("/* " + contract.fixture + " */"))

    def test_unreachable_helper_cannot_satisfy_entry_contract(self):
        for contract in CONTRACTS:
            if contract.entry:
                with self.subTest(reason=contract.reason):
                    decoy = f"fun {contract.entry}() {{ }}\n" + contract.fixture.replace(
                        f"fun {contract.entry}()", "fun disconnected()", 1)
                    self.assertFalse(contract.accepts(decoy))


class CheckoutContracts(unittest.TestCase):
    def test_checkout_sources(self):
        self.assertEqual([], violations(ROOT), "Static source contract failures (not runtime results)")


def main() -> int:
    global ROOT
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--controls-only", action="store_true")
    args = parser.parse_args()
    ROOT = args.root.resolve()
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(ContractControls)
    if not args.controls_only:
        suite.addTests(unittest.defaultTestLoader.loadTestsFromTestCase(CheckoutContracts))
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    if not args.controls_only:
        for failure in violations(ROOT):
            print(f"FAIL {failure}")
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
