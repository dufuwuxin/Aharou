package com.aharou.core.content

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ContentParserTest {
    private val fixture = "<html><head><title>标题</title></head><body><header>隐藏</header>" +
        "<h1>中文 &amp; text</h1><p>第一段<br>下一行</p><script>bad()</script><a href='/a'>链接</a></body></html>"

    @Test fun textMatchesExistingCleanText() {
        val doc = Jsoup.parse(fixture, "https://example.com/base")
        doc.select("script, style, iframe, nav, footer, header, noscript, .ad, .advertisement").remove()
        doc.select("p, h1, h2, h3, h4, h5, h6, li, div, br").append("\\n")
        val expected = doc.body().text().replace("\\n", "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
        assertEquals(expected, ContentParser.parse(fixture.toByteArray(), "UTF-8", "https://example.com/base", false))
    }

    @Test fun htmlMatchesOuterHtml() {
        assertEquals(Jsoup.parse(fixture, "https://example.com/base").outerHtml(),
            ContentParser.parse(fixture.toByteArray(), "UTF-8", "https://example.com/base", true))
    }

    @Test fun xmlOutputPreservesElementCaseAndDoesNotAddHtmlWrapper() {
        val parsed = ContentParser.parse("<Feed><Item /></Feed>".toByteArray(), "UTF-8", "", true, xml = true)
        assertTrue(parsed.contains("<Feed>"))
        assertTrue(parsed.contains("<Item"))
        assertFalse(parsed.contains("<html"))
    }

    @Test fun detectsChineseMetaCharset() {
        val source = "<meta charset='GBK'><p>中文编码</p>".toByteArray(charset("GBK"))
        assertTrue(ContentParser.parse(source, null, "", false).contains("中文编码"))
    }

    @Test fun contentLargerThanBinderIsBoundedOnlyAtOutput() {
        val bytes = ("<p>" + "长".repeat(600_000) + "</p>").toByteArray()
        assertTrue(bytes.size > 1024 * 1024)
        val parsed = ContentParser.parse(bytes, "UTF-8", "", false)
        assertTrue(parsed.startsWith("长".repeat(ContentParser.MAX_OUTPUT_CHARS)))
        assertTrue(parsed.endsWith("[网页内容超长，已截断...]"))
    }

    @Test fun ipcEncodingPreservesTruncatedSurrogatePair() {
        val source = ("x".repeat(99_999) + "😀").toByteArray()
        val local = ContentParser.parse(source, "UTF-8", "", false)
        assertEquals(local, ContentParser.decodeResult(ContentParser.encodeResult(local)))
    }

    @Test(expected = IllegalArgumentException::class) fun oversizedInputRejected() {
        ContentParser.parse(ByteArray(ContentParser.MAX_INPUT_BYTES + 1), null, "", false)
    }

    @Test(expected = IllegalArgumentException::class) fun oversizedStreamRejected() {
        ContentParser.readBounded(ByteArrayInputStream(ByteArray(17)), 16)
    }

    @Test fun emptyAndMalformedHtmlMatchJsoup() {
        listOf("", "<p>broken<b>bold").forEach {
            assertEquals(Jsoup.parse(it).outerHtml(), ContentParser.parse(it.toByteArray(), "UTF-8", "", true))
        }
    }
}
