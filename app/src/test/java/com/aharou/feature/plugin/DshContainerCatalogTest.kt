package com.aharou.feature.plugin

import com.aharou.feature.agent.domain.container.CommandResult
import com.aharou.feature.agent.domain.container.LinuxContainerEngine
import com.aharou.feature.plugin.domain.market.DshContainerCatalog
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DshContainerCatalogTest {
    private fun encoded(text: String) = Base64.getMimeEncoder(76, byteArrayOf(10)).encodeToString(text.toByteArray()) + "\n"

    @Test fun noReadyContainerDoesNotExecuteOrInstallAnything() = runBlocking {
        var calls = 0
        val catalog = DshContainerCatalog(ready = { false }, execute = { calls++; error("unexpected") })
        assertEquals("dsh_container_not_ready", runCatching { catalog.page(1) }.exceptionOrNull()?.message)
        assertEquals(0, calls)
    }
    @Test fun productionAdapterOnlyUsesReadyUnboundedReadWithoutWorkspace() = runBlocking {
        val engine = mockk<LinuxContainerEngine>()
        every { engine.isContainerInstalled() } returns true
        every { engine.notReadyHint() } returns null
        coEvery { engine.runCommandSyncUnbounded(any(), null, 30_000L) } returns CommandResult(encoded("<html>目录</html>"), 0)
        assertEquals("<html>目录</html>", DshContainerCatalog(engine).page(1))
        coVerify(exactly = 1) { engine.runCommandSyncUnbounded(any(), null, 30_000L) }
        coVerify(exactly = 0) { engine.ensureInstalled() }
    }
    @Test fun rejectsNoCurlNonzeroMissingExitTruncationAndMalformedOutput() {
        listOf(
            CommandResult("curl: not found", 127), CommandResult(encoded("partial"), 22),
            CommandResult(encoded("partial"), null), CommandResult(encoded("partial"), 0, true),
            CommandResult("", 0), CommandResult("%%%%", 0), CommandResult("abc", 0),
            CommandResult("\uFFFD\uFFFD\uFFFD\uFFFD", 0),
        ).forEach { result -> assertTrue(result.toString(), runCatching { DshContainerCatalog.decode(result) }.isFailure) }
    }
    @Test fun refusesOversizedCatalogInsteadOfAcceptingPartialData() {
        val bytes = ByteArray(DshContainerCatalog.MAX_BYTES + 1) { 65 }
        val result = CommandResult(Base64.getEncoder().encodeToString(bytes), 0)
        assertEquals("dsh_container_too_large", runCatching { DshContainerCatalog.decode(result) }.exceptionOrNull()?.message)
    }
    @Test fun largestAcceptedPayloadFitsTheActualEngineTransportBudget() {
        val html = "a".repeat(DshContainerCatalog.MAX_BYTES)
        val output = encoded(html)
        assertTrue(output.length <= com.aharou.feature.agent.domain.container.MAX_UNBOUNDED_CHARS)
        assertEquals(html, DshContainerCatalog.decode(CommandResult(output, 0)))
    }
    @Test fun handlesSingleLineHtmlViaFoldedBase64WithoutChangingBytes() {
        val html = "<html>" + "目录".repeat(100_000) + "</html>"
        assertEquals(html, DshContainerCatalog.decode(CommandResult(encoded(html), 0)))
    }
    @Test fun commandIsFixedHttpsBoundedDirectAndDoesNotFollowRedirectsOrLoadCurlConfig() {
        val command = DshContainerCatalog.command(2)
        assertTrue(command.contains("curl -q --fail"))
        assertTrue(command.contains("--proto '=https'"))
        assertTrue(command.contains("--proxy '' --noproxy '*'"))
        assertTrue(command.contains("--connect-timeout 10 --max-time 25 --max-filesize ${DshContainerCatalog.MAX_BYTES}"))
        assertTrue(command.contains("https://dpharness.com/plugins?page=2"))
        assertTrue(command.contains("&& base64 --wrap=76"))
        assertTrue(command.contains("trap 'rm -f -- \"/tmp/aharou-dsh-"))
        assertFalse(command.contains("--location"))
        assertFalse(command.contains("--insecure"))
        assertFalse(command.contains("rm -rf"))
        assertTrue(runCatching { DshContainerCatalog.command(0) }.isFailure)
        assertTrue(runCatching { DshContainerCatalog.command(10001) }.isFailure)
    }
}
