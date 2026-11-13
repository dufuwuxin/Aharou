package com.aharou.feature.agent.domain.subagent

import com.aharou.feature.workspace.domain.FileAccessProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AgentSkillBindingTest {
    private val global = mockk<GlobalDirectoryAgentSource>()
    private val project = mockk<ProjectDirectoryAgentSource>()
    private val config = mockk<AgentDefinitionConfigRepository>()
    private val files = mockk<FileAccessProvider>(relaxed = true)
    private val repository = AgentDefinitionRepository(global, project, config, files)
    private val original = AgentDefinition(
        name = "member",
        description = "description",
        providerId = "provider",
        model = "model",
        reasoningEffort = "high",
        allowedTools = listOf("readFile"),
        disallowedTools = listOf("Bash"),
        skills = listOf("existing"),
        inject = setOf(InjectPart.SKILLS),
        keywordInjections = listOf(KeywordInjection(listOf("review"), "check \"carefully\"\nkeep all details")),
        prompt = "member instructions",
        filePath = "/global/original.md"
    )
    private var writtenPath = ""
    private var writtenText = ""

    @Before
    fun setUp() {
        every { global.listDefinitions() } returns listOf(original)
        every { project.listDefinitions() } returns emptyList()
        every { global.agentsRoot } returns "/global"
        every { project.agentsRoot } returns "/project"
        every { files.isFile(any()) } returns true
        every { files.writeFile(any(), any(), any(), any()) } answers {
            writtenPath = firstArg()
            writtenText = secondArg()
        }
        every { files.readFile(any()) } answers { writtenText }
    }

    @Test
    fun bind_preservesOtherFieldsAndOriginalFilename() {
        assertNull(repository.setSkillBinding("member", AgentDefinitionScope.GLOBAL, "shared", true))
        assertEquals("/global/original.md", writtenPath)
        val saved = AgentDefinitionParser.parse(files, writtenPath)
        assertEquals(original.copy(skills = listOf("existing", "shared")), saved)
    }

    @Test
    fun bind_sameSkillCanBeSharedByTwoMembers() {
        val second = original.copy(name = "second", filePath = "/global/second.md")
        every { global.listDefinitions() } returns listOf(original, second)
        assertNull(repository.setSkillBinding("member", AgentDefinitionScope.GLOBAL, "shared", true))
        assertNull(repository.setSkillBinding("second", AgentDefinitionScope.GLOBAL, "shared", true))
        verify(exactly = 1) { files.writeFile("/global/original.md", any(), any(), any()) }
        verify(exactly = 1) { files.writeFile("/global/second.md", any(), any(), any()) }
    }

    @Test
    fun unbind_removesOnlySelectedSkill() {
        every { global.listDefinitions() } returns listOf(original.copy(skills = listOf("shared", "existing")))
        assertNull(repository.setSkillBinding("member", AgentDefinitionScope.GLOBAL, "SHARED", false))
        assertEquals(listOf("existing"), AgentDefinitionParser.parse(files, writtenPath)?.skills)
    }

    @Test
    fun bind_projectOverrideDoesNotWriteGlobalDefinition() {
        val local = original.copy(filePath = "/project/local.md")
        every { project.listDefinitions() } returns listOf(local)
        assertNull(repository.setSkillBinding("member", AgentDefinitionScope.PROJECT, "shared", true))
        assertEquals("/project/local.md", writtenPath)
        verify(exactly = 0) { files.writeFile("/global/original.md", any(), any(), any()) }
    }

    @Test
    fun bind_missingMemberDoesNotRecreateDefinition() {
        every { global.listDefinitions() } returns emptyList()
        assertEquals(AgentSaveError.IO_FAILED, repository.setSkillBinding("member", AgentDefinitionScope.GLOBAL, "shared", true))
        verify(exactly = 0) { files.writeFile(any(), any(), any(), any()) }
    }

    @Test
    fun bind_memberRemovedDuringSaveDoesNotRecreateDefinition() {
        every { global.listDefinitions() } returnsMany listOf(listOf(original), emptyList())
        assertEquals(AgentSaveError.IO_FAILED, repository.setSkillBinding("member", AgentDefinitionScope.GLOBAL, "shared", true))
        verify(exactly = 0) { files.writeFile(any(), any(), any(), any()) }
    }

    @Test
    fun bind_missingOriginalFileDoesNotCreateAnotherFilename() {
        every { files.isFile("/global/original.md") } returns false
        assertEquals(AgentSaveError.IO_FAILED, repository.setSkillBinding("member", AgentDefinitionScope.GLOBAL, "shared", true))
        verify(exactly = 0) { files.writeFile(any(), any(), any(), any()) }
    }

    @Test
    fun bind_existingBindingIsNotDuplicated() {
        every { global.listDefinitions() } returns listOf(original.copy(skills = listOf("existing", "shared")))
        assertNull(repository.setSkillBinding("member", AgentDefinitionScope.GLOBAL, "shared", true))
        verify(exactly = 0) { files.writeFile(any(), any(), any(), any()) }
    }
}
