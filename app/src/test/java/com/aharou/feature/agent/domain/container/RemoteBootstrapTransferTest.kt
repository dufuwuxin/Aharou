package com.aharou.feature.agent.domain.container

import io.mockk.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.RenameFlags
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import org.junit.Assert.*
import org.junit.Test

class RemoteBootstrapTransferTest {
    // Keep the byte-backed state of MemoryAccess, but exercise the real SftpAccess adapter.
    private class MemorySftp {
        val files = linkedMapOf<String, ByteArray>()
        val events = mutableListOf<String>()
        var failWrites = false
        var corruptReads = false
        var afterWrite: () -> Unit = {}
        val client = mockk<SFTPClient>()
        private val attrs = mockk<FileAttributes> {
            every { type } returns FileMode.Type.REGULAR
        }

        init {
            every { client.close() } just Runs
            every { client.lstat(any()) } answers {
                if (firstArg<String>() in files) attrs else throw missing(firstArg())
            }
            every { client.statExistence(any()) } answers { if (firstArg<String>() in files) attrs else null }
            every { client.mkdirs(any()) } just Runs
            every { client.open(any<String>()) } answers {
                val path = firstArg<String>()
                val bytes = files.getValue(path).let { if (corruptReads) "corrupt".toByteArray() else it.copyOf() }
                events += "open-read:$path"
                mockk<RemoteFile>().also { file ->
                    every { file.close() } just Runs
                    every { file.read(any(), any(), any(), any()) } answers {
                        val offset = firstArg<Long>().toInt()
                        val buffer = secondArg<ByteArray>()
                        val start = thirdArg<Int>()
                        val count = minOf(arg<Int>(3), 3, bytes.size - offset)
                        events += "read:$path:$offset"
                        if (count <= 0) -1 else {
                            bytes.copyInto(buffer, start, offset, offset + count)
                            count
                        }
                    }
                }
            }
            every { client.open(any<String>(), any<Set<OpenMode>>()) } answers {
                val path = firstArg<String>()
                assertEquals(setOf(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL), secondArg<Set<OpenMode>>())
                assertTrue(path.contains(".aharou-") && path.endsWith(".tmp"))
                check(path !in files) { "exclusive temporary already exists" }
                files[path] = byteArrayOf()
                events += "open-write:$path"
                mockk<RemoteFile>().also { file ->
                    every { file.close() } answers { events += "close-write:$path" }
                    every { file.write(any(), any(), any(), any()) } answers {
                        assertEquals(0L, firstArg<Long>())
                        val bytes = secondArg<ByteArray>()
                        val start = thirdArg<Int>()
                        val size = arg<Int>(3)
                        files[path] = bytes.copyOfRange(start, start + if (failWrites) minOf(1, size) else size)
                        events += "write:$path"
                        check(!failWrites) { "fixture write failure" }
                        afterWrite()
                    }
                }
            }
            every { client.rename(any(), any(), any<Set<RenameFlags>>()) } answers {
                val source = firstArg<String>()
                val target = secondArg<String>()
                assertEquals(setOf(RenameFlags.OVERWRITE), thirdArg<Set<RenameFlags>>())
                assertTrue(source.startsWith("$target.aharou-"))
                assertTrue("close-write:$source" in events)
                files[target] = files.remove(source) ?: throw missing(source)
                events += "rename:$target"
            }
            every { client.rm(any()) } answers {
                val path = firstArg<String>()
                assertTrue("cleanup must never delete a document", path.contains(".aharou-") && path.endsWith(".tmp"))
                events += "rm:$path"
                if (files.remove(path) == null) throw missing(path)
            }
        }

        private fun missing(path: String) = SFTPException(Response.StatusCode.NO_SUCH_FILE, path)
        fun receipts() = files.keys.filter { "/builtin-seed-state/" in it }
        fun assertNoTemporaryFiles() = assertTrue(files.keys.none { it.endsWith(".tmp") })
    }

    private data class Fixture(
        val connection: RemoteSshConnection,
        val target: RemoteSshConnection.BootstrapTarget,
        val sftp: MemorySftp,
        val commands: MutableList<String>
    )

    private fun fixture(blocked: CountDownLatch? = null, interrupted: CountDownLatch? = null): Fixture {
        val connection = RemoteSshConnection(mockk(), mockk(), mockk(), mockk())
        val client = mockk<SSHClient>()
        val sftp = MemorySftp()
        every { client.isConnected } returns true
        every { client.isAuthenticated } returns true
        every { client.newSFTPClient() } returns sftp.client
        every { client.disconnect() } just Runs
        val target = RemoteSshConnection.BootstrapTarget(client, "/home/test", mockk())
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
                    every { it.exitStatus } returns 0
                    every { it.join() } answers {
                        if (blocked != null && command.contains("printf")) {
                            blocked.countDown()
                            try { Thread.sleep(60_000) }
                            catch (error: InterruptedException) { interrupted?.countDown(); throw error }
                        }
                    }
                }
            }
            session
        }
        return Fixture(connection, target, sftp, commands)
    }

    @Test fun documentsPublishReceiptOnlyAfterAllWritesAndPreserveUnmanagedFiles() = runBlocking {
        val (connection, target, sftp, commands) = fixture()
        val root = "/home/test/.aharou"
        val old = mapOf("$root/docs/custom.md" to "custom", "$root/docs/old.md" to "user modified old document")
        old.forEach { (path, text) -> sftp.files[path] = text.toByteArray() }
        sftp.files["$root/docs/guide/a.md"] = "previous seed".toByteArray()
        val docs = linkedMapOf("guide/a.md" to "new".toByteArray(), "b.md" to "second".toByteArray())
        connection.uploadDocs(docs, target)
        docs.forEach { (name, bytes) -> assertArrayEquals(bytes, sftp.files.getValue("$root/docs/$name")) }
        old.forEach { (path, text) -> assertEquals(text, sftp.files.getValue(path).toString(Charsets.UTF_8)) }
        val receipt = sftp.receipts().single()
        assertEquals(listOf("rename:$root/docs/guide/a.md", "rename:$root/docs/b.md", "rename:$receipt"),
            sftp.events.filter { it.startsWith("rename:") })
        assertEquals(docs.keys, Json.parseToJsonElement(sftp.files.getValue(receipt).toString(Charsets.UTF_8))
            .jsonObject.getValue("files").jsonObject.keys)
        assertTrue(sftp.events.any { it == "open-read:$root/docs/guide/a.md" })
        sftp.assertNoTemporaryFiles()
        assertEquals(3, sftp.events.count { it.startsWith("rm:") })
        val writes = sftp.events.count { it.startsWith("write:") }
        sftp.files["$root/docs/guide/a.md"] = "same-version user edit".toByteArray()
        connection.uploadDocs(docs, target)
        assertEquals("same-version user edit", sftp.files.getValue("$root/docs/guide/a.md").toString(Charsets.UTF_8))
        assertTrue(sftp.events.any { it == "open-read:$receipt" })
        assertEquals(writes, sftp.events.count { it.startsWith("write:") })
        assertTrue(commands.isEmpty())
    }

    @Test fun failedRemoteWriteLeavesOldDocumentsWithoutReceiptAndCanRetry() = runBlocking {
        val (connection, target, sftp, commands) = fixture()
        val path = "/home/test/.aharou/docs/a.md"
        sftp.files[path] = "old document".toByteArray()
        sftp.failWrites = true
        try { connection.uploadDocs(mapOf("a.md" to "new".toByteArray()), target); fail("partial SFTP write must fail") }
        catch (expected: IllegalStateException) { assertEquals("fixture write failure", expected.message) }
        assertEquals("old document", sftp.files.getValue(path).toString(Charsets.UTF_8))
        assertTrue(sftp.receipts().isEmpty())
        assertTrue(sftp.events.none { it.startsWith("rename:") })
        assertEquals(1, sftp.events.count { it.startsWith("rm:") })
        sftp.assertNoTemporaryFiles()
        sftp.failWrites = false
        connection.uploadDocs(mapOf("a.md" to "new".toByteArray()), target)
        assertEquals("new", sftp.files.getValue(path).toString(Charsets.UTF_8))
        assertEquals(1, sftp.receipts().size)
        sftp.assertNoTemporaryFiles()
        assertTrue(commands.isEmpty())
    }

    @Test fun sftpReadbackMismatchDoesNotPublishReceiptAndCanRetry() {
        val sftp = MemorySftp()
        val store = RemoteBuiltinAssetStore(RemoteBuiltinAssetStore.SftpAccess(sftp.client, "/home/test/.aharou") {}, 1)
        val files = mapOf("run.py" to "trusted script".toByteArray())
        sftp.corruptReads = true
        try { store.deploy("builtin-skills/check", files, requireExact = true); fail("readback mismatch must fail") }
        catch (expected: IllegalStateException) { assertEquals("BUILTIN_SKILL_RESOURCES_CHANGED", expected.message) }
        assertTrue(sftp.events.any { it == "open-read:/home/test/.aharou/builtin-skills/check/run.py" })
        assertTrue(sftp.receipts().isEmpty())
        sftp.assertNoTemporaryFiles()
        sftp.corruptReads = false
        store.deploy("builtin-skills/check", files, requireExact = true)
        assertEquals(1, sftp.receipts().size)
        assertEquals(1, sftp.events.count { it == "rename:/home/test/.aharou/builtin-skills/check/run.py" })
        sftp.assertNoTemporaryFiles()
    }

    // Documents now use SFTP; the non-managed builtin uploader still owns the interruptible exec/join path.
    @Test fun cancellingAnActualBlockedJoinDoesNotContinueToTheNextBuiltinWrite() = runBlocking {
        val blocked = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val (connection, target, sftp, commands) = fixture(blocked, interrupted)
        val job = launch(Dispatchers.Default) {
            connection.uploadBuiltinFiles("scripts", linkedMapOf("a.sh" to "first".toByteArray(), "b.sh" to "second".toByteArray()), target = target)
        }
        try { assertTrue(blocked.await(10, TimeUnit.SECONDS)) }
        finally { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertTrue("blocked join must be interrupted, not merely marked cancelled", interrupted.await(1, TimeUnit.SECONDS))
        assertEquals(1, commands.count { "printf" in it })
        assertTrue(commands.any { "a.sh" in it && "printf" in it })
        assertTrue(commands.none { "b.sh" in it || "-delete" in it })
        assertTrue(sftp.files.isEmpty())
        verify(exactly = 0) { target.client.newSFTPClient() }
    }

    @Test fun connectionChangedDuringSftpWriteCannotPublishOrRecordSuccess() = runBlocking {
        val (connection, target, sftp, _) = fixture()
        val replacement = mockk<SSHClient>()
        val path = "/home/test/.aharou/docs/a.md"
        sftp.files[path] = "old".toByteArray()
        sftp.afterWrite = {
            val newTarget = RemoteSshConnection.BootstrapTarget(replacement, "/home/new", mockk())
            for ((field, value) in listOf("sshClient" to replacement, "bootstrap" to newTarget)) {
                RemoteSshConnection::class.java.getDeclaredField(field).apply { isAccessible = true }.set(connection, value)
            }
        }
        try { connection.uploadDocs(mapOf("a.md" to "new".toByteArray()), target); fail("changed connection must fail before rename") }
        catch (expected: IllegalStateException) { assertEquals("SSH_CONNECTION_CHANGED", expected.message) }
        assertEquals("old", sftp.files.getValue(path).toString(Charsets.UTF_8))
        assertTrue(sftp.events.none { it.startsWith("rename:") })
        assertTrue(sftp.receipts().isEmpty())
        assertEquals(1, sftp.events.count { it.startsWith("rm:") })
        sftp.assertNoTemporaryFiles()
        verify { replacement wasNot Called }
    }

    @Test fun staleConnectionIdentityCannotStartAnUploadOnANewConnection() = runBlocking {
        val (connection, target, sftp, commands) = fixture()
        val stale = RemoteSshConnection.BootstrapTarget(mockk(), "/home/old", mockk())
        try { connection.uploadDocs(mapOf("a.md" to byteArrayOf(1)), stale); fail("stale connection must fail") }
        catch (expected: IllegalStateException) { assertEquals("SSH_CONNECTION_CHANGED", expected.message) }
        assertTrue(commands.isEmpty())
        assertTrue(sftp.files.isEmpty())
        verify(exactly = 0) { target.client.newSFTPClient() }
        verify(exactly = 0) { stale.client.newSFTPClient() }
        RemoteSshConnection::class.java.getDeclaredField("remoteHome").apply { isAccessible = true }.set(connection, "/home/test")
        connection.disconnect()
        assertEquals("/home/test", connection.remoteHome)
        assertNull(connection.bootstrapTarget())
    }
}
