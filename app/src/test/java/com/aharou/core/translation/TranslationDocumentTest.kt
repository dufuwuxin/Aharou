package com.aharou.core.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationDocumentTest {
    @org.junit.Test fun multilineEmbeddedJsonIsNotTranslated() {
        val text = "Use this JSON:\n[\n  true,\n  false\n]\n"
        val parts = TranslationDocument.parts(text, "zh")
        org.junit.Assert.assertEquals(text, parts.joinToString("") { it.text })
        org.junit.Assert.assertTrue(parts.filter { it.translate }.none { "true" in it.text || "false" in it.text })
    }

    @org.junit.Test fun embeddedJsonWithWhitespaceAndNestedObjectsStaysByteExact() {
        for (text in listOf("Use { \"message\": \"Hello world\" } here", "Use {\"a\":{\"x\":1},\"label\":\"Keep raw\"} now", "数组 [1, {\"label\":\"原样\"}] 保留")) {
            val parts = TranslationDocument.parts(text, "zh")
            org.junit.Assert.assertEquals(text, parts.joinToString("") { it.text })
            org.junit.Assert.assertTrue(parts.none { it.translate })
        }
    }

    @Test fun splittingNeverChangesOriginalBytes() {
        listOf(
            "", "Hello world\r\n第二行 Test words\n", "  Hello   world  ",
            "# Overview\n- Use `git status` now.\n", "~~~kotlin\nval name = 1\n~~~\nDone",
            "```python\nprint('English')\n", "    English code\n\tmore code",
            "[Read documentation](https://example.com/English) 中文描述",
            "{{AICODE_PATH}} \u0024{HOME} %1\u0024s /root/shared ~/workspace ./test.py --dry-run",
            "Hello 😀 world " + "word ".repeat(200),
        ).forEach { source ->
            assertEquals(source, TranslationDocument.parts(source).joinToString("") { it.text })
        }
    }

    @Test fun fencedAndIndentedCodeNeverGoesToTranslator() {
        val source = "```kotlin\nval answer = getAnswer()\n```\n    print hello\nHello world"
        assertEquals(listOf("Hello world"), TranslationDocument.parts(source).filter { it.translate }.map { it.text })
    }

    @Test fun unclosedFenceAndInlineCodeAreProtected() {
        assertFalse(TranslationDocument.parts("```text\nNever translate this").any { it.translate })
        assertFalse(TranslationDocument.parts("`never translate this").any { it.translate })
    }

    @Test fun symbolsPathsUrlsAndPlaceholdersNeverGoToTranslator() {
        val source = "Use `git status` with getAnswer and task_id at /root/project or https://example.com/docs and {{AICODE_PATH}} \u0024{HOME} %1\u0024s --dry-run user@example.com"
        val inputs = TranslationDocument.parts(source).filter { it.translate }.joinToString("|") { it.text }
        listOf("git", "getAnswer", "task_id", "/root", "example.com", "AICODE_PATH", "HOME", "dry-run").forEach {
            assertFalse("protected value leaked: $it", inputs.contains(it))
        }
        assertTrue(inputs.contains("Use"))
    }

    @Test fun linkLabelsTranslateButTargetsRemainUnchanged() {
        val source = "[Read documentation](https://example.com/English)"
        val parts = TranslationDocument.parts(source)
        assertTrue(parts.any { it.translate && it.text == "Read documentation" })
        assertTrue(parts.any { !it.translate && it.text.contains("https://example.com/English") })
    }

    @Test fun chineseIsPreservedAndEnglishPhrasesAreSelected() {
        val parts = TranslationDocument.parts("中文 Hello world，继续 Keep going。")
        assertEquals(listOf("Hello world", "Keep going"), parts.filter { it.translate }.map { it.text })
        assertFalse(TranslationDocument.parts("纯中文说明").any { it.translate })
    }

    @Test fun relativeFilesBracesAndMultiBacktickCodeSurviveTranslation() {
        val protected = listOf("src/main.kt", "README.md", "{name}", "{0}", "``use `raw` text``", "\u0024HOME")
        protected.forEach { original ->
            val source = "Read $original carefully"
            val translated = TranslationDocument.parts(source).joinToString("") {
                if (it.translate) "译文" else it.text
            }
            assertTrue("changed protected value: $original", translated.contains(original))
            assertFalse(TranslationDocument.parts(original).any { it.translate })
        }
    }

    @Test fun longParagraphsAreChunkedWithoutDroppingWords() {
        val text = List(140) { "word" }.joinToString(" ")
        val parts = TranslationDocument.parts(text)
        assertEquals(text, parts.joinToString("") { it.text })
        assertEquals(3, parts.count { it.translate })
        assertTrue(parts.filter { it.translate }.all { it.text.split(' ').size <= 64 })
    }
    @Test fun chineseDirectionPreservesCodePathsAndPlaceholders() {
        val source = "请检查 `保存代码()` 和 /tmp/中文.txt，再打开 https://example.com/a 与 {{name}}。\n```kotlin\nval x = \"中文代码\"\n```"
        val parts = TranslationDocument.parts(source, "en")
        assertEquals(source, parts.joinToString("") { it.text })
        assertTrue(parts.any { it.translate && it.text.contains("请检查") })
        assertFalse(parts.filter { it.translate }.any { it.text.contains("保存代码") || it.text.contains("中文代码") || it.text.contains("/tmp/") || it.text.contains("example.com") || it.text.contains("{{") })
    }
    @Test fun chineseChunksHaveBoundedInferenceInput() {
        val source = "中文说明".repeat(1000)
        val parts = TranslationDocument.parts(source, "en")
        assertEquals(source, parts.joinToString("") { it.text })
        assertTrue(parts.filter { it.translate }.all { it.text.length <= 192 })
    }
    @Test fun credentialLinesAndJsonStringsStayVerbatim() {
        for (source in listOf("Authorization: Bearer opaque", "api_key=opaque", "\"Hello world\"", "{\"message\":\"中文\"}")) {
            for (target in listOf("en", "zh")) assertFalse(TranslationDocument.parts(source, target).any { it.translate })
        }
    }

}
