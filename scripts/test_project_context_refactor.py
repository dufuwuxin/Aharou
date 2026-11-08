#!/usr/bin/env python3
"""Precise source contracts, not JVM/device tests. Controls never edit the checkout."""
from __future__ import annotations

import argparse
import re
from dataclasses import dataclass
from pathlib import Path

BASE = "app/src/main/java/com/aharou/"
AGENT = BASE + "feature/agent/"
DOMAIN = AGENT + "domain/"
UI = AGENT + "presentation/component/"
SETTINGS = BASE + "feature/settings/presentation/"


@dataclass(frozen=True)
class Rule:
    name: str
    path: str
    pattern: str
    good: str
    bad: str
    forbidden: bool = False
    scope: str = ""

    def accepts(self, text: str) -> bool:
        text = without_comments(text)
        if self.scope:
            text = function_body(text, self.scope)
            if not text:
                return False
        found = re.search(self.pattern, text, re.DOTALL) is not None
        return not found if self.forbidden else found


def without_comments(text: str) -> str:
    tokens = r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|/\*[\s\S]*?\*/|//[^\n]*'
    return re.sub(tokens, lambda match: re.sub(r"[^\n]", " ", match[0])
                  if match[0].startswith(("//", "/*")) else match[0], text)


def function_body(text: str, name: str) -> str:
    """Find a declaration body; quoted braces cannot change its lexical boundary."""
    match = re.search(r"\bfun\s+" + re.escape(name) + r"\s*\(", text)
    if match is None:
        return ""
    strings = r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\''
    masked = re.sub(strings, lambda item: " " * len(item[0]), text)
    # Skip parameter defaults/lambdas before selecting the implementation.
    depth = 1
    pos = match.end()
    while pos < len(masked) and depth:
        depth += (masked[pos] == "(") - (masked[pos] == ")")
        pos += 1
    start = masked.find("{", pos)
    if start < 0:
        return ""
    depth = 1
    pos = start + 1
    while pos < len(masked) and depth:
        depth += (masked[pos] == "{") - (masked[pos] == "}")
        pos += 1
    return text[start + 1:pos - 1] if depth == 0 else ""


def required(name: str, path: str, pattern: str, good: str, bad: str,
             scope: str = "") -> Rule:
    return Rule(name, path, pattern, good, bad, scope=scope)


def forbidden(name: str, path: str, pattern: str, good: str, bad: str,
              scope: str = "") -> Rule:
    return Rule(name, path, pattern, good, bad, forbidden=True, scope=scope)


def rules() -> tuple[Rule, ...]:
    repository = DOMAIN + "context/ContextLibraryRepository.kt"
    provider = DOMAIN + "prompt/SystemPromptProvider.kt"
    planner = DOMAIN + "context/ContextLibraryPlanner.kt"
    editor = SETTINGS + "component/ContextLibraryEditor.kt"
    result = [
        forbidden("task-tool-hard-cast", DOMAIN + "task/ProjectWorkflowRunner.kt",
                  r'\bas\s+(?:[\w.]+\.)?TaskTool\b',
                  'val tool = registry.getTool("task") as? TaskTool',
                  'val tool = registry.getTool("task") as TaskTool'),
        required("project-create-real-repository", AGENT + "presentation/ProjectTaskViewModel.kt",
                 r'\brepository\.createOwnedProject\s*\(',
                 'fun createOwnedProject() { repository.createOwnedProject(name, requirements) }',
                 'fun createOwnedProject() { registry.getTool("task") }', "createOwnedProject"),
        required("thinking-selector-visible", UI + "ChatInputBar.kt",
                 r'\bReasoningEffortSelector\s*\([^)]*\beffort\s*=\s*reasoningEffort\b[^)]*\bonChange\s*=\s*onReasoningEffortChange\b',
                 'ReasoningEffortSelector(effort = reasoningEffort, onChange = onReasoningEffortChange)',
                 'HiddenSelector(effort = reasoningEffort, onChange = onReasoningEffortChange)'),
        required("thinking-state-consumer", UI + "AIChatPanel.kt",
                 r'\breasoningEffort\s*=\s*reasoningEffort\s*,\s*onReasoningEffortChange\s*=\s*\{\s*viewModel\.setSessionReasoningEffort\(it\)',
                 'reasoningEffort = reasoningEffort, onReasoningEffortChange = { viewModel.setSessionReasoningEffort(it) }',
                 'reasoningEffort = reasoningEffort, onReasoningEffortChange = {}'),
        forbidden("project-no-stage-model-controls", UI + "ProjectChatMenu.kt",
                  r'\b(?:DualModelSheet|DualModelViewModel|DualModelEntryButton)\b|\bR\.string\.dual_model_\w+|\bdualModelOpen\b',
                  'StewardSheet(sessionId)', 'DualModelSheet(sessionId)'),
        required("sessions-share-project-screen", BASE + "MainActivity.kt",
                 r'composable\("projects"\)\s*\{\s*ProjectListScreen\s*\(.*?sessionsContent\s*=\s*\{.*?SessionListScreen\s*\(',
                 'composable("projects") { ProjectListScreen(viewModel, sessionsContent = { SessionListScreen(sessions) }) }',
                 'composable("projects") { ProjectListScreen(viewModel) }; composable("sessions") { SessionListScreen(sessions) }'),
        required("two-injection-categories", DOMAIN + "context/ContextEntry.kt",
                 r'\b(?:ROLE_INJECTED|INJECTED_ROLE)\b.*\b(?:MAIN_INJECTED|INJECTED_MAIN)\b|\b(?:MAIN_INJECTED|INJECTED_MAIN)\b.*\b(?:ROLE_INJECTED|INJECTED_ROLE)\b',
                 'enum class ContextCategory { ROLE_INJECTED, MAIN_INJECTED }',
                 'enum class ContextCategory { INJECTED }'),
        required("snapshot-role-input", repository,
                 r'\b(?:agentDefinition|isSubAgent|memberName)\b',
                 'fun withPromptSnapshot(ctx: AgentContext) { Snapshot(ctx.agentDefinition != null) }',
                 'fun withPromptSnapshot(ctx: AgentContext) { Snapshot(true) }', "withPromptSnapshot"),
        required("plan-consumes-injection-category", repository,
                 r'ContextLibraryPlanner\.plan\([^;]*\binjectionCategory\b|ContextLibraryPlanner\.plan\([^;]*active\?\.injectionCategory',
                 'fun plan() { ContextLibraryPlanner.plan(entries, injectionCategory = active?.injectionCategory) }',
                 'fun plan() { entries.map { entry -> entry.copy(enabled = memoryAllowed) } }', "plan"),
        forbidden("member-memory-not-whole-source-gated", provider,
                  r'val\s+memories\s*=\s*if\s*\(\s*InjectPart\.MEMORY\s*!in\s+definition\.inject\s*\)\s*null',
                  'fun buildForSubAgent() { val memories = memoryListSource.build(agentContext) }',
                  'fun buildForSubAgent() { val memories = if (InjectPart.MEMORY !in definition.inject) null else memoryListSource.build(agentContext) }',
                  "buildForSubAgent"),
        forbidden("member-append-not-memory-gated", provider,
                  r'if\s*\(\s*InjectPart\.MEMORY\s+in\s+definition\.inject\s*&&\s*MEMORY_VAR\s*!in\s+raw\s*\)',
                  'fun buildForSubAgent() { if (MEMORY_VAR !in raw) memories?.let { append(it) } }',
                  'fun buildForSubAgent() { if (InjectPart.MEMORY in definition.inject && MEMORY_VAR !in raw) memories?.let { append(it) } }',
                  "buildForSubAgent"),
        required("member-source-built", provider, r'\bmemoryListSource\.build\(agentContext',
                 'fun buildForSubAgent() { val memories = memoryListSource.build(agentContext) }',
                 'fun buildForSubAgent() { val memories = null }', "buildForSubAgent"),
        required("member-source-appended", provider, r'\bmemories\?\.let\s*\{\s*append\(',
                 'fun buildForSubAgent() { memories?.let { append(it) } }',
                 'fun buildForSubAgent() { renderVariables(raw) }', "buildForSubAgent"),
        required("snapshot-finally-restores", repository,
                 r'finally\s*\{\s*if\s*\(previous\s*==\s*null\)\s*snapshot\.remove\(\)\s*else\s*snapshot\.set\(previous\)',
                 'fun withPromptSnapshot() { try { block() } finally { if (previous == null) snapshot.remove() else snapshot.set(previous) } }',
                 'fun withPromptSnapshot() { block(); snapshot.remove() }', "withPromptSnapshot"),
        required("snapshot-workspace-session-boundary", repository,
                 r'it\.root\s*==\s*(?:root|normalizedRoot)\s*&&\s*it\.sessionId\s*==\s*sessionId',
                 'fun plan() { snapshot.get()?.takeIf { it.root == root && it.sessionId == sessionId } }',
                 'fun plan() { snapshot.get()?.takeIf { it.root == root } }', "plan"),
        required("expiry-retained", repository, r'm\.expiresAt\?\.let\s*\{\s*it\s*>\s*System\.currentTimeMillis\(\)',
                 'enabled = m.expiresAt?.let { it > System.currentTimeMillis() } ?: true', 'enabled = true'),
        required("save-version-retained", repository, r'expectedVersion\s*=\s*entry\.version',
                 'fun save() { memories.writeMemory(expectedVersion = entry.version) }',
                 'fun save() { memories.writeMemory(expectedVersion = null) }', "save"),
        required("save-workspace-retained", repository, r'entry\.workspaceRoot\s*!=\s*projectRoot',
                 'fun save() { if (entry.workspaceRoot != projectRoot) return false }',
                 'fun save() { return true }', "save"),
        required("planner-off-retained", planner, r'!entry\.enabled\s*\|\|\s*entry\.loading\s*==\s*ContextLoad\.OFF',
                 'if (!entry.enabled || entry.loading == ContextLoad.OFF) continue', 'if (!entry.enabled) continue'),
        required("planner-temporary-session-retained", planner,
                 r'ContextCategory\.TEMPORARY\s*&&\s*\(sessionId\.isNullOrBlank\(\)\s*\|\|\s*entry\.sessionId\s*!=\s*sessionId\)',
                 'entry.category == ContextCategory.TEMPORARY && (sessionId.isNullOrBlank() || entry.sessionId != sessionId)',
                 'entry.category == ContextCategory.TEMPORARY && sessionId.isNullOrBlank()'),
        required("editor-three-fields-persist", editor,
                 r'onSave\(draft\.name,\s*draft\.description,\s*draft\.content,',
                 'onSave(draft.name, draft.description, draft.content, scope)',
                 'onSave(draft.name, "", draft.content, scope)'),
        required("editor-draft-saver-retained", editor,
                 r'rememberSaveable\([^\n]*stateSaver\s*=\s*ContextEditorDraftSaver',
                 'rememberSaveable(editorKey, stateSaver = ContextEditorDraftSaver)', 'remember(editorKey)'),
        required("search-all-three-fields", SETTINGS + "ContextLibraryViewModel.kt",
                 r'listOf\(entry\.title,\s*entry\.description,\s*entry\.content',
                 'fun filterContextEntries() { listOf(entry.title, entry.description, entry.content) }',
                 'fun filterContextEntries() { listOf(entry.title, entry.description) }', "filterContextEntries"),
        required("search-branches-before-folder-filter", SETTINGS + "ContextLibraryNavigation.kt",
                 r'if\s*\(query\.isNotBlank\(\)\)\s*(?:\{\s*)?filterContextEntries\(\s*entries',
                 'if (query.isNotBlank()) filterContextEntries(entries, query) else contextFolderEntries(entries)',
                 'filterContextEntries(contextFolderEntries(state.entries), query)'),
    ]
    result.extend((
        required("legacy-injected-maps-main", DOMAIN + "context/ContextEntry.kt",
                 r'"INJECTED"\s*->\s*(?:ContextCategory\.)?MAIN_INJECTED',
                 'when (value) { "INJECTED" -> MAIN_INJECTED }',
                 'when (value) { "INJECTED" -> ROLE_INJECTED }'),
        required("planner-injection-before-budget", planner,
                 r'entry\.category[^;]*injectionCategory[^;]*continue[\s\S]*?fullChars\s*\+\s*block\.length',
                 'if (entry.category != injectionCategory) { continue }; if (fullChars + block.length < budget) emit()',
                 'if (fullChars + block.length < budget) emit(); if (entry.category != injectionCategory) { continue }'),
        required("planner-ordinary-memory-separate-gate", planner,
                 r'\bmemoryAllowed\b[^;]*\b(?:isInjection|ROLE_INJECTED|MAIN_INJECTED|injected|injectionCategories)\b|\b(?:isInjection|ROLE_INJECTED|MAIN_INJECTED|injected|injectionCategories)\b[^;]*\bmemoryAllowed\b',
                 'if (!memoryAllowed && !entry.category.isInjection) continue',
                 'if (!memoryAllowed) continue'),
        required("source-passes-ordinary-gate", provider,
                 r'contextLibrary\.render\([^;]*ordinaryMemoryEnabled\s*=',
                 'contextLibrary.render(root, ordinaryMemoryEnabled = allowOrdinary)',
                 'contextLibrary.render(root)'),
        required("source-block-switch-retained", provider,
                 r'\bInjectionBlock\.MEMORIES\b',
                 'val ordinaryAllowed = InjectionBlock.MEMORIES !in offBlocks',
                 'val ordinaryAllowed = true'),
        required("snapshot-records-category", repository,
                 r'if\s*\(roleSession\)\s*ContextCategory\.ROLE_INJECTED\s*else\s*ContextCategory\.MAIN_INJECTED',
                 'fun withPromptSnapshot() { Snapshot(if (roleSession) ContextCategory.ROLE_INJECTED else ContextCategory.MAIN_INJECTED) }',
                 'fun withPromptSnapshot() { Snapshot(ContextCategory.MAIN_INJECTED) }', "withPromptSnapshot"),
    ))
    result.append(required("default-child-identity-consumer", provider,
                           r'withPromptSnapshot\(agentContext,\s*roleSession\s*=\s*isSubAgentSession\(agentContext\)\)',
                           'fun build() = contextLibrary.withPromptSnapshot(agentContext, roleSession = isSubAgentSession(agentContext)) { render() }',
                           'fun build() = contextLibrary.withPromptSnapshot(agentContext) { render() }'))
    for constant, value in (("FULL_ENTRY_CHARS", 900), ("FULL_TOTAL_CHARS", 3000), ("INDEX_TOTAL_CHARS", 6000)):
        result.append(required("budget-" + constant.lower(), planner,
                               rf'const\s+val\s+{constant}\s*=\s*{value}\b',
                               f'const val {constant} = {value}', f'const val {constant} = {value + 1}'))
    for field in ("name", "description", "content"):
        result.append(required("editor-field-" + field, editor,
                               rf'AppTextField\(\s*value\s*=\s*draft\.{field}\b',
                               f'AppTextField(value = draft.{field}, onValueChange = {{}})',
                               f'Text(draft.{field})'))
    return tuple(result)


def controls(contracts: tuple[Rule, ...]) -> int:
    for rule in contracts:
        if not rule.accepts(rule.good):
            raise AssertionError(f"positive fixture rejected: {rule.name}")
        if rule.accepts(rule.bad):
            raise AssertionError(f"negative fixture accepted: {rule.name}")
        if not rule.forbidden and rule.accepts("/* " + rule.good + " */"):
            raise AssertionError(f"comment satisfied contract: {rule.name}")
    return len(contracts)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--controls-only", action="store_true")
    args = parser.parse_args()
    contracts = rules()
    count = controls(contracts)
    print(f"CONTROLS: {count}/{count} positive + negative fixture pairs passed (in memory)")
    if args.controls_only:
        return 0
    texts: dict[str, str] = {}
    failures = []
    for rule in contracts:
        if rule.path not in texts:
            try:
                texts[rule.path] = (args.root / rule.path).read_text(encoding="utf-8")
            except FileNotFoundError:
                texts[rule.path] = ""
        if not texts[rule.path] or not rule.accepts(texts[rule.path]):
            failures.append(f"FAIL {rule.name}: {rule.path}")
    for failure in failures:
        print(failure)
    print(f"SOURCE: {len(contracts) - len(failures)}/{len(contracts)} contracts passed")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
