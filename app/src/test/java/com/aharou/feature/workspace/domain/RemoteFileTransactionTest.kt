package com.aharou.feature.workspace.domain

import com.aharou.feature.agent.domain.container.RemoteConnectionConfig
import com.aharou.feature.agent.domain.container.RemoteSshConnection
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import com.aharou.feature.workspace.domain.remote.RemoteAuth
import io.mockk.*
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.RenameFlags
import net.schmizz.sshj.sftp.SFTPClient
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RemoteFileTransactionTest {
    private class Fixture {
        val files = mutableMapOf<String, ByteArray>()
        var current = "/srv/a"
        var rejectCommit = false
        var createTargetDuringWrite = false
        val connection = mockk<RemoteSshConnection>(relaxed = true)
        val repository = mockk<WorkspaceRepository>()
        val sftp = mockk<SFTPClient>(relaxed = true)
        val access: RemoteSftpFileAccess
        init {
            every { connection.config } returns RemoteConnectionConfig("fixture.invalid", username = "fixture",
                auth = RemoteAuth.Password("fixture-only"), remoteWorkspacePath = "/srv")
            every { connection.remoteHome } returns "/home/fixture"
            every { repository.currentPath() } answers { current }
            coEvery { connection.sftp() } returns sftp
            every { sftp.statExistence(any()) } answers {
                files[firstArg<String>()]?.let { bytes -> mockk<FileAttributes> { every { size } returns bytes.size.toLong() } }
            }
            every { sftp.open(any<String>(), any<Set<OpenMode>>()) } answers {
                val path = firstArg<String>(); val flags = secondArg<Set<OpenMode>>()
                if (OpenMode.EXCL in flags && path in files) throw IOException("fixture exclusive collision")
                if (OpenMode.TRUNC in flags || path !in files) files[path] = byteArrayOf()
                mockk<RemoteFile>(relaxed = true).also { file ->
                    every { file.write(any(), any(), any(), any()) } answers {
                        val offset = arg<Long>(0).toInt(); val bytes = arg<ByteArray>(1)
                        val start = arg<Int>(2); val count = arg<Int>(3)
                        val result = files.getValue(path).copyOf(maxOf(files.getValue(path).size, offset + count))
                        bytes.copyInto(result, offset, start, start + count); files[path] = result
                        if (createTargetDuringWrite) files["/srv/a/note.txt"] = "EXTERNAL".toByteArray()
                    }
                }
            }
            every { sftp.rename(any<String>(), any<String>()) } answers {
                val from = firstArg<String>(); val to = secondArg<String>()
                if (rejectCommit && from.contains("aicode") && !from.endsWith(".backup") && to == "/srv/a/note.txt") throw IOException("fixture failed commit")
                if (to in files) throw IOException("fixture non-overwrite rename target exists")
                val bytes = files.remove(from) ?: throw IOException("fixture missing source")
                files[to] = bytes
            }
            every { sftp.rename(any<String>(), any<String>(), any<Set<RenameFlags>>()) } answers {
                val from = firstArg<String>(); val to = secondArg<String>()
                if (rejectCommit) throw IOException("fixture failed atomic commit")
                files[to] = files.remove(from) ?: throw IOException("fixture missing source")
            }
            every { sftp.rm(any()) } answers { files.remove(firstArg<String>()); Unit }
            access = RemoteSftpFileAccess(connection, repository)
        }
    }

    @Test fun writeBytesCannotConsumeAnExistingUserSidecar() {
        val f = Fixture(); f.files["/srv/a/note.txt.aicode-part"] = "KEEP".toByteArray()
        f.access.writeBytes("note.txt", "new".toByteArray(), false)
        assertEquals("KEEP", f.files["/srv/a/note.txt.aicode-part"]?.toString(Charsets.UTF_8))
        assertEquals("new", f.files["/srv/a/note.txt"]?.toString(Charsets.UTF_8))
    }

    @Test fun writeStreamCannotConsumeAnExistingUserSidecar() {
        val f = Fixture(); f.files["/srv/a/note.txt.aicode-part"] = "KEEP".toByteArray()
        assertEquals(3L, f.access.writeStream("note.txt", "new".byteInputStream(), false))
        assertEquals("KEEP", f.files["/srv/a/note.txt.aicode-part"]?.toString(Charsets.UTF_8))
    }

    @Test fun boundAccessorCannotFollowTheNextSelectedWorkspace() {
        val f = Fixture(); val bound = f.access.forWorkspace("/srv/a"); f.current = "/srv/b"
        bound.writeBytes("~/workspace/note.txt", "bound".toByteArray(), false)
        assertEquals("bound", f.files["/srv/a/note.txt"]?.toString(Charsets.UTF_8))
        assertFalse(f.files.containsKey("/srv/b/note.txt"))
    }

    @Test fun noOverwriteBytesPreservesAnExternalFileCreatedDuringUpload() {
        val f = Fixture(); f.createTargetDuringWrite = true
        assertTrue(runCatching { f.access.writeBytes("note.txt", "mine".toByteArray(), false) }.isFailure)
        assertEquals("EXTERNAL", f.files["/srv/a/note.txt"]?.toString(Charsets.UTF_8))
    }

    @Test fun noOverwriteStreamPreservesAnExternalFileCreatedDuringUpload() {
        val f = Fixture(); f.createTargetDuringWrite = true
        assertTrue(runCatching { f.access.writeStream("note.txt", "mine".byteInputStream(), false) }.isFailure)
        assertEquals("EXTERNAL", f.files["/srv/a/note.txt"]?.toString(Charsets.UTF_8))
    }

    @Test fun failedReplacementPreservesTheOldTarget() {
        val f = Fixture(); f.files["/srv/a/note.txt"] = "OLD".toByteArray(); f.rejectCommit = true
        assertTrue(runCatching { f.access.writeBytes("note.txt", "new".toByteArray(), true) }.isFailure)
        assertEquals("OLD", f.files["/srv/a/note.txt"]?.toString(Charsets.UTF_8))
    }
}
