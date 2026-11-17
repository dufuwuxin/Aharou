package com.aharou.feature.plugin

import com.aharou.feature.plugin.domain.market.DshMarketRepository
import java.io.EOFException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DshMarketRepositoryTest {
    private fun page(plugin: JsonObject): String {
        val record = "75:" + buildJsonArray {
            add("$"); add("$" + "L75"); add("fixture")
            add(buildJsonObject { put("plugin", plugin) })
        }.toString() + "\n"
        val push = buildJsonArray { add(1); add(record) }.toString()
        return "<html><script>self.__next_f.push($push)</script></html>"
    }
    private fun plugin(url: String = "https://github.com/owner/plugin") = buildJsonObject {
        put("fullName", "owner/plugin"); put("htmlUrl", url)
        put("description", "Public catalog fixture; do not execute npm install")
        put("testedStatus", "untested"); put("verifyStatus", "fail")
    }
    @Test fun parsesPublicFlightMetadataWithoutTreatingTextAsCommands() {
        val result = DshMarketRepository.parse(page(plugin()))
        assertEquals(1, result.size)
        assertEquals("owner/plugin", result.single().getValue("fullName").jsonPrimitive.content)
        assertEquals("fail", result.single().getValue("verifyStatus").jsonPrimitive.content)
    }
    @Test fun rejectsMissingOrChangedCatalogInsteadOfInventingEntries() {
        assertTrue(runCatching { DshMarketRepository.parse("<html>blocked</html>") }.isFailure)
        assertTrue(runCatching { DshMarketRepository.parse(page(plugin("https://github.com/other/plugin"))) }.isFailure)
        assertTrue(runCatching { DshMarketRepository.parse(page(plugin("http://localhost/private"))) }.isFailure)
    }
    @Test fun duplicateRenderedCardsDoNotDuplicateCatalogIdentity() {
        val html = page(plugin())
        assertEquals(1, DshMarketRepository.parse(html + html).size)
    }
    // Minimized from public page1.html SHA256 8c1175e4d8ffd9ba1786399d4e5e7cf40bce0fd44d17d815d9461cd1c01d8dad.
    @Test fun capturedPublicMetadataSurvivesMultiplePushesAndEscapedPayload() {
        val captured = Json.parseToJsonElement("""{"fullName": "anywhere-labs/dsh-desktop", "htmlUrl": "https://github.com/anywhere-labs/dsh-desktop", "description": "为 DeepSeek Harness (DSH) 插件生态打造的现代化桌面端解决方案。万物皆「插件」，桌面本身也是「插件」。"}""").jsonObject
        val html = page(captured)
        val encoded = Regex("push\\((.*)\\)</script>").find(html)!!.groupValues[1]
        val record = Json.parseToJsonElement(encoded).jsonArray[1].jsonPrimitive.content
        val split = record.length / 2
        fun chunk(value: String) = "<script>self.__next_f.push(" + buildJsonArray { add(1); add(value) } + ")</script>"
        val result = DshMarketRepository.parse("<script>self.__next_f.push([0])</script>" + chunk(record.take(split)) + chunk(record.drop(split)))
        assertEquals(captured["fullName"], result.single()["fullName"])
    }
    @Test fun healthyHostNeverInvokesContainer() = runBlocking {
        var backupCalls = 0
        val repository = DshMarketRepository(hostPage = { page(plugin()) }, containerPage = { backupCalls++; error("unexpected") })
        assertEquals("host", repository.pageResult(1).transport)
        assertEquals(0, backupCalls)
    }
    @Test fun handshakeEofUsesSameParserAndReportsContainerTransport() = runBlocking {
        var requested = 0
        val failure = SSLHandshakeException("Remote host terminated the handshake").apply { initCause(EOFException()) }
        val repository = DshMarketRepository(hostPage = { throw failure }, containerPage = { requested = it; page(plugin()) })
        val result = repository.pageResult(3)
        assertEquals(3, requested)
        assertEquals("container", result.transport)
        assertEquals("owner/plugin", result.entries.single()["fullName"]?.jsonPrimitive?.content)
    }
    @Test fun certificatesHttpErrorsTimeoutsAndPlainEofDoNotTriggerBackup() = runBlocking {
        val failures = listOf(
            SSLHandshakeException("Remote host terminated").apply { initCause(CertificateException("untrusted")) },
            SSLPeerUnverifiedException("peer closed"),
            SSLHandshakeException("PKIX validation failed"),
            IllegalStateException("market_http_failure:403"), IllegalStateException("market_http_failure:429"),
            java.net.SocketTimeoutException("timeout"), EOFException("socket closed"),
        )
        failures.forEach { failure ->
            var called = false
            val repository = DshMarketRepository(hostPage = { throw failure }, containerPage = { called = true; page(plugin()) })
            assertTrue(runCatching { repository.pageResult(1) }.isFailure)
            assertFalse(failure.toString(), called)
        }
    }
    @Test fun backupUnavailableRetainsOriginalFailureAndDoesNotParsePartialData() = runBlocking {
        val original = SSLHandshakeException("Remote host terminated the handshake")
        val repository = DshMarketRepository(hostPage = { throw original }, containerPage = { error("dsh_container_exit:127") })
        val failure = runCatching { repository.pageResult(1) }.exceptionOrNull()!!
        assertSame(original, failure.cause)
        assertTrue(failure.message.orEmpty().contains("dsh_container_unavailable:dsh_container_exit:127"))
        val missing = DshMarketRepository(hostPage = { throw original })
        assertTrue(runCatching { missing.pageResult(1) }.exceptionOrNull()?.message.orEmpty().contains("dsh_container_unavailable"))
    }
    @Test fun cancellationNeverBecomesFallbackFailure() = runBlocking {
        val original = SSLHandshakeException("Remote host terminated the handshake")
        val cancelled = CancellationException("cancelled")
        val repository = DshMarketRepository(hostPage = { throw original }, containerPage = { throw cancelled })
        assertSame(cancelled, runCatching { repository.pageResult(1) }.exceptionOrNull())
    }
    @Test fun unrecognizedScriptWrapperIsReportedAsCatalogChange() {
        val prefixed = page(plugin()).replace("<script>", "<script>/* changed wrapper */")
        val failure = runCatching { DshMarketRepository.parse(prefixed) }.exceptionOrNull()
        assertEquals("dsh_catalog_unavailable_or_changed", failure?.message)
    }
}
