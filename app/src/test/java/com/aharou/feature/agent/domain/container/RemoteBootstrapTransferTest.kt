package com.aharou.feature.agent.domain.container

import io.mockk.*
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import org.junit.Assert.*
import org.junit.Test

class RemoteBootstrapTransferTest {
    private fun fixture(failWrites: Boolean = false, blocked: java.util.concurrent.CountDownLatch? = null): Triple<RemoteSshConnection, RemoteSshConnection.BootstrapTarget, MutableList<String>> {
        val connection = RemoteSshConnection(mockk(), mockk(), mockk(), mockk())
        val client = mockk<SSHClient>()
        every { client.isConnected } returns true
        every { client.isAuthenticated } returns true
        val target = RemoteSshConnection.BootstrapTarget(client, "/home/test")
        for ((field, value) in listOf("sshClient" to client, "bootstrap" to target)) {
            RemoteSshConnection::class.java.getDeclaredField(field).apply { isAccessible = true }.set(connection, value)
        }
        val commands = java.util.Collections.synchronizedList(mutableListOf<String>())
        every { client.startSession() } answers {
            val session = mockk<Session>(relaxed = true)
            every { session.exec(any()) } answers {
                val command = firstArg<String>(); commands += command
                mockk<Session.Command>(relaxed = true).also {
                    every { it.outputStream } returns ByteArrayOutputStream()
                    every { it.exitStatus } returns if (failWrites && command.contains("printf")) 1 else 0
                    every { it.join() } answers {
                        if (blocked != null && command.contains("printf")) { blocked.countDown(); Thread.sleep(60_000) }
                    }
                }
            }
            session
        }
        return Triple(connection, target, commands)
    }

    @Test fun documentsAreCleanedOnlyAfterAllNewContentsWereWritten() = runBlocking {
        val (connection, target, commands) = fixture()
        connection.uploadDocs(mapOf("guide/a.md" to "new".toByteArray(), "b.md" to "second".toByteArray()), target)
        assertEquals(2, commands.count { "printf" in it })
        assertTrue(commands.last().contains("-delete"))
        assertTrue(commands.last().contains("! -path '/home/test/.aharou/docs/guide/a.md'"))
        assertTrue(commands.dropLast(1).none { "-delete" in it })
    }

    @Test fun failedRemoteWriteLeavesOldDocumentsAndReportsFailure() = runBlocking {
        val (connection, target, commands) = fixture(failWrites = true)
        try { connection.uploadDocs(mapOf("a.md" to "new".toByteArray()), target); fail("nonzero remote exit must fail") }
        catch (expected: IllegalStateException) { assertEquals("REMOTE_ASSET_WRITE_FAILED", expected.message) }
        assertTrue(commands.none { "-delete" in it })
    }

    @Test fun cancellingAnActualBlockedJoinDoesNotContinueToDocumentCleanup() = runBlocking {
        val blocked = java.util.concurrent.CountDownLatch(1)
        val (connection, target, commands) = fixture(blocked = blocked)
        val job = launch(Dispatchers.Default) { connection.uploadDocs(mapOf("a.md" to "new".toByteArray()), target) }
        try { assertTrue(blocked.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
        finally { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertTrue(commands.none { "-delete" in it })
    }

    @Test fun staleConnectionIdentityCannotStartAnUploadOnANewConnection() = runBlocking {
        val (connection, _, commands) = fixture()
        val stale = RemoteSshConnection.BootstrapTarget(mockk(), "/home/old")
        try { connection.uploadDocs(mapOf("a.md" to byteArrayOf(1)), stale); fail("stale connection must fail") }
        catch (expected: IllegalStateException) { assertEquals("SSH_CONNECTION_CHANGED", expected.message) }
        assertTrue(commands.isEmpty())
        RemoteSshConnection::class.java.getDeclaredField("remoteHome").apply { isAccessible = true }.set(connection, "/home/test")
        connection.disconnect()
        assertEquals("/home/test", connection.remoteHome)
        assertNull(connection.bootstrapTarget())
    }
}
