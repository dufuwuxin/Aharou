package com.aharou.feature.editor.lsp

import com.aharou.feature.agent.domain.container.RemoteSshConnection
import com.aharou.feature.agent.domain.container.SshCommandProcess
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RemoteLspConnectionProviderTest {
    @Test fun pathsAndArgumentsAreQuotedWithoutLocalFallback() {
        assertEquals("cd '/srv/a'\"'\"' b' && exec 'server' '--stdio' 'x'\"'\"'y'",
            RemoteLspConnectionProvider.command("/srv/a' b", "server", listOf("--stdio", "x'y")))
        assertEquals("/srv/work/42", RemoteLspConnectionProvider.remoteRoot("/local/projects/42", "/srv/work"))
        assertEquals("/srv/work/42", RemoteLspConnectionProvider.remoteRoot("/srv/work/42", "/srv/work"))
        assertEquals("/srv/work/42/a.kt", RemoteLspConnectionProvider.filePath("~/workspace/a.kt", "/srv/work/42", "/local/42", "/home/u"))
        assertThrows(IllegalArgumentException::class.java) { RemoteLspConnectionProvider.remoteRoot("/local/42", "") }
        assertThrows(IllegalArgumentException::class.java) { RemoteLspConnectionProvider.filePath("/other/a", "/srv/42", "/local/42", "/home/u") }
    }
    @Test fun remoteTransportReusesProcessAndClosesOnExitOrHostChange() {
        val connection = mockk<RemoteSshConnection>()
        val target = mockk<RemoteSshConnection.BootstrapTarget>()
        val child = mockk<SshCommandProcess>()
        val done = CountDownLatch(1)
        val input = ByteArrayInputStream(byteArrayOf(1, 2))
        val output = ByteArrayOutputStream()
        every { connection.isCurrentBootstrap(target) } returns true
        every { connection.startProcess(any(), target) } returns child
        every { child.inputStream } returns input
        every { child.outputStream } returns output
        every { child.errorStream } returns ByteArrayInputStream(byteArrayOf())
        every { child.waitFor() } answers { done.await(3, TimeUnit.SECONDS); 0 }
        every { child.destroy() } just Runs
        val provider = RemoteLspConnectionProvider(connection, target, "lsp:test", "/srv/42", "server", listOf("--stdio"))
        try {
            provider.start()
            assertSame(input, provider.inputStream)
            assertSame(output, provider.outputStream)
            verify(exactly = 1) { connection.startProcess("cd '/srv/42' && exec 'server' '--stdio'", target) }
            every { connection.isCurrentBootstrap(target) } returns false
            assertTrue(provider.isClosed)
            provider.close()
            provider.close()
            verify(exactly = 1) { child.destroy() }
        } finally { done.countDown(); provider.close() }
    }
    @Test fun changedHostNeverStartsAProcess() {
        val connection = mockk<RemoteSshConnection>()
        val target = mockk<RemoteSshConnection.BootstrapTarget>()
        every { connection.isCurrentBootstrap(target) } returns false
        val provider = RemoteLspConnectionProvider(connection, target, "lsp:test", "/srv", "server", emptyList())
        assertThrows(IOException::class.java) { provider.start() }
        verify(exactly = 0) { connection.startProcess(any(), any()) }
        assertTrue(provider.isClosed)
    }
}
