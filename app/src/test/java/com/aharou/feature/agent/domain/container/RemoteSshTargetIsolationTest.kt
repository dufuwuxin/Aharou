package com.aharou.feature.agent.domain.container

import com.aharou.feature.workspace.domain.remote.RemoteAuth
import io.mockk.*
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import org.junit.Assert.*
import org.junit.Test

class RemoteSshTargetIsolationTest {
    private fun connection() = RemoteSshConnection(mockk(relaxed = true), mockk(), mockk(), mockk())
    private fun config(host: String) = RemoteConnectionConfig(host = host, port = 22, username = "test",
        auth = RemoteAuth.Password("fixture-only"), remoteWorkspacePath = "/srv/work")
    private fun client() = mockk<SSHClient> { every { isConnected } returns true; every { isAuthenticated } returns true }
    private fun field(connection: RemoteSshConnection, name: String, value: Any?) {
        RemoteSshConnection::class.java.getDeclaredField(name).apply { isAccessible = true }.set(connection, value)
    }
    @Test fun targetLookupCannotReturnAnotherHostsBootstrap() = runBlocking {
        val connection = connection()
        val configA = config("host-a")
        val clientA = client()
        val targetA = RemoteSshConnection.BootstrapTarget(clientA, "/home/a", configA)
        field(connection, "sshClient", clientA)
        field(connection, "config", configA)
        field(connection, "bootstrap", targetA)
        assertSame(targetA, connection.connectTarget(configA))
        val targetB = RemoteSshConnection.BootstrapTarget(client(), "/home/b", config("host-b"))
        // Simulate the old split API race: config A was checked but a host B target appeared.
        field(connection, "bootstrap", targetB)
        var rejected = false
        try { connection.connectTarget(configA) } catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)
    }
    @Test fun reconnectDuringSftpOpenClosesPinnedClientWithoutWritingToNewHost() = runBlocking {
        val connection = connection()
        val a = client()
        val b = client()
        val targetA = RemoteSshConnection.BootstrapTarget(a, "/home/a", config("host-a"))
        val targetB = RemoteSshConnection.BootstrapTarget(b, "/home/b", config("host-b"))
        field(connection, "sshClient", a)
        field(connection, "bootstrap", targetA)
        val sftp = mockk<SFTPClient>(relaxed = true)
        every { a.newSFTPClient() } answers {
            field(connection, "sshClient", b)
            field(connection, "bootstrap", targetB)
            sftp
        }
        var rejected = false
        try { connection.uploadBuiltinSkillResources(mapOf("security-audit/a.py" to byteArrayOf(1)), targetA) }
        catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)
        verify(exactly = 1) { sftp.close() }
        verify(exactly = 0) { b.newSFTPClient() }
        verify(exactly = 0) { sftp.mkdirs(any()) }
        verify(exactly = 0) { sftp.open(any<String>(), any<Set<net.schmizz.sshj.sftp.OpenMode>>()) }
    }
    @Test fun staleTargetNeverOpensSftpOnEitherHost() = runBlocking {
        val connection = connection()
        val a = client()
        val b = client()
        val targetA = RemoteSshConnection.BootstrapTarget(a, "/home/a", config("host-a"))
        field(connection, "sshClient", b)
        field(connection, "bootstrap", RemoteSshConnection.BootstrapTarget(b, "/home/b", config("host-b")))
        var rejected = false
        try { connection.uploadBuiltinSkillResources(mapOf("security-audit/a.py" to byteArrayOf(1)), targetA) }
        catch (_: IllegalStateException) { rejected = true }
        assertTrue(rejected)
        verify(exactly = 0) { a.newSFTPClient() }
        verify(exactly = 0) { b.newSFTPClient() }
    }
}
