package com.aharou.feature.agent.domain.skill

import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import com.aharou.feature.workspace.domain.FileAccessProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillTypeRepositoryTest {
    @get:org.junit.Rule val temporary = org.junit.rules.TemporaryFolder()

    private fun repositoryFixture(types: SkillTypeRepository, text: String): Pair<SkillRepository, java.io.File> {
        val root = temporary.newFolder()
        val dir = java.io.File(root, "editable").apply { mkdirs() }
        java.io.File(dir, "SKILL.md").writeText(text)
        java.io.File(dir, "scripts").mkdirs()
        java.io.File(dir, "scripts/run.py").writeText("print('keep')")
        val access = object : FileAccessProvider by com.aharou.testutil.TestFileAccessProvider() {
            override fun checked(): FileAccessProvider = this
            private fun isCatalog(path: String) = path.endsWith("/skill-types.json")
            override fun isFile(path: String): Boolean = if (isCatalog(path)) true else java.io.File(path).isFile
            override fun readFile(path: String): String = if (isCatalog(path)) {
                com.google.gson.Gson().toJson(types.list(if (path.startsWith("~/.aharou/")) SkillScope.GLOBAL else SkillScope.PROJECT))
            } else java.io.File(path).readText()
            private val mapper = mockk<com.aharou.feature.workspace.domain.WorkspacePathMapper>().apply {
                every { toHostFile(any(), any()) } answers { java.io.File(firstArg<String>()) }
            }
            private val local = com.aharou.feature.workspace.domain.LocalFileAccess(mapper)
            override fun requireResourcePath(root: String, relative: String) = local.requireResourcePath(root, relative)
        }
        val projectRoot = temporary.newFolder()
        val global = mockk<GlobalDirectorySkillSource>().apply {
            every { skillsRoot } returns root.path
            every { listSkills() } answers { root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { SkillParser.parse(access, it.path, "zh") } }
            every { loadInstructions(any()) } answers { SkillParser.parse(access, java.io.File(root, firstArg<String>()).path, "zh")?.instructions }
        }
        val project = mockk<ProjectDirectorySkillSource>().apply {
            every { skillsRoot } returns projectRoot.path
            every { listSkills() } answers { projectRoot.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { SkillParser.parse(access, it.path, "zh") } }
            every { loadInstructions(any()) } answers { SkillParser.parse(access, java.io.File(projectRoot, firstArg<String>()).path, "zh")?.instructions }
        }
        val builtin = mockk<BuiltinSkillSource>().apply { every { listSkills() } returns emptyList() }
        val config = mockk<SkillConfigRepository>().apply { every { disabledNames() } returns emptySet() }
        val agents = mockk<com.aharou.feature.agent.domain.subagent.AgentDefinitionRepository>().apply {
            every { listAll() } returns emptyList()
        }
        return SkillRepository(builtin, global, project, config, access, agents, types) to dir
    }

    private class Fixture {
        val files = mutableMapOf<String, String>()
        var workspace = "A"
        private fun key(path: String): String = if (path.startsWith("~/workspace/")) "$workspace:$path" else path
        val provider = mockk<FileAccessProvider>().apply {
            every { checked() } returns this
            every { isFile(any()) } answers { files.containsKey(key(firstArg())) }
            every { readFile(any()) } answers { files.getValue(key(firstArg())) }
            every { delete(any()) } answers { files.remove(key(firstArg())); Unit }
            every { writeFile(any(), any(), any(), any()) } answers {
                files[key(firstArg())] = secondArg()
            }
        }
        val repository = SkillTypeRepository(provider)
    }

    private fun type(name: String) = SkillType("common", name, "通用语义: \"引用\"\n第二行", "Reusable functions", listOf("tag: #one", "slash \\"))

    @Test
    fun save_survivesRepositoryRecreationAndPreservesText() {
        val f = Fixture()
        val original = type("General")
        f.repository.save(original, SkillScope.GLOBAL)
        val reloaded = SkillTypeRepository(f.provider)
        assertEquals(original, reloaded.get("common", SkillScope.GLOBAL))
        assertEquals(listOf(original), reloaded.list(SkillScope.GLOBAL))
    }

    @Test
    fun scope_projectWinsAndNeverLeaksIntoGlobalOrAnotherWorkspace() {
        val f = Fixture()
        f.repository.save(type("Global"), SkillScope.GLOBAL)
        f.repository.save(type("Project A"), SkillScope.PROJECT)
        f.repository.save(SkillType("private", "A only", "private semantics", ""), SkillScope.PROJECT)
        assertEquals("Project A", f.repository.get("common", SkillScope.PROJECT)?.name)
        assertEquals("Global", f.repository.get("common", SkillScope.GLOBAL)?.name)
        assertNull(f.repository.get("private", SkillScope.GLOBAL))
        assertNull(f.repository.get("private", SkillScope.BUILTIN))
        f.workspace = "B"
        assertEquals("Global", f.repository.get("common", SkillScope.PROJECT)?.name)
        assertNull(f.repository.get("private", SkillScope.PROJECT))
        assertTrue(f.repository.listLocal(SkillScope.PROJECT).isEmpty())
        f.workspace = "A"
        assertEquals("Project A", f.repository.get("common", SkillScope.PROJECT)?.name)
    }

    @Test
    fun missingAndInvalidTypesDoNotProduceGuidanceOrOverwriteCorruption() {
        val f = Fixture()
        assertNull(f.repository.get("missing", SkillScope.GLOBAL))
        f.repository.save(type("General"), SkillScope.GLOBAL)
        val path = f.files.keys.single()
        f.files[path] = "not valid JSON"
        assertTrue(f.repository.list(SkillScope.GLOBAL).isEmpty())
        val failed = runCatching { f.repository.save(type("Changed"), SkillScope.GLOBAL) }
        assertTrue(failed.isFailure)
        assertEquals("not valid JSON", f.files[path])
    }

    @Test
    fun blankGuidanceInvalidIdsAndBuiltinMutationAreRejected() {
        val f = Fixture()
        assertTrue(runCatching { f.repository.save(type("name").copy(id = "../escape"), SkillScope.GLOBAL) }.isFailure)
        assertTrue(runCatching { f.repository.save(type("name").copy(semantics = "", functions = ""), SkillScope.GLOBAL) }.isFailure)
        assertTrue(runCatching { f.repository.save(type("name"), SkillScope.BUILTIN) }.isFailure)
        assertTrue(f.files.isEmpty())
    }

    @Test
    fun failedWriteRestoresTheOriginalConfiguration() {
        val f = Fixture()
        f.repository.save(type("Original"), SkillScope.GLOBAL)
        val before = f.files.toMap()
        var fail = true
        every { f.provider.writeFile(any(), any(), any(), any()) } answers {
            if (fail) {
                fail = false
                f.files[firstArg()] = "partial"
                error("write interrupted")
            }
            f.files[firstArg()] = secondArg()
        }
        assertTrue(runCatching { f.repository.save(type("Changed"), SkillScope.GLOBAL) }.isFailure)
        assertEquals(before, f.files)
    }

    @Test
    fun cancellationIsNotDowngradedToAnEmptyCatalog() {
        val f = Fixture()
        every { f.provider.isFile(any()) } throws CancellationException("cancelled")
        val result = runCatching { f.repository.list(SkillScope.GLOBAL) }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertFalse(result.isSuccess)
    }
    @Test
    fun firstWriteFailureRemovesOnlyTheNewPartialCatalog() {
        val f = Fixture()
        every { f.provider.writeFile(any(), any(), any(), any()) } answers {
            f.files[firstArg()] = "partial"
            error("write interrupted")
        }
        assertTrue(runCatching { f.repository.save(type("New"), SkillScope.GLOBAL) }.isFailure)
        assertTrue(f.files.isEmpty())
    }

    @Test
    fun projectSaveRejectsAnEditorFromThePreviousWorkspace() {
        val f = Fixture()
        val workspace = mockk<WorkspaceRepository>()
        every { workspace.currentPath() } returns "B"
        val repo = SkillTypeRepository(f.provider, workspace)
        assertTrue(runCatching { repo.save(type("A draft"), SkillScope.PROJECT, "A") }.isFailure)
        assertTrue(f.files.isEmpty())
    }


    @Test fun twoSelectionsSaveReopenLoadAndArchiveWithoutLosingToolsOrResources() {
        val f = Fixture()
        f.repository.save(SkillType("report", "Report", "shared", "report functions"), SkillScope.GLOBAL)
        f.repository.save(SkillType("code", "Code", "shared", "code functions"), SkillScope.GLOBAL)
        val (repo, dir) = repositoryFixture(f.repository,
            "---\nname: editable\ntype_id: report\ntags: [custom]\nrequired_tools: [Bash, readFile]\ncustom_field: preserve\n---\nold")
        val before = repo.listAllSkills().single().skill
        val selected = com.aharou.feature.settings.presentation.toggleSkillType(before.selectedTypeIds, "code")
        assertNull(repo.save(SkillForm(before.name, before.description, "specialty", before.requiredTools,
            tags = before.tags, typeId = selected.firstOrNull(), typeIds = selected), SkillScope.GLOBAL, before.name))
        val reopened = repo.listAllSkills().single().skill
        assertEquals(listOf("report", "code"), reopened.selectedTypeIds)
        assertEquals("report", reopened.typeId)
        assertEquals(listOf("custom"), reopened.tags)
        assertEquals(listOf("Bash", "readFile"), reopened.requiredTools)
        val loaded = repo.loadInstructionsForAgent("editable", null, "", true)!!
        assertTrue(loaded.startsWith("shared\n\nreport functions\n\ncode functions\n\nspecialty"))
        assertEquals(1, Regex("shared").findAll(loaded).count())
        assertTrue(loaded.contains("Required tools: Bash, readFile"))
        assertTrue(loaded.contains("scripts/run.py"))
        assertEquals("print('keep')", java.io.File(dir, "scripts/run.py").readText())
        assertTrue(java.io.File(dir, "SKILL.md").readText().contains("custom_field: preserve"))
        val zip = repo.exportZip("editable", SkillScope.GLOBAL)!!
        val target = temporary.newFolder()
        val report = SkillImporter.importArchive(com.aharou.testutil.TestFileAccessProvider(), target.path,
            emptySet(), zip.inputStream(), "restored")
        assertEquals(listOf("editable"), report.imported)
        val restored = SkillParser.parse(com.aharou.testutil.TestFileAccessProvider(), java.io.File(target, "editable").path, "zh")!!
        assertEquals(reopened.selectedTypeIds, restored.selectedTypeIds)
        assertEquals(reopened.requiredTools, restored.requiredTools)
        assertEquals("print('keep')", java.io.File(target, "editable/scripts/run.py").readText())
    }

    @Test fun productionImportMoveAndConfigWritersPreserveAllSelectedTypes() {
        val f = Fixture()
        f.repository.save(SkillType("report", "Report", "shared", "report functions"), SkillScope.GLOBAL)
        f.repository.save(SkillType("code", "Code", "shared", "code functions"), SkillScope.GLOBAL)
        val (repo, dir) = repositoryFixture(f.repository, "---\nname: editable\ntype_ids: [report, code]\nrequired_tools: [Bash]\n---\nbody")
        val zip = repo.exportZip("editable", SkillScope.GLOBAL)!!
        assertEquals(SkillMoveResult.OK, repo.moveSkill("editable", SkillScope.GLOBAL, SkillScope.PROJECT))
        val moved = repo.listAllSkills().single()
        assertEquals(SkillScope.PROJECT, moved.scope)
        assertEquals(listOf("report", "code"), moved.skill.selectedTypeIds)
        assertTrue(repo.loadInstructionsForAgent("editable", null, "", true)!!.contains("code functions"))
        val restored = repo.importZip(zip.inputStream(), "restored", SkillScope.GLOBAL)
        assertNull(restored.fatal)
        assertEquals(listOf("editable"), restored.imported)
        val parsed = SkillParser.parse(com.aharou.testutil.TestFileAccessProvider(), dir.path, "zh")!!
        assertEquals(listOf("report", "code"), parsed.selectedTypeIds)
        val config = mockk<SkillConfigRepository>()
        val registry = com.aharou.core.config.ConfigRegistry.isolated()
        com.aharou.feature.agent.data.ConfigSkillFields(repo, config, f.repository).registerInto(registry)
        registry.resolveField("skill.skills.editable.type_ids")!!.write(com.aharou.core.config.ConfigValue.Arr(listOf("code", "report").map { com.aharou.core.config.ConfigValue.Str(it) }))
        registry.resolveField("skill.skills.editable.instructions")!!.write(com.aharou.core.config.ConfigValue.Str("updated body"))
        assertEquals(listOf("code", "report"), repo.listAllSkills().single().skill.selectedTypeIds)
        assertTrue(repo.loadInstructionsForAgent("editable", null, "", true)!!.contains("updated body"))
        registry.resolveField("skill.skills.editable.type_id")!!.write(com.aharou.core.config.ConfigValue.Str("report"))
        assertEquals(listOf("report"), repo.listAllSkills().single().skill.selectedTypeIds)
    }

    @Test fun missingSecondaryTypeRejectsSaveImportAndMoveWithoutTouchingSource() {
        val f = Fixture()
        f.repository.save(SkillType("report", "Report", "shared", ""), SkillScope.GLOBAL)
        val (repo, dir) = repositoryFixture(f.repository,
            "---\nname: editable\ntype_ids: [report, private]\n---\nbody")
        val before = java.io.File(dir, "SKILL.md").readText()
        assertNull(repo.loadInstructions("editable"))
        assertEquals(SkillSaveError.UNKNOWN_TYPE, repo.save(SkillForm("editable", "", "new",
            typeIds = listOf("report", "private")), SkillScope.GLOBAL, "editable"))
        assertEquals(SkillMoveResult.FAILED, repo.moveSkill("editable", SkillScope.GLOBAL, SkillScope.PROJECT))
        every { f.provider.exists(any()) } returns false
        every { f.provider.requireResourcePath(any(), any()) } returns Unit
        val catalogBefore = f.files.toMap()
        val imported = repo.importMarkdown(before, "editable", SkillScope.GLOBAL, boundAccess = f.provider)
        assertEquals(SkillImportError.IO_FAILED, imported.fatal)
        assertTrue(imported.imported.isEmpty())
        assertEquals(catalogBefore, f.files)
        assertEquals(before, java.io.File(dir, "SKILL.md").readText())
        assertTrue(java.io.File(dir, "scripts/run.py").exists())
    }
}
