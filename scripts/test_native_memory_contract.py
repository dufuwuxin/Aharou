#!/usr/bin/env python3
"""Isolated positive/negative controls for the static native-memory checker."""

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from check_native_memory_contract import (
    AUXILIARY_PROMPTS,
    BUILTINS,
    GROUPS,
    MENU,
    PROJECTS,
    PROVIDER,
    REPOSITORY,
    RESOLVER,
    RUNNER,
    SESSIONS,
    SETTINGS,
    SPEC,
    TASK,
    TEAMS,
    initializers,
    masked,
    violations,
)

FIXTURES = {
    PROVIDER: '''class SystemPromptProvider {
        fun build(ctx: AgentContext): String = native(ctx)
        private fun native(ctx: AgentContext): String { return contextLibrary.render(ctx) }
        fun resolvePrompt(name: String): String = promptFileResolver.resolve(name)
    }''',
    RESOLVER: '''class PromptFileResolver {
        fun resolve(name: String): String = contextLibrary.resolvePrompt(name)
        fun resolveRaw(name: String): String = resolve(name)
    }''',
    REPOSITORY: '''class ContextLibraryRepository(private val builtins: BuiltinContextEntries) {
        fun list(root: String): List<ContextEntry> { return sources(root) }
        private fun sources(root: String): List<ContextEntry> {
            builtins.ensureSeeded(memories)
            return memories.list(root)
        }
    }''',
    BUILTINS: '''class BuiltinContextEntries {
        fun ensureSeeded(memories: MemoryRepository) { memories.saveMemory(entry) }
    }''',
    SPEC: '''object TaskToolSpec {
        val parameters = mapOf("action" to ToolParameter(name = "action",
            enum = listOf("teams", "team_run")))
    }''',
    TASK: '''class TaskTool {
        fun executeWithContext(args: Args, context: AgentContext): ToolResult {
            return when (action) {
                "teams" -> teamTasks.list(args)
                "team_run" -> runTeam(args, context)
                else -> ToolResult.Error("bad action", "INVALID_ARGS")
            }
        }
    }''',
    TEAMS: '''class TeamTasks {
        fun list(args: Args): ToolResult { return TeamRunner.renderList(entries) }
    }''',
    RUNNER: '''object TeamRunner {
        fun renderList(entries: List<Entry>): String {
            return entries.joinToString { entry -> entry.team.name + entry.team.description }
        }
        fun renderDetail(entry: Entry): String { return entry.team.stages.toString() }
    }''',
    SESSIONS: '''class SessionTool {
        fun executeWithContext(args: Args, context: AgentContext): ToolResult {
                        return when (action) {
                "list" -> listSessions(workspace)
                "search" -> searchMessages(workspace)
                "read" -> readSession(workspace)
                "tool_calls", "context" -> inspectSession(workspace)
                else -> ToolResult.Error("bad action", "INVALID_ARGS")
            }
        }
        fun listSessions(workspace: String): ToolResult {
            return chatSessionDao.getAllSessionsByWorkspaceOnce(workspace)
        }
        fun searchMessages(workspace: String): ToolResult {
            return hits.filter { hit -> sessionUseCase.getSessionById(hit.sessionId)?.workspacePath == workspace }
        }
        fun readSession(workspace: String): ToolResult {
            val session = sessionUseCase.getSessionById(id)
            if (session.workspacePath != workspace) return ToolResult.Error("foreign target", "DENIED")
            return readMessages(id)
        }
        fun inspectSession(workspace: String): ToolResult {
            val session = sessionUseCase.getSessionById(id)
            if (session.workspacePath != workspace) return ToolResult.Error("foreign target", "DENIED")
            return inspect(id)
        }
    }''',
    SETTINGS: '''fun SettingsScreen() {
        val parent = when (section) { SettingsSection.Memory -> (configHome ?: SettingsSection.AiGroup) }
        when (section) { SettingsSection.Memory -> ContextLibrarySection() }
    }
    fun SettingsMenu() { SettingsRow(onClick = { open(SettingsSection.AiGroup) }) }
    ''',
    PROJECTS: '''fun ProjectListScreen() {
        Card(Modifier.combinedClickable(onClick = { open(project) },
            onLongClick = { projectMenu = project })) {
            DropdownMenu(expanded = projectMenu == project, onDismissRequest = { projectMenu = null }) {
                DropdownMenuItem(text = { Text(deleteLabel) }, onClick = { deletingProject = project })
            }
        }
        deletingProject?.let { project ->
            AlertDialog(confirmButton = {
                TextButton(onClick = { viewModel.deleteProject(project.id); deletingProject = null }) {}
            })
        }
    }''',
}


for path, entry in AUXILIARY_PROMPTS.items():
    FIXTURES[path] = f'''class Auxiliary {{
        private const val SYSTEM_PROMPT_KEY = "agent/auxiliary-system.md"
        fun {entry}(): String {{
            val systemPrompt = contextLibrary.resolvePromptForContext(SYSTEM_PROMPT_KEY,
                workspace, sessionId).trim()
            return complete(systemPrompt = systemPrompt)
        }}
    }}'''

class NativeMemoryContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="aharou-native-contract-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for path, text in FIXTURES.items():
            self.write(path, text)

    def write(self, path: Path, text: str) -> None:
        destination = self.root / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(text, encoding="utf-8")

    def mutate(self, path: Path, old: str, new: str, reason: str) -> None:
        original = FIXTURES[path]
        self.assertIn(old, original)
        self.write(path, original.replace(old, new, 1))
        errors = violations(self.root)
        self.assertTrue(any(reason in error for error in errors), errors)

    def test_nullable_native_resolution_and_checked_write_are_supported(self) -> None:
        self.write(RESOLVER, FIXTURES[RESOLVER].replace("contextLibrary.resolvePrompt", "contextLibrary?.resolvePrompt"))
        self.write(BUILTINS, FIXTURES[BUILTINS].replace("memories.saveMemory(entry)", "memories.writeMemory(entry)"))
        self.assertEqual([], violations(self.root))

    def test_unused_legacy_team_renderer_is_not_a_main_facing_path(self) -> None:
        self.write(TEAMS, '''class TeamTasks {
            fun list(args: Args): ToolResult { return entries.map { it.team.name + it.team.description } }
        }''')
        self.write(RUNNER, '''object TeamRunner {
            fun renderList(entries: List<Entry>): String { return entries.joinToString { it.team.members.toString() } }
        }''')
        self.assertEqual([], violations(self.root))

    def test_positive_fixture(self) -> None:
        self.assertEqual(violations(self.root), [])

    def test_comments_and_string_braces(self) -> None:
        self.write(PROVIDER, FIXTURES[PROVIDER] + '\n// definition.prompt\n/* renderStatic() */\nval note = "{ not a body }"')
        self.assertEqual(violations(self.root), [])
        self.assertEqual(masked('// render()\n/* role */').strip(), '')

    def test_missing_source_fails(self) -> None:
        (self.root / BUILTINS).unlink()
        self.assertTrue(any("missing required source" in error for error in violations(self.root)))

    def test_old_prompt_sources(self) -> None:
        for expression in ('promptFragmentCatalog.renderStatic(ctx)', 'definition.systemPrompt',
                           'definition.prompt', 'projectRuleSource.build(ctx)'):
            with self.subTest(expression=expression):
                self.mutate(PROVIDER, 'contextLibrary.render(ctx)', expression, 'old prompt/role source')

    def test_native_renderer_not_just_declared(self) -> None:
        self.mutate(PROVIDER, 'return contextLibrary.render(ctx)', 'return "contextLibrary.render(ctx)"',
                    'build must reach native library render')

    def test_resolver_old_fallback(self) -> None:
        for expression in ('context.assets.open("prompts/$name").readText()',
                           'File(root, "prompts.custom").readText()'):
            with self.subTest(expression=expression):
                self.mutate(RESOLVER, 'contextLibrary.resolvePrompt(name)', expression,
                            'legacy prompt directory/assets fallback')

    def test_auxiliary_hardcoded_prompts_are_rejected(self) -> None:
        native = 'contextLibrary.resolvePromptForContext(SYSTEM_PROMPT_KEY,\n                workspace, sessionId).trim()'
        for path in AUXILIARY_PROMPTS:
            for replacement in ('SYSTEM_PROMPT', '"hardcoded instructions"', '"""hardcoded\n{ instructions }"""'):
                with self.subTest(path=path, replacement=replacement):
                    self.mutate(path, native, replacement, 'must initialize systemPrompt from native memory')
            self.write(path, FIXTURES[path])

    def test_auxiliary_system_prompt_constants_are_rejected(self) -> None:
        for path in AUXILIARY_PROMPTS:
            for suffix in ('\nprivate const val SYSTEM_PROMPT = "old instructions"',
                           '\nval old = "${SYSTEM_PROMPT}"'):
                with self.subTest(path=path, suffix=suffix):
                    self.write(path, FIXTURES[path] + suffix)
                    self.assertTrue(any('hardcoded auxiliary SYSTEM_PROMPT' in error
                                        for error in violations(self.root)))
            self.write(path, FIXTURES[path])

    def test_auxiliary_fallbacks_are_rejected(self) -> None:
        for path in AUXILIARY_PROMPTS:
            for fallback in (' ?: File(root, "default.md").readText()',
                             '\n                ?: context.assets.open("fallback.md").readText()',
                             '\n                .ifBlank { "hardcoded instructions" }',
                             '.ifEmpty { fallback() }',
                             '.ifBlank({ "hardcoded instructions" })',
                             ' ?: "hardcoded instructions"'):
                with self.subTest(path=path, fallback=fallback):
                    self.mutate(path, 'workspace, sessionId).trim()', 'workspace, sessionId).trim()' + fallback,
                                'auxiliary system prompt must not use a fallback source')
            self.write(path, FIXTURES[path])

    def test_auxiliary_legacy_file_paths_are_rejected(self) -> None:
        for path in AUXILIARY_PROMPTS:
            for expression in ('File(root, "prompts.custom").readText()',
                               'context.assets.open("prompts/agent/default.md").readText()'):
                with self.subTest(path=path, expression=expression):
                    self.mutate(path, 'return complete(systemPrompt = systemPrompt)',
                                'val legacy = ' + expression + '\nreturn complete(systemPrompt = systemPrompt)',
                                'legacy auxiliary prompt directory/file fallback')
            self.write(path, FIXTURES[path])

    def test_auxiliary_native_call_cannot_be_a_decoy(self) -> None:
        for path in AUXILIARY_PROMPTS:
            with self.subTest(path=path):
                self.mutate(path, 'val systemPrompt = contextLibrary', 'val unusedPrompt = contextLibrary',
                            'must initialize systemPrompt from native memory')
            self.write(path, FIXTURES[path])

    def test_auxiliary_resources_remain_on_demand(self) -> None:
        for path in AUXILIARY_PROMPTS:
            self.write(path, FIXTURES[path].replace('return complete(systemPrompt = systemPrompt)', '''
                val rule = readRule()
                val state = File(root, "state.json").readText()
                val optional = readFileOrNull("optional-rule.md")
                return complete(systemPrompt = systemPrompt)
            ''') + '''
                fun readRule(): String = context.assets.open("rules/optional.md").readText()
                // SYSTEM_PROMPT = "prompts/custom.md"
                val note = "SYSTEM_PROMPT { is only an example }"
            ''')
        self.assertEqual([], violations(self.root))

    def test_each_auxiliary_source_is_required(self) -> None:
        for path in AUXILIARY_PROMPTS:
            with self.subTest(path=path):
                (self.root / path).unlink()
                self.assertIn(f'{path}: missing required source', violations(self.root))
            self.write(path, FIXTURES[path])

    def test_prompt_initializer_boundaries(self) -> None:
        for expression in ('"literal"', '"""multiline\n{ prompt }"""',
                           'native(key,\n session).trim()\n .ifEmpty { fallback() }',
                           'native(key)\n ?: fallback()'):
            with self.subTest(expression=expression):
                source = 'val systemPrompt: String = ' + expression + '\nval unrelated = readText()'
                self.assertEqual([expression], initializers(source, 'systemPrompt'))
        self.assertEqual(['native(key)'], initializers('val systemPrompt = native(key); readText()', 'systemPrompt'))

    def test_seed_must_be_reachable_from_list(self) -> None:
        self.mutate(REPOSITORY, 'return sources(root)', 'return memories.list(root)',
                    'list must reach BuiltinContextEntries seeding')

    def test_seed_must_persist(self) -> None:
        self.mutate(BUILTINS, 'memories.saveMemory(entry)', 'render(entry)',
                    'persist entries through MemoryRepository')

    def test_action_schema_exact(self) -> None:
        self.mutate(SPEC, 'listOf("teams", "team_run")', 'listOf("teams", "team_run", "create")',
                    'action enum must contain exactly')

    def test_each_old_dispatch_is_rejected(self) -> None:
        for action in ('create', 'read', 'send', 'roles', 'pipeline', 'grant', 'list', 'team', 'tell', 'stop', 'del'):
            with self.subTest(action=action):
                self.mutate(TASK, '"teams" ->', f'"{action}" -> oldAction(args)\n "teams" ->',
                            'old task action remains reachable')

    def test_team_detail_leak(self) -> None:
        self.mutate(TEAMS, 'TeamRunner.renderList(entries)', 'TeamRunner.renderDetail(entry)',
                    'team listing exposes execution details')

    def test_team_stage_leak(self) -> None:
        self.mutate(RUNNER, 'entry.team.name + entry.team.description',
                    'entry.team.name + entry.team.description + entry.team.stages.toString()',
                    'team listing exposes execution details')

    def test_team_string_template_leak(self) -> None:
        self.mutate(RUNNER, 'entry.team.name + entry.team.description',
                    '"${entry.team.name}: ${entry.team.description}: ${entry.team.stages}"',
                    'team listing exposes execution details')

    def test_group_menu_delegation(self) -> None:
        self.write(SETTINGS, FIXTURES[SETTINGS].replace('open(SettingsSection.AiGroup)', 'SettingsAharouRows(onOpen)'))
        self.write(GROUPS, '''fun SettingsAharouRows(onOpen: Open) { onOpen(SettingsSection.Memory) }
            fun ConfigGroup(onOpen: Open) { onOpen(SettingsSection.Memory) }''')
        errors = violations(self.root)
        self.assertTrue(any('top-level menu must not duplicate' in error for error in errors), errors)
        self.assertTrue(any('expected one configuration memory entry' in error for error in errors), errors)

    def test_session_target_workspace_guards_not_only_caller(self) -> None:
        self.mutate(SESSIONS, 'if (session.workspacePath != workspace) return ToolResult.Error("foreign target", "DENIED")',
                    'if (context.agentDefinition != null) return ToolResult.Error("caller only", "DENIED")',
                    'readSession must reject cross-workspace targets')

    def test_session_search_filters_foreign_targets(self) -> None:
        self.mutate(SESSIONS, 'sessionUseCase.getSessionById(hit.sessionId)?.workspacePath == workspace',
                    'true', 'search must verify target workspace')

    def test_session_inspection_filters_foreign_targets(self) -> None:
        text = FIXTURES[SESSIONS]
        needle = 'if (session.workspacePath != workspace) return ToolResult.Error("foreign target", "DENIED")'
        prefix, tail = text.rsplit(needle, 1)
        self.write(SESSIONS, prefix + tail)
        self.assertTrue(any('inspectSession must reject cross-workspace targets' in e for e in violations(self.root)))

    def test_duplicate_settings_entry(self) -> None:
        self.mutate(SETTINGS, 'open(SettingsSection.AiGroup)', 'open(SettingsSection.Memory)',
                    'top-level menu must not duplicate')

    def test_extracted_settings_menu(self) -> None:
        self.write(SETTINGS, FIXTURES[SETTINGS].split('    fun SettingsMenu')[0])
        self.write(MENU, 'fun SettingsMenu() { open(SettingsSection.AiGroup) }')
        self.assertEqual(violations(self.root), [])
        self.write(MENU, 'fun SettingsMenu() { open(SettingsSection.Memory) }')
        self.assertTrue(any('top-level menu must not duplicate' in e for e in violations(self.root)))

    def test_project_long_press_connected_to_menu(self) -> None:
        self.mutate(PROJECTS, 'expanded = projectMenu == project', 'expanded = unrelatedFlag',
                    'long-press state must control')

    def test_project_visible_delete_removed(self) -> None:
        self.mutate(PROJECTS, 'Card(Modifier', 'TextButton(onClick = { deletingProject = project }) {}\n Card(Modifier',
                    'always-visible project delete button')

    def test_confirmation_preserved(self) -> None:
        self.mutate(PROJECTS, 'viewModel.deleteProject(project.id)', 'dismiss()',
                    'existing deletion confirmation must remain')

    def test_cli_exit_status(self) -> None:
        command = [sys.executable, str(Path(__file__).with_name('check_native_memory_contract.py')),
                   '--root', str(self.root)]
        good = subprocess.run(command, capture_output=True, text=True, check=False)
        self.assertEqual(good.returncode, 0, good.stdout + good.stderr)
        self.assertIn('not runtime verification', good.stdout)
        self.write(SPEC, '// enum = listOf("teams", "team_run")')
        bad = subprocess.run(command, capture_output=True, text=True, check=False)
        self.assertEqual(bad.returncode, 1, bad.stdout + bad.stderr)
        self.assertIn('FAIL', bad.stdout)


if __name__ == '__main__':
    unittest.main(verbosity=2)
