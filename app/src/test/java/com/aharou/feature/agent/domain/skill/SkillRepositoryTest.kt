package com.aharou.feature.agent.domain.skill

import com.aharou.testutil.TestFileAccessProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillRepositoryTest {

    private val provider = TestFileAccessProvider()
    private val language = mockk<AppLanguageProvider>().apply { every { code } returns "zh" }
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
        val tool = com.aharou.feature.agent.domain.tool.skill.LoadSkillTool(repository)
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
}
