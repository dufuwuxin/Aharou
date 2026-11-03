package com.aharou.feature.agent.domain.prompt

import android.content.Context
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.agent.domain.subagent.InjectPart
import com.aharou.feature.agent.domain.subagent.KeywordInjection
import com.aharou.feature.workspace.domain.ProjectAharouRoot
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PromptFragmentPolicySaveTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun updateKeepsOtherScopeAndExistingMetadata() {
        val globalRoot = folder.newFolder("global")
        val projectRoot = folder.newFolder("project")
        val global = File(globalRoot, "prompts.custom/10-rules.md").apply {
            parentFile.mkdirs(); writeText("global baseline")
        }
        val keywords = listOf(KeywordInjection(listOf("trigger"), "legacy body"))
        val parts = setOf(InjectPart.BASE)
        val project = File(projectRoot, "prompts.custom/10-rules.md").apply {
            parentFile.mkdirs()
            writeText("project body" + PromptFragmentMeta.encode(keywords, parts, PromptFragmentInjection()))
        }
        val context = mockk<Context>()
        every { context.assets.list("prompts") } returns emptyArray()
        val installer = mockk<ContainerInstaller>()
        every { installer.aharouDir } returns globalRoot
        val roots = mockk<ProjectAharouRoot>()
        every { roots.forPath("/project") } returns projectRoot
        val catalog = PromptFragmentCatalog(context, installer, roots, mockk())
        val injection = PromptFragmentInjection(false, listOf("Kotlin"), setOf("reviewer"), false, false)
        assertTrue(catalog.updateInjection(10, "/project", injection))
        assertEquals("global baseline", global.readText())
        assertEquals("project body", PromptFragmentMeta.stripTrailingMeta(project.readText()))
        assertEquals(keywords to parts, PromptFragmentMeta.parse(project.readText()))
        assertEquals(injection, catalog.fragment(10, "/project")!!.injection)
    }

    @Test fun failedReplacementKeepsExistingTargetAndCleansTemporaryFile() {
        val target = folder.newFolder("10-rules.md")
        val original = File(target, "original").apply { writeText("keep") }
        val catalog = spyk(PromptFragmentCatalog(mockk(), mockk(), mockk(), mockk()))
        every { catalog.fragment(10, "/project") } returns
            PromptFragment(10, "rules", PromptFragmentSource.PROJECT, "body", target, "")
        assertFalse(catalog.updateInjection(10, "/project", PromptFragmentInjection(false)))
        assertEquals("keep", original.readText())
        assertTrue(folder.root.listFiles().orEmpty().none { it.name.startsWith(".injection-") })
    }
}
