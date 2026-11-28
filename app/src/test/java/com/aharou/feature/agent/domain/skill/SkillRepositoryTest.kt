package com.aharou.feature.agent.domain.skill

import com.aharou.testutil.TestFileAccessProvider
import com.aharou.feature.settings.presentation.matchesSkillQuery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SkillRepositoryTest {

    private val provider = object : com.aharou.feature.workspace.domain.FileAccessProvider by TestFileAccessProvider() {
        override fun checked(): com.aharou.feature.workspace.domain.FileAccessProvider = this
        private val mapper = mockk<com.aharou.feature.workspace.domain.WorkspacePathMapper>().apply {
            every { toHostFile(any(), any()) } answers { File(firstArg<String>()) }
        }
        private val local = com.aharou.feature.workspace.domain.LocalFileAccess(mapper)
        override fun requireResourcePath(root: String, relative: String) = local.requireResourcePath(root, relative)
    }
    private val language = mockk<AppLanguageProvider>().apply {
        every { code } returns "zh"
        every { promptCode } returns "zh"
    }
    private val globalSource = spyk(GlobalDirectorySkillSource(language, provider)).apply {
        every { listSkills() } returns emptyList()
    }
    private val projectSource = spyk(ProjectDirectorySkillSource(language, provider)).apply {
        every { listSkills() } returns emptyList()
    }
    private val config = mockk<SkillConfigRepository>().apply {
        every { disabledNames() } returns emptySet()
    }
    private val builtinSource = mockk<BuiltinSkillSource>().apply {
        every { listSkills() } returns emptyList()
        every { loadInstructions(any()) } returns null
    }
    private val agents = mockk<com.aharou.feature.agent.domain.subagent.AgentDefinitionRepository>().apply {
        every { listAll() } returns emptyList()
    }
    private val repository = SkillRepository(builtinSource, globalSource, projectSource, config, provider, agents)

    private fun skill(name: String) = Skill(
        name = name,
        description = "desc of $name",
        instructions = "body of $name"
    )

    @Test
    fun skillFingerprintCoversInstructionsAndAdditionalUserFiles() {
        val root = java.nio.file.Files.createTempDirectory("skill-fingerprint").toFile()
        try {
            val dir = File(root, "sample").apply { mkdirs() }
            val instructions = File(dir, "SKILL.md").apply {
                writeText(listOf("---", "name: sample", "---", "original").joinToString("\n"))
            }
            val resource = File(dir, "resources/data.json").apply { parentFile!!.mkdirs(); writeText("value=1") }
            every { globalSource.listSkills() } answers {
                listOf(SkillParser.parseText(instructions.readText(), "sample").copy(dirPath = dir.path))
            }
            val before = repository.skillFingerprint("sample", SkillScope.GLOBAL)
            assertNotNull(before)
            assertEquals(before, repository.skillFingerprint("sample", SkillScope.GLOBAL))

            resource.writeText("value=2")
            assertFalse(before == repository.skillFingerprint("sample", SkillScope.GLOBAL))
            instructions.writeText(listOf("---", "name: sample", "---", "user edited").joinToString("\n"))
            assertFalse(repository.skillFingerprint("sample", SkillScope.GLOBAL) == before)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun skillDraftSaveUsesChosenGlobalOrProjectRootAndRejectsBuiltinCollision() {
        val globalRoot = java.nio.file.Files.createTempDirectory("skill-draft-global").toFile()
        val projectRoot = java.nio.file.Files.createTempDirectory("skill-draft-project").toFile()
        try {
            every { globalSource.skillsRoot } returns globalRoot.path
            every { projectSource.skillsRoot } returns projectRoot.path
            every { globalSource.listSkills() } answers {
                globalRoot.walkTopDown().filter { it.isFile && it.name.equals("SKILL.md", true) }.map { file ->
                    SkillParser.parseText(file.readText(), file.parentFile?.name.orEmpty()).copy(dirPath = file.parentFile?.path)
                }.toList()
            }
            every { projectSource.listSkills() } answers {
                projectRoot.walkTopDown().filter { it.isFile && it.name.equals("SKILL.md", true) }.map { file ->
                    SkillParser.parseText(file.readText(), file.parentFile?.name.orEmpty()).copy(dirPath = file.parentFile?.path)
                }.toList()
            }
            assertNull(repository.save(SkillForm("draft-global", "desc", "instructions"), SkillScope.GLOBAL))
            assertNull(repository.save(SkillForm("draft-project", "desc", "instructions"), SkillScope.PROJECT))
            assertTrue(File(globalRoot, "draft-global/SKILL.md").isFile)
            assertTrue(File(projectRoot, "draft-project/SKILL.md").isFile)
            assertEquals(SkillSaveError.NAME_CONFLICT,
                repository.save(SkillForm("draft-global", "replacement", "replacement"), SkillScope.GLOBAL))
            assertTrue(File(globalRoot, "draft-global/SKILL.md").readText().contains("instructions"))

            every { builtinSource.listSkills() } returns listOf(skill("builtin"))
            assertEquals(SkillSaveError.READ_ONLY,
                repository.save(SkillForm("builtin", "desc", "instructions"), SkillScope.GLOBAL))
            assertFalse(File(globalRoot, "builtin/SKILL.md").exists())
        } finally {
            globalRoot.deleteRecursively()
            projectRoot.deleteRecursively()
        }
    }

    @Test
    fun deletedBuiltinStaysDeletedAfterRepositoryReloadAndDoesNotHideUserOverride() {
        val access = spyk(provider)
        var tombstones: String? = null
        val path = "~/.aharou/skills.deleted-builtins.json"
        every { access.isFile(path) } answers { tombstones != null }
        every { access.readFile(path) } answers { tombstones!! }
        every { access.writeFile(path, any(), any(), any()) } answers { tombstones = secondArg() }
        every { builtinSource.listSkills() } returns listOf(skill("builtin"))
        every { builtinSource.loadInstructions("builtin") } returns "builtin body"
        every { globalSource.loadInstructions(any()) } returns null
        every { projectSource.loadInstructions(any()) } returns null
        val repo = SkillRepository(builtinSource, globalSource, projectSource, config, access, agents)
        assertTrue(repo.deleteSkill("BUILTIN", SkillScope.BUILTIN))
        val reloaded = SkillRepository(builtinSource, globalSource, projectSource, config, access, agents)
        assertTrue(reloaded.listAllSkills().isEmpty())
        assertNull(reloaded.loadInstructions("builtin"))
        every { globalSource.listSkills() } returns listOf(skill("builtin"))
        every { globalSource.loadInstructions("builtin") } returns "user body"
        assertEquals(SkillScope.GLOBAL, reloaded.listAllSkills().single().scope)
        assertEquals("user body", reloaded.loadInstructions("builtin"))
        verify(exactly = 0) { access.deleteRecursively(any()) }
    }

    @Test
    fun corruptedBuiltinDeletionRecordIsNotOverwritten() {
        val access = spyk(provider)
        val path = "~/.aharou/skills.deleted-builtins.json"
        every { access.isFile(path) } returns true
        every { access.readFile(path) } returns "invalid json"
        every { builtinSource.listSkills() } returns listOf(skill("builtin"))
        val repo = SkillRepository(builtinSource, globalSource, projectSource, config, access, agents)
        assertTrue(runCatching { repo.deleteSkill("builtin", SkillScope.BUILTIN) }.isFailure)
        verify(exactly = 0) { access.writeFile(any(), any(), any(), any()) }
    }

    @Test
    fun editorSave_switchesModesReloadsAndKeepsMetadata() {
        val temp = java.nio.file.Files.createTempDirectory("skill-editor-policy").toFile()
        try {
            val dir = File(temp, "editable").apply { mkdirs() }
            val file = File(dir, "SKILL.md").apply {
                writeText("---\nname: editable\ndescription_zh: translated\ncustom_key: keep\nrequired_tools: [Bash]\n---\nbody")
            }
            every { globalSource.listSkills() } answers {
                listOf(SkillParser.parseText(file.readText(), "editable").copy(dirPath = dir.path))
            }
            val form = SkillForm("editable", "desc", "new body", listOf("Bash"), true, listOf(" Kotlin ", "", "Kotlin"))
            assertNull(repository.save(form, SkillScope.GLOBAL, "editable"))
            val reloaded = repository.listAllSkills().single().skill
            assertTrue(reloaded.keywordEnabled)
            assertEquals(listOf("Kotlin"), reloaded.keywords)
            assertEquals(listOf("Bash"), reloaded.requiredTools)
            assertTrue(file.readText().contains("description_zh"))
            assertTrue(file.readText().contains("custom_key"))
            assertTrue(repository.listSkillsForAgent(null, "poetry").isEmpty())
            assertEquals(listOf("editable"), repository.listSkillsForAgent(null, "KOTLIN task").map { it.name })
            assertNull(repository.save(form.copy(keywordEnabled = false), SkillScope.GLOBAL, "editable"))
            assertTrue(!repository.listAllSkills().single().skill.keywordEnabled)
            assertEquals(listOf("editable"), repository.listSkillsForAgent(null, "poetry").map { it.name })
        } finally { temp.deleteRecursively() }
    }

    @Test
    fun editorSave_rejectsEmptyKeywordsAndBoundSkillWithoutUnbinding() {
        assertEquals(SkillSaveError.EMPTY_KEYWORDS, repository.save(
            SkillForm("conditional", "", "body", keywordEnabled = true, keywords = listOf(" ")), SkillScope.GLOBAL))
        val owner = com.aharou.feature.agent.domain.subagent.AgentDefinition("owner", "", skills = listOf("conditional"), prompt = "role")
        every { agents.listAll() } returns listOf(com.aharou.feature.agent.domain.subagent.AgentDefinitionEntry(
            owner, com.aharou.feature.agent.domain.subagent.AgentDefinitionScope.GLOBAL))
        assertEquals(SkillSaveError.BOUND_KEYWORD_POLICY, repository.save(
            SkillForm("conditional", "", "body", keywordEnabled = true, keywords = listOf("task")), SkillScope.GLOBAL))
        verify(exactly = 0) { agents.setSkillBinding(any(), any(), any(), any()) }
    }

    @Test
    fun editorSave_rollsBackPartialFileWrite() {
        val temp = java.nio.file.Files.createTempDirectory("skill-editor-rollback").toFile()
        try {
            val file = File(temp, "SKILL.md").apply { writeText("---\nname: editable\n---\noriginal") }
            every { globalSource.listSkills() } returns listOf(skill("editable").copy(dirPath = temp.path))
            val failing = spyk(provider)
            var first = true
            every { failing.writeFile(file.path, any(), any(), any()) } answers {
                if (first) {
                    first = false
                    file.writeText("partial")
                    throw java.io.IOException("write failed")
                } else callOriginal()
            }
            val repo = SkillRepository(builtinSource, globalSource, projectSource, config, failing, agents)
            val original = file.readText()
            assertEquals(SkillSaveError.IO_FAILED, repo.save(SkillForm("editable", "", "new"), SkillScope.GLOBAL, "editable"))
            assertEquals(original, file.readText())
        } finally { temp.deleteRecursively() }
    }

    @Test
    fun boundSkillRename_isRejectedWithoutMakingItPublic() {
        val owner = com.aharou.feature.agent.domain.subagent.AgentDefinition("owner", "", skills = listOf("private"), prompt = "role")
        every { agents.listAll() } returns listOf(com.aharou.feature.agent.domain.subagent.AgentDefinitionEntry(
            owner, com.aharou.feature.agent.domain.subagent.AgentDefinitionScope.GLOBAL))
        every { globalSource.listSkills() } returns listOf(skill("private"))
        assertEquals(SkillSaveError.BOUND_RENAME, repository.save(SkillForm("renamed", "desc", "body"), SkillScope.GLOBAL, "private"))
        assertTrue(repository.listSkillsForAgent(null).isEmpty())
    }

    @Test
    fun commandDirectory_keepsConditionalPublicButHidesExclusiveSkills() {
        val owner = com.aharou.feature.agent.domain.subagent.AgentDefinition("owner", "", skills = listOf("private"), prompt = "role")
        every { agents.listAll() } returns listOf(com.aharou.feature.agent.domain.subagent.AgentDefinitionEntry(
            owner, com.aharou.feature.agent.domain.subagent.AgentDefinitionScope.GLOBAL))
        every { globalSource.listSkills() } returns listOf(skill("private"), skill("conditional").copy(keywordEnabled = true, keywords = listOf("Kotlin")))
        assertEquals(listOf("conditional"), repository.listCommandSkills().map { it.name })
        assertTrue(repository.listSkills().isEmpty())
    }

    @Test
    fun loadSkillTool_enforcesOwnershipAndIgnoresHistoryAndToolBodies() = kotlinx.coroutines.runBlocking {
        val owner = com.aharou.feature.agent.domain.subagent.AgentDefinition(
            "owner", "", skills = listOf("private"), inject = emptySet(), prompt = "role")
        val entry = com.aharou.feature.agent.domain.subagent.AgentDefinitionEntry(
            owner, com.aharou.feature.agent.domain.subagent.AgentDefinitionScope.GLOBAL)
        every { agents.listAll() } returns listOf(entry)
        every { globalSource.listSkills() } returns listOf(skill("private"),
            skill("conditional").copy(keywordEnabled = true, keywords = listOf("Kotlin")))
        val tool = com.aharou.feature.agent.domain.tool.skill.LoadSkillTool(repository, mockk(relaxed = true))
        val args = mapOf("skill_name" to kotlinx.serialization.json.JsonPrimitive("private"))
        val main = com.aharou.feature.agent.domain.model.AgentContext(null, null, "/project", null)
        assertEquals("SKILL_NOT_ALLOWED", (tool.executeWithContext(args, main) as com.aharou.feature.agent.domain.tool.ToolResult.Error).code)
        assertTrue(tool.executeWithContext(args, main.copy(agentDefinition = owner)) is com.aharou.feature.agent.domain.tool.ToolResult.Success)
        assertEquals("SKILL_NOT_ALLOWED", (tool.executeWithContext(args, main.copy(agentDefinition = owner.copy(name = "other", skills = emptyList())))
            as com.aharou.feature.agent.domain.tool.ToolResult.Error).code)
        val history = listOf(
            com.aharou.feature.agent.domain.model.AgentMessage.UserMessage(content = "Kotlin"),
            com.aharou.feature.agent.domain.model.AgentMessage.ToolResultMessage(toolName = "readFile", result = "Kotlin"))
        val conditionalArgs = mapOf("skill_name" to kotlinx.serialization.json.JsonPrimitive("conditional"))
        assertEquals("SKILL_NOT_ALLOWED", (tool.executeWithContext(conditionalArgs, main.copy(history = history,
            currentUserText = "Write a poem")) as com.aharou.feature.agent.domain.tool.ToolResult.Error).code)
        assertTrue(tool.executeWithContext(conditionalArgs, main.copy(currentUserText = "Kotlin task")) is com.aharou.feature.agent.domain.tool.ToolResult.Success)
    }

    @Test
    fun bindingEntry_rejectsKeywordSkillEvenOutsideSkillDetailUi() {
        val temp = java.nio.file.Files.createTempDirectory("keyword-binding").toFile()
        try {
            val agentRoot = File(temp, "agents").apply { mkdirs() }
            val skillDir = File(temp, "skills/conditional").apply { mkdirs() }
            File(skillDir, "SKILL.md").writeText(SkillParser.serialize("conditional", "", emptyList(), "body", true, listOf("test")))
            val global = mockk<com.aharou.feature.agent.domain.subagent.GlobalDirectoryAgentSource>()
            val project = mockk<com.aharou.feature.agent.domain.subagent.ProjectDirectoryAgentSource>()
            every { global.agentsRoot } returns agentRoot.path
            every { project.agentsRoot } returns File(temp, "other/agents").path
            val member = com.aharou.feature.agent.domain.subagent.AgentDefinition("owner", "", prompt = "body", filePath = File(agentRoot, "owner.md").path)
            every { global.listDefinitions() } returns listOf(member)
            every { project.listDefinitions() } returns emptyList()
            val config = mockk<com.aharou.feature.agent.domain.subagent.AgentDefinitionConfigRepository>()
            val agentRepo = com.aharou.feature.agent.domain.subagent.AgentDefinitionRepository(global, project, config, provider)
            assertEquals(com.aharou.feature.agent.domain.subagent.AgentSaveError.IO_FAILED,
                agentRepo.setSkillBinding("owner", com.aharou.feature.agent.domain.subagent.AgentDefinitionScope.GLOBAL, "conditional", true))
            assertTrue(!File(member.filePath!!).exists())
        } finally { temp.deleteRecursively() }
    }

    @Test
    fun keywordPolicy_rejectsUnconfirmedBindingAndRollsBackFailedUnbind() {
        val temp = java.nio.file.Files.createTempDirectory("keyword-policy").toFile()
        try {
            val dir = File(temp, "conditional").apply { mkdirs() }
            val file = File(dir, "SKILL.md").apply { writeText("---\nname: conditional\n---\nbody") }
            val memberFile = File(temp, "member.md").apply { writeText("original member") }
            val definition = com.aharou.feature.agent.domain.subagent.AgentDefinition(
                name = "owner", description = "", skills = listOf("conditional"), prompt = "role", filePath = memberFile.path)
            val entry = com.aharou.feature.agent.domain.subagent.AgentDefinitionEntry(
                definition, com.aharou.feature.agent.domain.subagent.AgentDefinitionScope.GLOBAL)
            every { agents.listAll() } returns listOf(entry)
            every { globalSource.listSkills() } returns listOf(skill("conditional").copy(dirPath = dir.path))
            assertTrue(runCatching { repository.setKeywordPolicy("conditional", SkillScope.GLOBAL, true, listOf("test")) }.isFailure)
            assertEquals("original member", memberFile.readText())
            every { agents.setSkillBinding("owner", entry.scope, "conditional", false) } answers {
                memberFile.writeText("partial write")
                com.aharou.feature.agent.domain.subagent.AgentSaveError.IO_FAILED
            }
            val original = file.readText()
            assertTrue(runCatching { repository.setKeywordPolicy("conditional", SkillScope.GLOBAL, true, listOf("test"), true) }.isFailure)
            assertEquals(original, file.readText())
            assertEquals("original member", memberFile.readText())
        } finally { temp.deleteRecursively() }
    }

    @Test
    fun keywordPolicy_enablesAndDisablesWithoutRestoringBindings() {
        val temp = java.nio.file.Files.createTempDirectory("keyword-policy").toFile()
        try {
            val dir = File(temp, "conditional").apply { mkdirs() }
            val file = File(dir, "SKILL.md").apply { writeText("---\nname: conditional\ndescription_zh: 保留翻译\n---\nbody") }
            val memberFile = File(temp, "owner.md").apply { writeText("bound member") }
            var bound = true
            val owner = com.aharou.feature.agent.domain.subagent.AgentDefinition("owner", "", prompt = "role", filePath = memberFile.path)
            val scope = com.aharou.feature.agent.domain.subagent.AgentDefinitionScope.GLOBAL
            every { agents.listAll() } answers { listOf(com.aharou.feature.agent.domain.subagent.AgentDefinitionEntry(
                owner.copy(skills = if (bound) listOf("conditional") else emptyList()), scope)) }
            every { agents.setSkillBinding("owner", scope, "conditional", false) } answers {
                bound = false
                memberFile.writeText("unbound member")
                null
            }
            every { globalSource.listSkills() } answers { listOf(SkillParser.parseText(file.readText(), "conditional").copy(dirPath = dir.path)) }
            repository.setKeywordPolicy("conditional", SkillScope.GLOBAL, true, listOf(" test "), true)
            assertTrue(!bound)
            assertTrue(SkillParser.parseText(file.readText(), "conditional").keywordEnabled)
            assertEquals(listOf("test"), SkillParser.parseText(file.readText(), "conditional").keywords)
            assertTrue(file.readText().contains("description_zh"))
            repository.setKeywordPolicy("conditional", SkillScope.GLOBAL, false, listOf("test"))
            assertTrue(!SkillParser.parseText(file.readText(), "conditional").keywordEnabled)
            assertTrue(!bound)
            assertEquals("unbound member", memberFile.readText())
            verify(exactly = 1) { agents.setSkillBinding("owner", scope, "conditional", false) }
            verify(exactly = 0) { agents.setSkillBinding(any(), any(), any(), true) }
        } finally { temp.deleteRecursively() }
    }

    @Test
    fun exclusiveSkills_areHiddenFromMainAndOtherRoles() {
        val owner = mockk<com.aharou.feature.agent.domain.subagent.AgentDefinition>()
        val other = mockk<com.aharou.feature.agent.domain.subagent.AgentDefinition>()
        every { owner.name } returns "owner"
        every { owner.skills } returns listOf("PRIVATE")
        every { other.name } returns "other"
        every { other.skills } returns emptyList()
        val entry = mockk<com.aharou.feature.agent.domain.subagent.AgentDefinitionEntry>()
        every { entry.definition } returns owner
        every { agents.listAll() } returns listOf(entry)
        every { globalSource.listSkills() } returns listOf(skill("private"), skill("legacy"))
        assertEquals(listOf("legacy"), repository.listSkills().map { it.name })
        assertEquals(listOf("legacy"), repository.listSkillsForAgent(other).map { it.name })
        assertEquals(listOf("legacy", "private"), repository.listSkillsForAgent(owner).map { it.name })
        assertEquals(listOf("private"), repository.listSkillsForAgent(owner, includePublic = false).map { it.name })
    }

    @Test
    fun keywords_matchAnyCurrentTaskAndRecomputeOnTaskChange() {
        every { globalSource.listSkills() } returns listOf(
            skill("conditional").copy(keywordEnabled = true, keywords = listOf("Kotlin", "Android")),
            skill("legacy")
        )
        assertEquals(listOf("conditional", "legacy"), repository.listSkillsForAgent(null, "Fix ANDROID bug").map { it.name })
        assertEquals(listOf("legacy"), repository.listSkillsForAgent(null, "Write a poem").map { it.name })
        assertEquals(listOf("legacy"), repository.listSkillsForAgent(null, "").map { it.name })
    }

    @Test
    fun keywordMode_rejectsEmptyWordsAndUnconfirmedUnbinding() {
        assertTrue(!SkillRepository.validKeywordPolicy(true, listOf(" ")))
        assertTrue(SkillRepository.validKeywordPolicy(true, listOf(" Kotlin ")))
        assertTrue(!SkillRepository.canEnableKeywords(true, false))
        assertTrue(SkillRepository.canEnableKeywords(true, true))
    }

    @Test
    fun loadInstructions_projectOverridesSameNameWithDifferentBody() {
        val global = skill("same").copy(instructions = "global instructions")
        val project = skill("same").copy(instructions = "project instructions")
        every { globalSource.listSkills() } returns listOf(global)
        every { projectSource.listSkills() } returns listOf(project)

        val listed = repository.listSkills().single()
        assertEquals(project, listed)
        assertEquals("project instructions", repository.loadInstructions(listed.name))
        verify(exactly = 0) { globalSource.loadInstructions(any()) }
    }

    @Test
    fun loadInstructions_projectOverridesCaseInsensitiveName() {
        val global = skill("MixedCase").copy(instructions = "global instructions")
        val project = skill("mixedcase").copy(instructions = "project instructions")
        every { globalSource.listSkills() } returns listOf(global)
        every { projectSource.listSkills() } returns listOf(project)

        assertEquals(listOf(project), repository.listSkills())
        assertEquals("project instructions", repository.loadInstructions("MIXEDCASE"))
        verify(exactly = 0) { globalSource.loadInstructions(any()) }
    }

    @Test
    fun loadInstructions_fallsBackToGlobalWhenProjectHasNoMatch() {
        val global = skill("GlobalOnly").copy(instructions = "global instructions")
        every { globalSource.listSkills() } returns listOf(global)
        every { projectSource.listSkills() } returns listOf(skill("other"))

        assertEquals("global instructions", repository.loadInstructions("GLOBALONLY"))
        verify(exactly = 1) { projectSource.loadInstructions("GLOBALONLY") }
        verify(exactly = 1) { globalSource.loadInstructions("GLOBALONLY") }
    }

    @Test
    fun loadInstructions_returnsNullWhenNeitherSourceHasMatch() {
        every { globalSource.listSkills() } returns listOf(skill("global"))
        every { projectSource.listSkills() } returns listOf(skill("project"))

        assertNull(repository.loadInstructions("missing"))
        verify(exactly = 1) { projectSource.loadInstructions("missing") }
        verify(exactly = 1) { globalSource.loadInstructions("missing") }
    }

    @Test
    fun loadInstructions_disabledNameBlocksBothSourcesCaseInsensitively() {
        every { globalSource.listSkills() } returns listOf(skill("MixedCase"))
        every { projectSource.listSkills() } returns listOf(skill("mixedcase"))
        every { config.disabledNames() } returns setOf("mixedcase")

        assertTrue(repository.listSkills().isEmpty())
        assertNull(repository.loadInstructions("MIXEDCASE"))
        verify(exactly = 0) { projectSource.loadInstructions(any()) }
        verify(exactly = 0) { globalSource.loadInstructions(any()) }
    }

    @Test
    fun mergeAll_combinesGlobalAndProject() {
        val merged = SkillRepository.mergeAll(
            builtin = emptyList(),
            global = listOf(skill("a"), skill("b")),
            project = listOf(skill("c"))
        )

        assertEquals(listOf("a", "b", "c"), merged.map { it.skill.name })
        assertTrue(merged.all { it.scope == SkillScope.GLOBAL || it.scope == SkillScope.PROJECT })
        assertEquals(
            listOf(SkillScope.GLOBAL, SkillScope.GLOBAL, SkillScope.PROJECT),
            merged.map { it.scope }
        )
    }

    @Test
    fun mergeAll_builtinOverriddenByUserSkill() {
        val merged = SkillRepository.mergeAll(
            builtin = listOf(skill("same"), skill("only-builtin")),
            global = listOf(skill("same")),
            project = emptyList()
        )

        assertEquals(listOf("only-builtin", "same"), merged.map { it.skill.name })
        assertEquals(SkillScope.BUILTIN, merged.first { it.skill.name == "only-builtin" }.scope)
        // 同名时用户的全局技能压过内置
        assertEquals(SkillScope.GLOBAL, merged.first { it.skill.name == "same" }.scope)
    }

    @Test
    fun mergeAll_projectOverridesSameName() {
        val merged = SkillRepository.mergeAll(
            builtin = emptyList(),
            global = listOf(skill("same")),
            project = listOf(skill("same"))
        )

        assertEquals(1, merged.size)
        assertEquals("same", merged[0].skill.name)
        assertEquals(SkillScope.PROJECT, merged[0].scope)
    }

    @Test
    fun mergeAll_caseInsensitiveDedup() {
        val merged = SkillRepository.mergeAll(
            builtin = emptyList(),
            global = listOf(skill("MixedCase")),
            project = listOf(skill("mixedcase"))
        )

        assertEquals(1, merged.size)
        assertEquals(SkillScope.PROJECT, merged[0].scope)
    }

    @Test
    fun mergeAll_sortedByName() {
        val merged = SkillRepository.mergeAll(
            builtin = emptyList(),
            global = listOf(skill("zeta"), skill("alpha")),
            project = listOf(skill("middle"))
        )

        assertEquals(listOf("alpha", "middle", "zeta"), merged.map { it.skill.name })
    }

    @Test
    fun filterDisabled_removesDisabledSkills() {
        val entries = SkillRepository.mergeAll(
            builtin = emptyList(),
            global = listOf(skill("keep"), skill("drop")),
            project = emptyList()
        )

        val filtered = SkillRepository.filterDisabled(entries, setOf("drop"))

        assertEquals(listOf("keep"), filtered.map { it.skill.name })
    }

    @Test
    fun filterDisabled_caseInsensitive() {
        val entries = SkillRepository.mergeAll(
            builtin = emptyList(),
            global = listOf(skill("Keep")),
            project = emptyList()
        )

        val filtered = SkillRepository.filterDisabled(entries, setOf("keep"))

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun filterDisabled_unknownNamesIgnored() {
        val entries = SkillRepository.mergeAll(
            builtin = emptyList(),
            global = listOf(skill("keep")),
            project = emptyList()
        )

        val filtered = SkillRepository.filterDisabled(entries, setOf("not-exists"))

        assertEquals(listOf("keep"), filtered.map { it.skill.name })
    }

    @Test
    fun safeDeleteSkillDir_deletesOnlySkillDirectories() {
        val temp = java.nio.file.Files.createTempDirectory("skills-test").toFile()
        try {
            val skillDir = File(temp, "my-skill")
            skillDir.mkdirs()
            File(skillDir, "SKILL.md").writeText("---\nname: my-skill\n---\n正文")
            File(skillDir, "run.py").writeText("print(1)")

            // 含 SKILL.md 的目录可删
            assertTrue(SkillRepository.safeDeleteSkillDir(provider, skillDir.absolutePath))
            assertTrue(!skillDir.exists())

            // 不含指令文件的目录拒绝删除
            val plainDir = File(temp, "not-a-skill")
            plainDir.mkdirs()
            File(plainDir, "readme.txt").writeText("x")
            assertTrue(!SkillRepository.safeDeleteSkillDir(provider, plainDir.absolutePath))
            assertTrue(plainDir.exists())

            // 不存在的目录返回 false
            assertTrue(!SkillRepository.safeDeleteSkillDir(provider, File(temp, "ghost").absolutePath))
        } finally {
            temp.deleteRecursively()
        }
    }

    @Test
    fun isValidName_rejectsPathTraversalAndSeparators() {
        assertTrue(SkillRepository.isValidName("pdf-report"))
        assertTrue(SkillRepository.isValidName("中文技能名"))
        assertTrue(!SkillRepository.isValidName(""))
        assertTrue(!SkillRepository.isValidName("   "))
        assertTrue(!SkillRepository.isValidName("."))
        assertTrue(!SkillRepository.isValidName(".."))
        assertTrue(!SkillRepository.isValidName("a/b"))
        assertTrue(!SkillRepository.isValidName("a\\b"))
        assertTrue(!SkillRepository.isValidName("a:b"))
        assertTrue(!SkillRepository.isValidName("a\u0000b"))
        assertTrue(!SkillRepository.isValidName("x".repeat(65)))
    }

    @Test
    fun instructionFile_prefersSkillMdThenClaudeMd() {
        val temp = java.nio.file.Files.createTempDirectory("skills-instruction-test").toFile()
        try {
            val both = File(temp, "both").apply { mkdirs() }
            File(both, "CLAUDE.md").writeText("x")
            File(both, "skill.md").writeText("x")
            assertEquals("skill.md", SkillRepository.instructionFile(provider, both.absolutePath)?.substringAfterLast('/'))

            val claudeOnly = File(temp, "claude-only").apply { mkdirs() }
            File(claudeOnly, "CLAUDE.md").writeText("x")
            assertEquals("CLAUDE.md", SkillRepository.instructionFile(provider, claudeOnly.absolutePath)?.substringAfterLast('/'))

            val empty = File(temp, "empty").apply { mkdirs() }
            assertEquals(null, SkillRepository.instructionFile(provider, empty.absolutePath))
        } finally {
            temp.deleteRecursively()
        }
    }
    @Test
    fun metadata_savedAndImportedAndExportedWithoutLoss() {
        val temp = java.nio.file.Files.createTempDirectory("skill-metadata").toFile()
        try {
            val existing = File(temp, "editable").apply { mkdirs() }
            File(existing, "SKILL.md").writeText("---\nname: editable\n---\nold body")
            val typed = skill("editable").copy(dirPath = existing.path)
            every { globalSource.listSkills() } returns listOf(typed)
            val types = mockk<SkillTypeRepository>()
            every { types.get("general", SkillScope.GLOBAL) } returns SkillType("general", "General", "common", "functions")
            val repo = SkillRepository(builtinSource, globalSource, projectSource, config, provider, agents, types)
            val tags = listOf("标签: #1", "quote \" \\ path")
            val form = SkillForm("editable", "desc", "specialty", tags = tags, typeId = "general")
            assertEquals(null, repo.save(form, SkillScope.GLOBAL, "editable"))
            val parsed = SkillParser.parse(provider, existing.path, "editable")!!
            assertEquals(tags, parsed.tags)
            assertEquals("general", parsed.typeId)
            val destination = File(temp, "imported").path
            val imported = SkillImporter.importMarkdown(provider, destination, emptySet(), File(existing, "SKILL.md").readText(), "imported")
            assertEquals(listOf("editable"), imported.imported)
            assertNull(imported.fatal)
            assertTrue(imported.failures.isEmpty())
            val importedDir = File(destination, "editable")
            assertEquals(tags, SkillParser.parse(provider, importedDir.path, "editable")!!.tags)
            val zip = repo.exportZip("editable", SkillScope.GLOBAL)!!
            val restoredRoot = File(temp, "restored").path
            val restoredReport = SkillImporter.importArchive(provider, restoredRoot, emptySet(), zip.inputStream(), "restored")
            assertEquals(listOf("editable"), restoredReport.imported)
            assertNull(restoredReport.fatal)
            assertTrue(restoredReport.failures.isEmpty())
            val restored = SkillParser.parse(provider, File(restoredRoot, "editable").path, "editable")!!
            assertEquals(tags, restored.tags)
            assertEquals("general", restored.typeId)
        } finally { temp.deleteRecursively() }
    }

    @Test
    fun typeGuidance_precedesSpecialtyAndMissingTypeFailsClosed() {
        val types = mockk<SkillTypeRepository>()
        val localSkill = skill("typed").copy(typeId = "general", instructions = "specialty")
        every { projectSource.listSkills() } returns listOf(localSkill)
        every { types.get("general", SkillScope.PROJECT) } returns SkillType("general", "General", "common semantics", "common functions")
        val repo = SkillRepository(builtinSource, globalSource, projectSource, config, provider, agents, types)
        assertEquals("common semantics\n\ncommon functions\n\nspecialty", repo.loadInstructions("typed"))
        assertEquals("common semantics\n\ncommon functions\n\nspecialty", repo.loadInstructionsForAgent("typed", null, "", true))
        every { types.get("general", SkillScope.PROJECT) } returns null
        assertNull(repo.loadInstructions("typed"))
        assertEquals(SkillSaveError.UNKNOWN_TYPE, repo.save(SkillForm("new", "", "body", typeId = "general"), SkillScope.PROJECT))
        every { config.disabledNames() } returns setOf("typed")
        assertNull(repo.loadInstructionsForAgent("typed", null, "", true))
    }

    @Test
    fun exactTagAndTypeSearch_keepsKeywordSearchIndependent() {
        val item = com.aharou.feature.settings.presentation.SkillUiEntry("report", "description", SkillScope.GLOBAL,
            disabled = false, instructions = "body", keywordEnabled = true, keywords = listOf("Kotlin"), tags = listOf("tag one"), typeId = "general", typeName = "General type")
        with(item) {
            assertTrue(matchesSkillQuery("tag:\"tag one\" type:general"))
            assertTrue(matchesSkillQuery("type:\"General type\" Kotlin"))
            assertFalse(matchesSkillQuery("tag:tag"))
            assertFalse(matchesSkillQuery("type:gen"))
            assertFalse(matchesSkillQuery("tag:Kotlin"))
        }
    }

    @Test
    fun savingResourceEditsPreservesNestedFilesAndRejectsTraversal() {
        val root = java.nio.file.Files.createTempDirectory("skill-resources-save").toFile()
        try {
            val dir = File(root, "editable").apply { mkdirs() }
            val instruction = File(dir, "SKILL.md").apply { writeText("---\nname: editable\n---\nold") }
            val untouched = File(dir, "code/original.bin").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(0, 7, 8)) }
            every { globalSource.listSkills() } answers { listOf(SkillParser.parseText(instruction.readText(), "editable").copy(dirPath = dir.path)) }
            assertNull(repository.save(SkillForm("editable", "desc", "new", listOf("Bash"),
                resourceEdits = mapOf("scripts/nested/run.py" to "print('ok')")), SkillScope.GLOBAL, "editable"))
            assertEquals("print('ok')", File(dir, "scripts/nested/run.py").readText())
            assertTrue(untouched.readBytes().contentEquals(byteArrayOf(0, 7, 8)))
            assertEquals(listOf("Bash"), SkillParser.parseText(instruction.readText(), "editable").requiredTools)
            assertTrue(repository.loadInstructions("editable")!!.contains("${dir.path}/scripts/nested/run.py"))
            assertEquals(SkillSaveError.IO_FAILED, repository.save(SkillForm("editable", "desc", "bad",
                resourceEdits = mapOf("../escape.txt" to "bad")), SkillScope.GLOBAL, "editable"))
            assertFalse(File(root, "escape.txt").exists())
        } finally { root.deleteRecursively() }
    }

    @Test
    fun resourceWritesRollbackWhenInstructionCommitFails() {
        val root = java.nio.file.Files.createTempDirectory("skill-resource-rollback").toFile()
        try {
            val dir = File(root, "editable").apply { mkdirs() }
            val instruction = File(dir, "SKILL.md").apply { writeText("---\nname: editable\n---\nold") }
            val script = File(dir, "scripts/run.py").apply { parentFile.mkdirs(); writeText("old script") }
            every { globalSource.listSkills() } answers { listOf(SkillParser.parseText(instruction.readText(), "editable").copy(dirPath = dir.path)) }
            val access = spyk(provider)
            var failOnce = true
            every { access.writeFile(instruction.path, any(), any(), any()) } answers {
                if (failOnce) { failOnce = false; throw java.io.IOException("fixture commit failure") }
                callOriginal()
            }
            val repo = SkillRepository(builtinSource, globalSource, projectSource, config, access, agents)
            val form = SkillForm("editable", "", "new", resourceEdits = mapOf("scripts/run.py" to "new script", "templates/new.txt" to "new"))
            assertEquals(SkillSaveError.IO_FAILED, repo.save(form, SkillScope.GLOBAL, "editable"))
            assertEquals("old script", script.readText())
            assertFalse(File(dir, "templates/new.txt").exists())
            assertTrue(instruction.readText().endsWith("old"))
        } finally { root.deleteRecursively() }
    }

    @Test fun projectImportPinsScanAndWriteBackendAcrossGlobalSwitch() {
        val temp = java.nio.file.Files.createTempDirectory("skill-project-pinned").toFile()
        try {
            val a = File(temp, "A").apply { mkdirs() }
            val b = File(temp, "B").apply { mkdirs() }
            var current = a
            fun mapper(): com.aharou.feature.workspace.domain.WorkspacePathMapper = mockk<com.aharou.feature.workspace.domain.WorkspacePathMapper>().apply {
                every { toHostFile(any(), any()) } answers {
                    val root = secondArg<String?>()?.let(::File) ?: current
                    val path = firstArg<String>()
                    if (path == "~/workspace") root else File(root, path.removePrefix("~/workspace/"))
                }
                every { resolveFile(any(), any(), any()) } answers {
                    val root = secondArg<String?>()?.let(::File) ?: current
                    File(root, firstArg<String>().removePrefix("~/workspace/"))
                }
                every { toContainerPath(any(), any()) } answers { firstArg<String>() }
            }
            val local = com.aharou.feature.workspace.domain.LocalFileAccess(mapper())
            val fileA = File(a, ".aharou/skills/existing/SKILL.md").apply { parentFile!!.mkdirs(); writeText("---\nname: duplicate\n---\nA") }
            every { projectSource.skillsRoot } returns "~/workspace/.aharou/skills"
            val repo = SkillRepository(builtinSource, globalSource, projectSource, config, local, agents)
            val markdown = "---\nname: pinned\n---\nbody"
            val report = repo.importMarkdown(markdown, "pinned", SkillScope.PROJECT, expectedWorkspace = a.path,
                authorizeWrite = { current = b })
            assertEquals(listOf("pinned"), report.imported)
            assertTrue(File(a, ".aharou/skills/pinned/SKILL.md").isFile)
            assertFalse(File(b, ".aharou/skills/pinned/SKILL.md").exists())
            assertTrue(fileA.isFile)
            assertEquals(SkillImportError.NAME_CONFLICT, repo.importMarkdown("---\nname: duplicate\n---\nbody", "duplicate",
                SkillScope.PROJECT, expectedWorkspace = a.path).fatal)
            val zip = requireNotNull(SkillExporter.zipOf("zipped", mapOf("SKILL.md" to "---\nname: zipped\n---\nbody".toByteArray(), "scripts/run.py" to "old".toByteArray())))
            current = a
            val imported = repo.importZip(zip.inputStream(), "zipped", SkillScope.PROJECT, expectedWorkspace = a.path, authorizeWrite = { current = b })
            assertEquals(listOf("zipped"), imported.imported)
            assertEquals("old", File(a, ".aharou/skills/zipped/scripts/run.py").readText())
            assertFalse(File(b, ".aharou/skills/zipped").exists())
        } finally { temp.deleteRecursively() }
    }

    @Test fun archivePermissionPreflightRejectsLaterResourceBeforeAnyWrite() {
        val root = java.nio.file.Files.createTempDirectory("skill-preflight").toFile()
        try {
            every { globalSource.skillsRoot } returns root.path
            val provider = TestFileAccessProvider().let { delegate ->
                object : com.aharou.feature.workspace.domain.FileAccessProvider by delegate {
                    override fun checked(): com.aharou.feature.workspace.domain.FileAccessProvider = this
                    override fun requireResourcePath(root: String, relative: String) {
                        require(!relative.startsWith('/') && relative.split('/').none { it == ".." })
                    }
                }
            }
            val repo = SkillRepository(builtinSource, globalSource, projectSource, config, provider, agents)
            val zip = requireNotNull(SkillExporter.zipOf("demo", linkedMapOf(
                "SKILL.md" to "---\nname: demo\n---\nbody".toByteArray(), "scripts/run.py" to "pass".toByteArray())))
            try {
                repo.importZip(zip.inputStream(), "demo", SkillScope.GLOBAL, authorizeWrite = { path ->
                    if (path.endsWith("run.py")) throw SecurityException("WRITE_LEASE_DENIED")
                })
                fail("Later resource must be rejected before the instruction is written")
            } catch (expected: SecurityException) { assertEquals("WRITE_LEASE_DENIED", expected.message) }
            assertFalse(File(root, "demo/SKILL.md").exists())
            assertFalse(File(root, "demo/scripts/run.py").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun archiveRuntimeRevocationRestoresOnlyThisImportsPriorFiles() {
        val root = java.nio.file.Files.createTempDirectory("skill-rollback").toFile()
        try {
            every { globalSource.skillsRoot } returns root.path
            val delegate = TestFileAccessProvider()
            val provider = object : com.aharou.feature.workspace.domain.FileAccessProvider by delegate {
                override fun checked(): com.aharou.feature.workspace.domain.FileAccessProvider = this
                override fun requireResourcePath(root: String, relative: String) {
                    require(!relative.startsWith('/') && relative.split('/').none { it == ".." })
                }
            }
            val instruction = File(root, "demo/SKILL.md").apply { parentFile!!.mkdirs(); writeText("---\nname: demo\n---\nold") }
            val script = File(root, "demo/scripts/run.py").apply { parentFile!!.mkdirs(); writeText("old script") }
            val repo = SkillRepository(builtinSource, globalSource, projectSource, config, provider, agents)
            val zip = requireNotNull(SkillExporter.zipOf("demo", linkedMapOf(
                "SKILL.md" to "---\nname: demo\n---\nnew".toByteArray(), "scripts/run.py" to "new script".toByteArray())))
            var scriptChecks = 0
            try {
                repo.importZip(zip.inputStream(), "demo", SkillScope.GLOBAL, overwrite = true, authorizeWrite = { path ->
                    if (path.endsWith("run.py") && ++scriptChecks == 2) throw SecurityException("WRITE_LEASE_DENIED")
                })
                fail("Revocation after preflight must reject the remaining writes")
            } catch (expected: SecurityException) { assertEquals("WRITE_LEASE_DENIED", expected.message) }
            assertEquals("---\nname: demo\n---\nold", instruction.readText())
            assertEquals("old script", script.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun builtinEditorAndExportUseRetainedDiskResourcesNotNewApk() {
        val temp = java.nio.file.Files.createTempDirectory("skill-builtin-retained").toFile()
        val capability = com.aharou.core.config.BuiltinSkillEditCapability
        io.mockk.mockkObject(com.aharou.feature.agent.domain.container.BuiltinAssetUpgradeCoordinator.Companion)
        try {
            val dir = File(temp, "builtin-skills/retained").apply { mkdirs() }
            File(dir, "SKILL.md").writeText("---\nname: retained\ncustom_metadata: keep\n---\nold instructions")
            File(dir, "scripts").mkdirs()
            File(dir, "scripts/old.py").writeText("old code")
            val binary = byteArrayOf(0, -1, 2)
            File(dir, "asset.bin").writeBytes(binary)
            val context = mockk<android.content.Context>(relaxed = true)
            val apkAssets = mockk<android.content.res.AssetManager>()
            every { context.assets } returns apkAssets
            every { apkAssets.open("skills/retained/scripts/new.py") } answers { "different-new-apk-code".byteInputStream() }
            val coordinator = com.aharou.feature.agent.domain.container.BuiltinAssetUpgradeCoordinator(temp, 2, true)
            coordinator.choose(false)
            every { com.aharou.feature.agent.domain.container.BuiltinAssetUpgradeCoordinator.initialize(context) } returns coordinator
            val source = BuiltinSkillSource(context, language)
            val root = File(temp, "global").absolutePath
            every { globalSource.skillsRoot } returns root
            val repo = SkillRepository(source, globalSource, projectSource, config, provider, agents)
            val skill = source.listSkills().single()
            assertEquals(listOf("asset.bin", "scripts/old.py"), repo.resourcePaths(skill))
            assertEquals(mapOf("scripts/old.py" to "old code"), repo.resourceTexts(skill, repo.resourcePaths(skill)))
            capability.setEnabled(true)
            assertNull(repo.save(SkillForm("retained", "edited", "edited instructions"), SkillScope.BUILTIN))
            assertEquals("old code", File(root, "retained/scripts/old.py").readText())
            assertTrue(binary.contentEquals(File(root, "retained/asset.bin").readBytes()))
            assertTrue(File(root, "retained/SKILL.md").readText().contains("custom_metadata: keep"))
            val exported = requireNotNull(repo.exportZip("retained", SkillScope.BUILTIN))
            val entries = java.util.zip.ZipInputStream(exported.inputStream()).use { zip ->
                generateSequence { zip.nextEntry }.filterNot { it.isDirectory }.associate { it.name to zip.readBytes() }
            }
            val retainedFiles = listOf("SKILL.md", "scripts/old.py", "asset.bin")
            assertEquals(retainedFiles.map { "retained/$it" }.toSet(), entries.keys)
            for (path in retainedFiles) {
                org.junit.Assert.assertArrayEquals(path, File(dir, path).readBytes(), entries.getValue("retained/$path"))
            }
            val unpacked = File(temp, "imported").absolutePath
            val imported = SkillImporter.importArchive(provider, unpacked, emptySet(), exported.inputStream(), "retained")
            assertEquals(listOf("retained"), imported.imported)
            assertEquals("old code", File(unpacked, "retained/scripts/old.py").readText())
            assertTrue(binary.contentEquals(File(unpacked, "retained/asset.bin").readBytes()))
            assertFalse(File(unpacked, "retained/scripts/new.py").exists())
            io.mockk.verify(exactly = 0) { context.assets }
            val broken = spyk(provider)
            every { broken.checked() } returns broken
            every { broken.writeFile(any(), any(), any(), any()) } throws java.io.IOException("fixture_write_failed")
            val failedRoot = File(temp, "failed").absolutePath
            every { globalSource.skillsRoot } returns failedRoot
            val failing = SkillRepository(source, globalSource, projectSource, config, broken, agents)
            assertEquals(SkillSaveError.IO_FAILED, failing.save(SkillForm("retained", "", "edit"), SkillScope.BUILTIN))
            assertFalse(File(failedRoot, "retained/scripts/old.py").exists())
            assertFalse(File(failedRoot, "retained/SKILL.md").exists())
        } finally {
            capability.setEnabled(false)
            io.mockk.unmockkObject(com.aharou.feature.agent.domain.container.BuiltinAssetUpgradeCoordinator.Companion)
            temp.deleteRecursively()
        }
    }


}
