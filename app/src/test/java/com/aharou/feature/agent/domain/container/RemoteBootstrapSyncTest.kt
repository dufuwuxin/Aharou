package com.aharou.feature.agent.domain.container

import android.content.Context
import android.content.res.AssetManager
import com.aharou.feature.workspace.domain.remote.RemoteAuth
import io.mockk.*
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import net.schmizz.sshj.SSHClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RemoteBootstrapSyncTest {
    private val root = Files.createTempDirectory("remote-bootstrap").toFile()
    private val connection = mockk<RemoteSshConnection>()
    private val context = mockk<Context>()
    private val assets = mockk<AssetManager>()
    private var current = RemoteSshConnection.BootstrapTarget(mockk<SSHClient>(), "/home/one")
    private lateinit var sync: RemoteBootstrapSync

    @Before fun setup() {
        every { context.filesDir } returns root
        every { context.assets } returns assets
        every { assets.list(any()) } answers { if (firstArg<String>().contains('/')) emptyArray() else arrayOf("builtin.md") }
        every { assets.open(any()) } answers { "content".byteInputStream() }
        every { connection.bootstrapTarget() } answers { current }
        every { connection.isCurrentBootstrap(any()) } answers { firstArg<RemoteSshConnection.BootstrapTarget>() === current }
        coEvery { connection.uploadDocs(any(), any()) } returns Unit
        coEvery { connection.uploadBuiltinFiles(any(), any(), any(), any(), any()) } returns Unit
        coEvery { connection.pullSharedFiles(any(), any()) } returns true
        root.resolve("aharou-global/shared").mkdirs()
        root.resolve("aharou-global/shared/mail.md").writeText("local")
        sync = RemoteBootstrapSync(context, connection)
    }

    @After fun cleanup() { root.deleteRecursively() }

    @Test fun simultaneousCallersUploadEachStageOnlyOnce() = runTest {
        coEvery { connection.uploadDocs(any(), any()) } coAnswers { delay(50) }
        (1..20).map { async { sync.syncIfNeeded() } }.awaitAll()
        coVerify(exactly = 1) { connection.uploadDocs(any(), any()) }
        for (directory in listOf("agents", "teams", "skills", "shared")) {
            coVerify(exactly = 1) { connection.uploadBuiltinFiles(directory, any(), any(), any(), any()) }
        }
        coVerify(exactly = 1) { connection.pullSharedFiles(any(), any()) }
    }

    @Test fun failedStageRetriesWithoutRepeatingSuccessfulUploads() = runTest {
        var fail = true
        coEvery { connection.uploadDocs(any(), any()) } coAnswers { if (fail) throw IllegalStateException("transfer failed") }
        sync.syncIfNeeded()
        fail = false
        sync.syncIfNeeded()
        sync.syncIfNeeded()
        coVerify(exactly = 2) { connection.uploadDocs(any(), any()) }
        coVerify(exactly = 1) { connection.uploadBuiltinFiles("shared", any(), any(), any(), any()) }
    }

    @Test fun pullFailureDoesNotReplayAnAlreadyCompletedSharedPush() = runTest {
        coEvery { connection.pullSharedFiles(any(), any()) } returnsMany listOf(false, true)
        sync.syncIfNeeded(); sync.syncIfNeeded(); sync.syncIfNeeded()
        coVerify(exactly = 2) { connection.pullSharedFiles(any(), any()) }
        coVerify(exactly = 1) { connection.uploadBuiltinFiles("shared", any(), any(), any(), any()) }
    }

    @Test fun reconnectToTheSameHostHasANewConnectionIdentityAndResynchronizes() = runTest {
        sync.syncIfNeeded()
        val old = current
        current = RemoteSshConnection.BootstrapTarget(mockk<SSHClient>(), "/home/one")
        sync.syncIfNeeded()
        coVerify(exactly = 1) { connection.uploadDocs(any(), old) }
        coVerify(exactly = 1) { connection.uploadDocs(any(), current) }
    }

    @Test fun connectionChangeDuringUploadDoesNotMarkTheNewTargetComplete() = runTest {
        val old = current
        coEvery { connection.uploadDocs(any(), old) } coAnswers {
            current = RemoteSshConnection.BootstrapTarget(mockk<SSHClient>(), "/home/two")
        }
        sync.syncIfNeeded()
        coVerify(exactly = 0) { connection.uploadBuiltinFiles(any(), any(), any(), any(), old) }
        sync.syncIfNeeded()
        coVerify(exactly = 1) { connection.uploadDocs(any(), current) }
    }

    @Test fun cancellationPropagatesAndTheCancelledStageCanBeRetried() = runTest {
        var cancel = true
        coEvery { connection.uploadDocs(any(), any()) } coAnswers { if (cancel) throw CancellationException("cancelled") }
        try { sync.syncIfNeeded(); fail("cancellation must propagate") } catch (_: CancellationException) { }
        cancel = false
        sync.syncIfNeeded()
        coVerify(exactly = 2) { connection.uploadDocs(any(), any()) }
    }

    @Test fun alreadyConnectedCommandEntryRetriesFailedStageAndPullsOnlyOncePerCommand() = runTest {
        val cfg = RemoteConnectionConfig("example.invalid", username = "test", auth = RemoteAuth.Password("test"), remoteWorkspacePath = "/home/test/work")
        every { connection.isConnected() } returns true
        every { connection.config } returns cfg
        every { connection.setOnBeforeDisconnect(any()) } just Runs
        val resolver = mockk<ContainerProfileResolver>()
        every { resolver.forWorkspace(any()) } returns ContainerProfile(id = "remote", name = "remote",
            rootfsSource = RootfsSource.RemoteSsh("remote", "/home/test/work"), shellPath = null, isBuiltin = false, mode = com.aharou.feature.settings.data.repository.ExecutionMode.REMOTE_SSH)
        val configs = mockk<ActiveRemoteConnectionResolver>()
        coEvery { configs.configForProfile("remote") } returns cfg
        val settings = mockk<com.aharou.feature.credentials.data.CredentialInjectSettingsRepository>(relaxed = true)
        val command = mockk<net.schmizz.sshj.connection.channel.direct.Session.Command>(relaxed = true)
        every { command.inputStream } answers { byteArrayOf().inputStream() }
        every { command.errorStream } answers { byteArrayOf().inputStream() }
        every { command.exitStatus } returns 0
        every { connection.startExecSession(any()) } returns command
        val engine = RemoteSshEngine(connection, resolver, configs, sync, mockk(relaxed = true), settings)
        var fail = true
        coEvery { connection.uploadDocs(any(), any()) } coAnswers { if (fail) throw IllegalStateException("retry needed") }
        engine.runCommandSync("true", null, 1000)
        fail = false
        engine.runCommandSync("true", null, 1000)
        coVerify(exactly = 2) { connection.uploadDocs(any(), any()) }
        coVerify(exactly = 2) { connection.pullSharedFiles(any(), any()) }
        coVerify(exactly = 1) { connection.uploadBuiltinFiles("shared", any(), any(), any(), any()) }
    }

    @Test fun unreadableAssetsAreNotAcknowledgedAsSuccessfullySynchronized() = runTest {
        var broken = true
        every { assets.open("docs/builtin.md") } answers {
            if (broken) throw java.io.IOException("missing asset")
            "docs".byteInputStream()
        }
        sync.syncIfNeeded()
        coVerify(exactly = 0) { connection.uploadDocs(any(), any()) }
        broken = false; sync.syncIfNeeded()
        coVerify(exactly = 1) { connection.uploadDocs(any(), any()) }
    }
}
