package com.aharou.core.content

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.ByteArrayOutputStream

/** No Android state, network or files: both execution paths use exactly this parser. */
object ContentParser {
    const val MAX_INPUT_BYTES = 5 * 1024 * 1024
    const val MAX_OUTPUT_CHARS = 100_000
    const val MAX_RESULT_BYTES = 4 * (MAX_OUTPUT_CHARS + 64)

    fun parse(bytes: ByteArray, charset: String?, baseUri: String, html: Boolean, xml: Boolean = false): String {
        require(bytes.size <= MAX_INPUT_BYTES) { "Content input exceeds 5 MiB" }
        val parser = if (xml) Parser.xmlParser() else Parser.htmlParser()
        val doc = ByteArrayInputStream(bytes).use { Jsoup.parse(it, charset, baseUri, parser) }
        val text = if (html) {
            doc.outerHtml()
        } else {
            doc.select("script, style, iframe, nav, footer, header, noscript, .ad, .advertisement").remove()
            doc.select("p, h1, h2, h3, h4, h5, h6, li, div, br").append("\\n")
            doc.body().text().replace("\\n", "\n")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
        }
        return if (text.length > MAX_OUTPUT_CHARS) {
            text.take(MAX_OUTPUT_CHARS) + "\n\n[网页内容超长，已截断...]"
        } else text
    }

    // Preserve every UTF-16 code unit, including a surrogate cut by the existing character limit.
    internal fun encodeResult(text: String): ByteArray = ByteArray(text.length * 2) { index ->
        val code = text[index / 2].code
        (if (index % 2 == 0) code ushr 8 else code).toByte()
    }

    internal fun decodeResult(bytes: ByteArray): String {
        require(bytes.size % 2 == 0) { "Invalid content encoding" }
        return CharArray(bytes.size / 2) { index ->
            (((bytes[index * 2].toInt() and 255) shl 8) or
                (bytes[index * 2 + 1].toInt() and 255)).toChar()
        }.concatToString()
    }

    internal fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            require(output.size() + count <= limit) { "Content stream exceeds limit" }
            output.write(buffer, 0, count)
        }
    }
}
