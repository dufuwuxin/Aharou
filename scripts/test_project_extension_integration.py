"""Source wiring checks, not Android click tests or real language-server builds."""
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/aharou"


class IntegrationContracts(unittest.TestCase):
    def read(self, name):
        return (SRC / name).read_text()

    def test_project_delete_is_explicit_and_keeps_files(self):
        ui = self.read("feature/agent/presentation/component/ProjectListScreen.kt")
        self.assertIn('testTag("delete_project_${project.id}")', ui)
        self.assertIn("viewModel.deleteProject(project.id)", ui)
        self.assertIn("project_delete_description", ui)
        repo = self.read("feature/agent/data/task/ProjectTaskRepository.kt")
        delete = repo[repo.index("private fun deleteProjectLocked"):repo.index("suspend fun save(")]
        self.assertLess(delete.index("persist(workspace, next)"), delete.index("mutable.value ="))
        self.assertNotIn("deleteRecursively", delete)

    def test_running_projects_are_rejected_and_delete_uses_owned_workspace(self):
        vm = self.read("feature/agent/presentation/ProjectTaskViewModel.kt")
        ui = self.read("feature/agent/presentation/component/ProjectListScreen.kt")
        repo = self.read("feature/agent/data/task/ProjectTaskRepository.kt")
        self.assertIn("enabled = viewModel.canDeleteProject(project)", ui)
        self.assertIn("if (!canDeleteProject(project))", vm)
        self.assertIn("check(projectIsIdle(project))", vm)
        self.assertIn("repository.deleteProject(projectId, project.workspace)", vm)
        self.assertIn("it.projectId == projectId && it.status == ProjectTaskStatus.RUNNING", repo)
        self.assertNotIn("deleteRecursively", repo)

    def test_delete_waits_for_owned_jobs(self):
        vm = self.read("feature/agent/presentation/ProjectTaskViewModel.kt")
        delete = vm[vm.index("fun deleteProject("):vm.index("fun createProject(")]
        self.assertLess(delete.index("cancelAndJoin()"), delete.index("repository.deleteProject("))
        self.assertNotIn("sessions.delete", delete)
        self.assertIn("state.workspace == workspace", delete)

    def test_editor_has_one_tools_entry(self):
        tools = self.read("feature/settings/presentation/component/UnifiedToolsSection.kt")
        runner = self.read("feature/settings/presentation/component/ToolboxSection.kt")
        self.assertEqual(1, tools.count("R.string.editor_settings"))
        self.assertNotIn("R.string.editor_settings", runner)

    def test_seven_sources_match_the_app_directory(self):
        entries = json.loads((ROOT / "app/src/main/assets/language-extensions/catalog.json").read_text())["extensions"]
        self.assertEqual({"lua", "python", "typescript", "shell", "yaml", "vue", "rust"}, {e["id"] for e in entries})
        catalog = self.read("feature/editor/lsp/LspServerCatalog.kt")
        for entry in entries:
            self.assertIn('program = "${VendoredLanguageServers.ROOT}/bin/' + entry["command"] + '"', catalog)

    def test_install_consumer_uses_the_vendored_bundle(self):
        installer = self.read("feature/editor/lsp/LanguageServerInstaller.kt")
        public_install = installer[installer.index("suspend fun install("):installer.index("suspend fun cachedStatus(")]
        self.assertIn("vendored.install(extension)", public_install)
        self.assertNotIn("installCommand(extension)", public_install)
        self.assertIn("status is ExtensionStatus.Installed", public_install)
        bundle = self.read("feature/editor/lsp/VendoredLanguageServers.kt")
        for token in ["context.assets.open", "files.writeStream", "EXTENSION_HASH_MISMATCH", "downloadExtensionArtifact", "installLock.withLock", "manifestUrl"]:
            self.assertIn(token, bundle)
        self.assertNotIn("DownloadManager", bundle)

    def test_old_global_cache_does_not_claim_managed_installation(self):
        installer = self.read("feature/editor/lsp/LanguageServerInstaller.kt")
        self.assertIn("vendored.installedVersion(extension)", installer)
        self.assertNotIn("readStatusRoot", installer)
        bundle = self.read("feature/editor/lsp/VendoredLanguageServers.kt")
        self.assertIn('text(node, "origin") == "ci-artifact"', bundle)
        self.assertIn("catalog.sourceSetSha256", bundle)

    def test_project_header_is_compact_and_map_first(self):
        ui = self.read("feature/agent/presentation/component/ProjectTaskWorkbench.kt")
        self.assertIn('testTag("project_compact_header")', ui)
        self.assertIn('mutableStateOf("map")', ui)
        self.assertIn("DropdownMenuItem", ui)
        self.assertNotIn("SingleChoiceSegmentedButtonRow", ui)

    def test_explicit_translation_does_not_trigger_on_page_open(self):
        ui = self.read("core/translation/TranslationDisplay.kt")
        preview = ui[ui.index("internal fun TranslationPreview("):]
        self.assertIn("TextButton(onClick", preview)
        self.assertIn("if (requested && enabled", preview)
        self.assertNotIn("rememberTranslatedText(text)", preview)
        service = self.read("core/translation/OfflineTranslation.kt")
        translate = service[service.index("private suspend fun translateReady("):service.index("private fun cache(")]
        self.assertNotIn("prepareModel()", translate)
        self.assertNotIn("LocalLlmEngine", service)

    def test_each_asset_editor_has_one_translation_action(self):
        for name in ["SkillEditorScreen.kt", "SubAgentEditorScreen.kt", "PromptEditorScreen.kt"]:
            ui = self.read("feature/settings/presentation/component/" + name)
            self.assertEqual(1, ui.count("TranslationPreview("), name)
        self.assertIn("TranslationPreview(project.requirements)", self.read("feature/agent/presentation/component/ProjectTaskWorkbench.kt"))


if __name__ == "__main__":
    unittest.main()
