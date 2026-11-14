"""Source wiring checks, not Android click tests or real language-server builds."""
import json
import shutil
import subprocess
import tempfile
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

    def test_running_projects_are_stopped_and_delete_uses_owned_workspace(self):
        vm = self.read("feature/agent/presentation/ProjectTaskViewModel.kt")
        ui = self.read("feature/agent/presentation/component/ProjectListScreen.kt")
        repo = self.read("feature/agent/data/task/ProjectTaskRepository.kt")
        self.assertIn("enabled = viewModel.canDeleteProject(project)", ui)
        self.assertIn("if (!canDeleteProject(project))", vm)
        self.assertIn("project.id !in mutable.value.planningProjectIds", vm)
        self.assertIn("flows.withProjectStopped(projectId, project.workspace)", vm)
        eligibility = vm[vm.index("fun canDeleteProject("):vm.index("fun deleteProject(")]
        self.assertNotIn("projectIsIdle", eligibility)
        self.assertNotIn("ProjectTaskStatus.RUNNING", eligibility)
        self.assertIn("repository.deleteProject(projectId, project.workspace)", vm)
        deletion = repo[repo.index("private fun deleteProjectLocked"):repo.index("suspend fun save(")]
        self.assertNotIn("ProjectTaskStatus.RUNNING", deletion)
        self.assertNotIn("deleteRecursively", repo)

    def test_delete_waits_for_owned_jobs(self):
        vm = self.read("feature/agent/presentation/ProjectTaskViewModel.kt")
        delete = vm[vm.index("fun deleteProject("):vm.index("fun createProject(")]
        self.assertLess(delete.index("jobs.forEach { it.cancel() }"), delete.index("flows.withProjectStopped("))
        self.assertLess(delete.index("jobs.joinAll()"), delete.index("repository.deleteProject("))
        self.assertNotIn("sessions.delete", delete)
        self.assertIn("it.first == project.workspace && it.second in taskIds", delete)
        self.assertIn("state.workspace == project.workspace", delete)
        controller = self.read("feature/agent/domain/task/ProjectFlowController.kt")
        stopped = controller[controller.index("suspend fun withProjectStopped("):controller.index("fun runProjectTask(")]
        self.assertLess(stopped.index("jobs.forEach { it.cancel() }"), stopped.index("jobs.joinAll()"))
        self.assertLess(stopped.index("jobs.joinAll()"), stopped.index("delete()"))
        self.assertIn("singleTaskProjects.filterValues { it == key }", stopped)

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

    def test_bootstrap_is_locked_and_before_network(self):
        installer = self.read("feature/editor/lsp/LanguageServerInstaller.kt")
        bundle = self.read("feature/editor/lsp/VendoredLanguageServers.kt")
        self.assertNotIn("ensureInstalled()", installer)
        install = bundle[bundle.index("suspend fun install("):]
        self.assertLess(install.index("installLock.withLock"), install.index("commands.ensureInstalled()"))
        self.assertLess(install.index("val profile ="), install.index("commands.ensureInstalled()"))
        self.assertLess(install.index("ExtensionRuntimeBootstrap.prepare"), install.index("extensionRequest(client"))
        self.assertIn("checkBackend()", install[install.index("ExtensionRuntimeBootstrap.prepare"):install.index("extensionRequest(client")])
        self.assertIn("node = stage / 'bin/node'", bundle)
        self.assertNotIn("check_output(['node'", bundle)
        self.assertNotIn('"python3 - ', bundle)

    def test_runtime_script_with_whitelisted_fake_commands(self):
        source = self.read("feature/editor/lsp/ExtensionRuntimeBootstrap.kt")
        script = source.split('internal val SCRIPT = """', 1)[1].split('""".trimIndent()', 1)[0].replace("${'$'}", "$")
        # No real package manager is reachable: PATH contains only these exact stubs
        # and grep; /bin/sh is the sole absolute executable in this fixed script.
        self.assertNotIn("sudo", script)
        self.assertNotIn("/usr/bin/", script)
        self.assertNotIn("/bin/", script)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stubs = {
                "uname": 'printf "%s\n" "$ARCH"',
                "cat": 'printf "ID=%s\nVERSION_ID=\"24.04\"\n" "$DISTRO"',
                "id": 'printf "%s\n" "$UID_VALUE"',
                "python3.12": 'test -f "$READY"',
                "dpkg-query": "printf 'install ok installed'",
                "apt-get": 'printf "%s\n" "$*" >> "$CALLS"; test "$APT_RC" = 0 || exit "$APT_RC"; case " $* " in *" install "*) : > "$READY" ;; esac',
            }
            for name, body in stubs.items():
                path = root / name
                path.write_text("#!/bin/sh\n" + body + "\n")
                path.chmod(0o755)
            (root / "grep").symlink_to(shutil.which("grep"))
            env = {"PATH": str(root), "ARCH": "aarch64", "DISTRO": "ubuntu", "UID_VALUE": "0", "APT_RC": "0", "READY": str(root / "ready"), "CALLS": str(root / "calls")}

            def run(**changes):
                result = subprocess.run(["/bin/sh", "-c", script], env=env | changes, capture_output=True, text=True, timeout=5)
                return result.returncode, result.stdout + result.stderr

            self.assertEqual(64, run(ARCH="x86_64")[0])
            self.assertEqual(65, run(DISTRO="debian")[0])
            self.assertEqual(77, run(UID_VALUE="1000")[0])
            self.assertFalse((root / "calls").exists())
            self.assertEqual(100, run(APT_RC="100")[0])
            self.assertFalse((root / "ready").exists())
            self.assertEqual((0, ""), run())
            calls = (root / "calls").read_text()
            self.assertIn("install -y --no-install-recommends python3.12 libstdc++6 libatomic1 ca-certificates", calls)
            self.assertNotIn("node", calls)
            self.assertEqual((0, ""), run(UID_VALUE="1000"))
            self.assertEqual(calls, (root / "calls").read_text())

    def test_node_artifacts_ship_private_runtime_and_require_clean_verification(self):
        builder = (ROOT / "scripts/build_language_extensions.py").read_text()
        workflow = (ROOT / ".github/workflows/language-extensions.yml").read_text()
        self.assertIn('exec "$here/bin/node"', builder)
        self.assertNotIn('runtime.append("node >=24")', builder)
        self.assertIn('entry.get("cleanUbuntuVerified") is not True', builder)
        self.assertIn("node-version: '24.22.2'", workflow)
        self.assertIn("ubuntu:24.04", workflow)
        self.assertIn("--verify-clean", workflow)
        self.assertIn("python3.12 libstdc++6 libatomic1 ca-certificates", workflow)
        self.assertLess(workflow.index("--verify-clean"), workflow.index("actions/upload-artifact"))

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
        translate = service[service.index("suspend fun translateRequired("):service.index("private fun cache(")]
        self.assertNotIn("prepareModel()", translate)
        self.assertNotIn("LocalLlmEngine", service)

    def test_each_asset_editor_has_one_translation_action(self):
        for name in ["SkillEditorScreen.kt", "SubAgentEditorScreen.kt", "ContextLibraryEditor.kt"]:
            ui = self.read("feature/settings/presentation/component/" + name)
            self.assertEqual(1, ui.count("TranslationPreview("), name)
        self.assertIn("TranslationPreview(project.requirements)", self.read("feature/agent/presentation/component/ProjectTaskWorkbench.kt"))


if __name__ == "__main__":
    unittest.main()
