package com.aharou.feature.agent.domain.skill

import com.aharou.testutil.TestFileAccessProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillParserTest {

    private fun parseSerialized(
        name: String,
        description: String,
        requiredTools: List<String> = emptyList(),
        instructions: String
    ): Skill {
        val dir = java.nio.file.Files.createTempDirectory("skill-parser-test").toFile()
        return try {
            File(dir, "SKILL.md").writeText(
                SkillParser.serialize(
                    name = name,
                    description = description,
                    requiredTools = requiredTools,
                    instructions = instructions
                )
            )
            val parsed = SkillParser.parse(TestFileAccessProvider(), dir.absolutePath, "zh")
            assertNotNull(parsed)
            parsed!!
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun keywords_roundTripAndLegacyDefaultsRemainPublic() {
        val text = SkillParser.serialize("conditional", "summary", emptyList(), "private body", true, listOf("Kotlin", "x: y"))
        val parsed = SkillParser.parseText(text, "fallback")
        assertTrue(parsed.keywordEnabled)
        assertEquals(listOf("Kotlin", "x: y"), parsed.keywords)
        assertTrue(!SkillParser.parseText("---\nname: legacy\n---\nbody", "fallback").keywordEnabled)
    }

    @Test
    fun serialize_roundTripsThroughParse() {
        val skill = parseSerialized(
            name = "pdf-report",
            description = "生成 PDF 报告时使用",
            requiredTools = listOf("Bash", "writeFile"),
            instructions = "## 步骤\n\n1. 读模板\n2. 渲染"
        )

        assertEquals("pdf-report", skill.name)
        assertEquals("生成 PDF 报告时使用", skill.description)
        assertEquals(listOf("Bash", "writeFile"), skill.requiredTools)
        assertEquals("## 步骤\n\n1. 读模板\n2. 渲染", skill.instructions)
    }

    @Test
    fun serialize_keepsYamlValidWithSpecialChars() {
        val skill = parseSerialized(
            name = "tricky",
            description = "用于: 处理 \"引号\" 与 # 井号 的场景",
            instructions = "正文"
        )

        assertEquals("用于: 处理 \"引号\" 与 # 井号 的场景", skill.description)
        assertEquals("正文", skill.instructions)
    }

    @Test
    fun serialize_flattensMultilineDescription() {
        val skill = parseSerialized(
            name = "multi",
            description = "第一行\n第二行",
            instructions = "正文"
        )

        assertEquals("第一行 第二行", skill.description)
    }

    @Test
    fun serialize_omitsRequiredToolsWhenEmpty() {
        val text = SkillParser.serialize(
            name = "plain",
            description = "d",
            requiredTools = emptyList(),
            instructions = "正文"
        )

        assertTrue(!text.contains("required_tools"))
    }
    @Test
    fun metadata_legacyDefaultsRemainUnclassified() {
        val skill = SkillParser.parseText("---\nname: legacy\n---\nbody", "legacy")
        assertEquals(emptyList<String>(), skill.tags)
        assertEquals(null, skill.typeId)
        val form = SkillForm("legacy", "", "body")
        assertEquals(emptyList<String>(), form.tags)
        assertEquals(null, form.typeId)
    }

    @Test
    fun metadata_roundTripPreservesSpecialCharactersWithoutChangingKeywords() {
        val tags = listOf("中文: 标签 #1", "quote \" and \\ slash", "line\nbreak", "true", "[brackets], comma")
        val type = "type: \"quoted\" \\ path\n下一行"
        val serialized = SkillParser.serialize("meta", "desc", emptyList(), "specialty", true, listOf("Kotlin"), tags, type)
        val parsed = SkillParser.parseText(serialized, "meta")
        assertEquals(tags, parsed.tags)
        assertEquals(type, parsed.typeId)
        assertEquals(listOf("Kotlin"), parsed.keywords)
        assertTrue(parsed.keywordEnabled)
        assertEquals("specialty", parsed.instructions)
    }

    @Test
    fun requiredToolsRoundTripQuotesPunctuationWithoutLosingOtherMetadata() {
        val tools = listOf("Bash", "readFile", "tool:with#punctuation", "quoted\"tool")
        val text = SkillParser.serialize("resources", "desc", tools, "body", true, listOf("Kotlin"), listOf("tag"), "type")
        val parsed = SkillParser.parseText(text, "fallback")
        assertEquals(tools, parsed.requiredTools)
        assertEquals(listOf("Kotlin"), parsed.keywords)
        assertEquals(listOf("tag"), parsed.tags)
        assertEquals("type", parsed.typeId)
    }

}
