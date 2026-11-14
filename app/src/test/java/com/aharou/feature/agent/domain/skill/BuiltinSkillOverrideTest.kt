package com.aharou.feature.agent.domain.skill

import com.aharou.core.config.BuiltinSkillEditCapability
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.agent.domain.subagent.AgentDefinitionRepository
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class BuiltinSkillOverrideTest {
    @After fun reset() { BuiltinSkillEditCapability.setEnabled(false) }
    @Test fun overrideWritesGlobalAndRevocationPreservesSavedContent() {
        val builtin = mockk<BuiltinSkillSource>()
        every { builtin.listSkills() } returns listOf(Skill("builtin", "", instructions = "original"))
        val global = mockk<GlobalDirectorySkillSource>()
        every { global.listSkills() } returns emptyList()
        every { global.skillsRoot } returns "/global"
        val project = mockk<ProjectDirectorySkillSource>()
        every { project.listSkills() } returns emptyList()
        val files = mockk<FileAccessProvider>(relaxed = true)
        every { files.isFile(any()) } returns false
        every { files.isDirectory(any()) } returns false
        val agents = mockk<AgentDefinitionRepository>()
        every { agents.listAll() } returns emptyList()
        val config = mockk<SkillConfigRepository>(relaxed = true)
        val repo = SkillRepository(builtin, global, project, config, files, agents)
        every { files.writeFile(any(), any(), any(), any()) } answers {
            assertTrue("Permission revocation must share the write lock", Thread.holdsLock(BuiltinSkillEditCapability))
        }
        val form = SkillForm("builtin", "", "saved override")
        BuiltinSkillEditCapability.setEnabled(false)
        assertEquals(SkillSaveError.READ_ONLY, repo.save(form, SkillScope.BUILTIN))
        BuiltinSkillEditCapability.setEnabled(true)
        assertNull(repo.save(form, SkillScope.BUILTIN))
        verify(exactly = 1) { files.writeFile("/global/builtin/SKILL.md", match { it.contains("saved override") }, true, Charsets.UTF_8) }
        BuiltinSkillEditCapability.setEnabled(false)
        assertEquals(SkillSaveError.READ_ONLY, repo.save(form.copy(instructions = "denied"), SkillScope.GLOBAL, "builtin"))
        repo.setSkillDisabled("builtin", true, SkillScope.GLOBAL)
        repo.setSkillDisabled("builtin", false, SkillScope.GLOBAL)
        verify { config.setEffectiveDisabled("builtin", true, SkillScope.GLOBAL) }
        verify { config.setEffectiveDisabled("builtin", false, SkillScope.GLOBAL) }
        assertTrue(repo.importMarkdown("---\nname: builtin\n---\ndenied", "builtin", SkillScope.GLOBAL, overwrite = true).imported.isEmpty())
        verify(exactly = 0) { files.delete(any()) }
        verify(exactly = 1) { files.writeFile(any(), any(), any(), any()) }
    }
}
