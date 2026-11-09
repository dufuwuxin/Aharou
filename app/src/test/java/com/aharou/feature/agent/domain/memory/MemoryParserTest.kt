package com.aharou.feature.agent.domain.memory

import com.aharou.feature.agent.domain.context.ContextCategory
import com.aharou.feature.agent.domain.context.ContextLoad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun format_and_parse_roundtrip() {
        val name = "user_preference"
        val description = "User prefers dark mode and concise answers"
        val content = "# User Preferences\n- Dark mode: true\n- Conciseness: high"

        val formatted = MemoryParser.format(name, description, content)
        val file = tempFolder.newFile("user_preference.md")
        file.writeText(formatted)

        val memory = MemoryParser.parse(file, MemoryScope.GLOBAL)
        assertTrue(memory != null)
        assertEquals("user_preference", memory?.name)
        assertEquals("User prefers dark mode and concise answers", memory?.description)
        assertEquals(MemoryScope.GLOBAL, memory?.scope)
        assertEquals(content.trim(), memory?.content)
    }

    @Test
    fun format_escapesSpecialCharactersInYaml() {
        val name = "special:key#name"
        val description = "Description with \"quotes\" and : colons"
        val content = "Memory body"

        val formatted = MemoryParser.format(name, description, content)
        val file = tempFolder.newFile("special.md")
        file.writeText(formatted)

        val memory = MemoryParser.parse(file, MemoryScope.PROJECT)
        assertTrue(memory != null)
        assertEquals("special:key#name", memory?.name)
        assertEquals("Description with \"quotes\" and : colons", memory?.description)
        assertEquals("Memory body", memory?.content)
    }

    @Test
    fun format_then_parse_keeps_pending_flag() {
        val formatted = MemoryParser.format(
            name = "demo",
            description = "一句摘要",
            content = "正文内容",
            type = MemoryType.FACT,
            keywords = listOf("alpha", "beta"),
            pending = true
        )
        val file = tempFolder.newFile("pending.md")
        file.writeText(formatted)

        val memory = MemoryParser.parse(file, MemoryScope.GLOBAL)
        assertNotNull(memory)
        assertTrue(memory!!.pending)
        assertEquals("demo", memory.name)
        assertEquals("正文内容", memory.content)
    }

    @Test
    fun parse_defaults_pending_to_false_when_absent() {
        val file = tempFolder.newFile("plain.md")
        file.writeText(MemoryParser.format("plain", "摘要", "正文"))

        val memory = MemoryParser.parse(file, MemoryScope.GLOBAL)
        assertNotNull(memory)
        assertFalse(memory!!.pending)
    }

    @Test
    fun sanitizeName_turns_path_separators_into_flat_name() {
        assertEquals("a-b", MemorySource.sanitizeName("a/b"))
        assertFalse(MemorySource.sanitizeName("../escape").contains('/'))
    }

    @Test fun legacyInjectionMapsToMainAndKeepsExplicitMetadataAndAllThreeFields() {
        val file = tempFolder.newFile("legacy-injected.md")
        val legacy = MemoryParser.format("标题", "摘要", "正文", contextLoad = ContextLoad.INDEX,
            contextCategory = ContextCategory.MAIN_INJECTED).replace("MAIN_INJECTED", "INJECTED")
        file.writeText(legacy)
        val parsed = MemoryParser.parse(file, MemoryScope.GLOBAL)!!
        assertEquals(ContextCategory.MAIN_INJECTED, parsed.contextCategory)
        assertTrue(parsed.hasContextMetadata)
        assertEquals("标题", parsed.name)
        assertEquals("摘要", parsed.description)
        assertEquals("正文", parsed.content)
        assertEquals(legacy, file.readText())
        assertTrue(MemoryParser.format(parsed).contains("context_category: MAIN_INJECTED"))
        assertEquals(ContextCategory.MAIN_INJECTED, ContextCategory.from("injected"))
    }

    @Test fun bothInjectionCategoriesRoundTripWithoutCrossAudienceFallback() {
        listOf(ContextCategory.ROLE_INJECTED, ContextCategory.MAIN_INJECTED).forEach { category ->
            val file = tempFolder.newFile("${category.name}.md")
            file.writeText(MemoryParser.format("标题", "摘要", "正文", contextCategory = category))
            assertEquals(category, MemoryParser.parse(file, MemoryScope.PROJECT)!!.contextCategory)
        }
    }


    @Test fun stableRequestMetadataRoundTripsIndependentOfEditableFolderAndName() {
        val file = tempFolder.newFile("request.md")
        file.writeText(MemoryParser.format("改名", "说明", "正文", contextFolder = "任意/目录",
            contextRequest = "agent/custom.md"))
        val memory = MemoryParser.parse(file, MemoryScope.GLOBAL)!!
        assertEquals("agent/custom.md", memory.contextRequest)
        file.writeText(MemoryParser.format(memory.copy(name = "再次改名", contextFolder = "")))
        assertEquals("agent/custom.md", MemoryParser.parse(file, MemoryScope.GLOBAL)!!.contextRequest)
    }

}
