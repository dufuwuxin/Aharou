package com.aharou.feature.plugin

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.CommandResult
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.agent.domain.container.LinuxContainerEngine
import com.aharou.feature.agent.domain.container.RuntimeProcessStore
import com.aharou.feature.plugin.data.PluginRecord
import com.aharou.feature.plugin.data.PluginRecordStore
import com.aharou.feature.plugin.domain.PluginManager
import com.aharou.feature.plugin.domain.model.PluginManifest
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** No shell, container process, network or Android runtime is used by these fixtures. */
class PluginManagerRecoveryTest {
    @get:Rule val temp = TemporaryFolder()
    private val installer = mockk<ContainerInstaller>()
    private val engine = mockk<LinuxContainerEngine>()
    private val processes = mockk<RuntimeProcessStore>()
    private lateinit var records: PluginRecordStore
    private lateinit var manager: PluginManager
    private lateinit var root: File
    private val oldRecord = PluginRecord("fixture", "Fixture", "1", 123L)

    @Before fun setup() {
        mockkObject(FileLogger)
        every { FileLogger.i(any(), any()) } just Runs
        every { FileLogger.d(any(), any()) } just Runs
        every { FileLogger.w(any(), any(), any()) } just Runs
        every { FileLogger.e(any(), any(), any()) } just Runs
        every { installer.aharouDir } returns temp.root
        every { engine.isContainerInstalled() } returns true
        every { processes.stale() } returns emptyList()
        coEvery { engine.runCommandSync(any(), any(), any()) } returns ""
        coEvery { engine.runCommandSyncWithExit(any(), any(), any()) } returns CommandResult("fixture", 0)
        records = PluginRecordStore(installer)
        manager = PluginManager(engine, installer, records, processes)
        root = manager.hostDir("fixture").apply { mkdirs() }
        File(root, "manifest.json").writeText(Json.encodeToString(manifest("1")))
        write("payload/old-only", "old payload")
        write("bin/tool", "old binary")
        write("data/user.txt", "persistent data")
        records.save(oldRecord)
        assertEquals(oldRecord, records.get("fixture"))
    }

    @After fun teardown() { unmockkObject(FileLogger) }

    private fun manifest(version: String) = PluginManifest(
        id = "fixture", name = "Fixture", version = version,
        installSteps = listOf("install-fixture"), verifyCommand = "verify-fixture",
        uninstallSteps = listOf("uninstall-fixture"),
    )

    private fun write(path: String, content: String) {
        File(root, path).apply { parentFile!!.mkdirs(); writeText(content) }
    }

    private fun archive(extraPath: String? = null): File = temp.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            val entries = linkedMapOf(
                "manifest.json" to Json.encodeToString(manifest("2")),
                "payload/new-only" to "new payload",
            )
            extraPath?.let { entries[it] = "must not escape" }
            entries.forEach { (path, body) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(body.toByteArray()); zip.closeEntry()
            }
        }
    }

    private fun assertOldInstallation() {
        assertEquals("1", manager.readManifest("fixture")!!.version)
        assertEquals(oldRecord, records.get("fixture"))
        assertEquals("old payload", File(root, "payload/old-only").readText())
        assertEquals("old binary", File(root, "bin/tool").readText())
        assertEquals("persistent data", File(root, "data/user.txt").readText())
        assertFalse(File(root, "payload/new-only").exists())
        assertFalse(File(root, "bin/new-only").exists())
    }

    private fun assertNoTransactionFiles() {
        assertEquals(listOf("fixture"), root.parentFile!!.listFiles()!!.map { it.name }.sorted())
    }

    @Test fun invalidZipAndZipSlipNeverTouchInstalledFiles() = runBlocking {
        assertTrue(manager.importPackage(temp.newFile().apply { writeText("not a zip") }).isFailure)
        assertOldInstallation()
        assertTrue(manager.importPackage(archive("payload/../../escaped")).isFailure)
        assertOldInstallation()
        assertFalse(File(root.parentFile, "escaped").exists())
        assertNoTransactionFiles()
    }

    @Test fun importOnlyStagesAndKeepsLiveManifestRecordAndData() = runBlocking {
        assertEquals("2", manager.importPackage(archive()).getOrThrow().version)
        assertOldInstallation()
        coVerify(exactly = 0) { engine.runCommandSyncWithExit(any(), any(), any()) }
        verify(exactly = 0) { processes.stale() }
    }

    @Test fun nonzeroInstallRestoresAllManagedFilesAndCanRetrySameStagedPackage() = runBlocking {
        manager.importPackage(archive()).getOrThrow()
        coEvery { engine.runCommandSyncWithExit(any(), any(), any()) } answers {
            write("bin/tool", "partial binary")
            write("bin/new-only", "partial new file")
            CommandResult("failed fixture", 7)
        }
        assertTrue(manager.install("fixture").isFailure)
        assertOldInstallation()
        coEvery { engine.runCommandSyncWithExit(any(), any(), any()) } returns CommandResult("ok", 0)
        manager.install("fixture").getOrThrow()
        assertEquals("2", manager.readManifest("fixture")!!.version)
        assertEquals("2", records.get("fixture")!!.version)
        assertNotEquals(123L, records.get("fixture")!!.installedAt)
        assertEquals("new payload", File(root, "payload/new-only").readText())
        assertFalse(File(root, "payload/old-only").exists())
        assertEquals("persistent data", File(root, "data/user.txt").readText())
        verify(exactly = 0) { processes.stale() }
        coVerify(exactly = 0) { engine.runCommandSync(any(), any(), any()) }
        assertNoTransactionFiles()
    }

    @Test fun silentlyFailedRecordSaveRollsBackFilesRatherThanReportingSuccess() = runBlocking {
        val failingRecords = mockk<PluginRecordStore>()
        every { failingRecords.get("fixture") } returns oldRecord
        every { failingRecords.save(any()) } just Runs
        val failingManager = PluginManager(engine, installer, failingRecords, processes)
        failingManager.importPackage(archive()).getOrThrow()
        assertTrue(failingManager.install("fixture").isFailure)
        assertOldInstallation()
        verify { failingRecords.save(oldRecord) }
    }

    @Test fun hostImportNeedsNoContainerAndFailedEnabledUpgradeRetainsOldFilesAndRecord() = runBlocking {
        val runtime = mockk<com.aharou.feature.plugin.runtime.HostPluginRuntime>(relaxed = true)
        val hostManager = PluginManager(engine, installer, records, processes, runtime)
        val oldHost = manifest("1").copy(installSteps = emptyList(), uninstallSteps = emptyList(),
            verifyCommand = "", hostRuntime = com.aharou.feature.plugin.domain.model.HostRuntimeSpec("js", "old.js"))
        write("manifest.json", Json.encodeToString(oldHost))
        records.save(oldRecord.copy(enabled = true))
        val next = manifest("2").copy(installSteps = emptyList(), uninstallSteps = emptyList(),
            verifyCommand = "", hostRuntime = com.aharou.feature.plugin.domain.model.HostRuntimeSpec("js", "new.js"))
        val archive = temp.newFile("host.txplugin")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); zip.write(Json.encodeToString(next).toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("payload/new.js")); zip.write("var plugin = {};".toByteArray()); zip.closeEntry()
        }
        coEvery { runtime.load(any(), any()) } throws IllegalStateException("fixture failed new generation")
        hostManager.importPackage(archive).getOrThrow()
        assertTrue(hostManager.install("fixture").isFailure)
        assertEquals(oldHost, hostManager.readManifest("fixture"))
        assertEquals(oldRecord.copy(enabled = true), records.get("fixture"))
        assertEquals("persistent data", File(root, "data/user.txt").readText())
        coVerify(exactly = 0) { engine.runCommandSyncWithExit(any(), any(), any()) }
    }

    @Test fun timeoutInstallRestoresOldInstallation() = runBlocking {
        manager.importPackage(archive()).getOrThrow()
        coEvery { engine.runCommandSyncWithExit(any(), any(), any()) } returns CommandResult("timed out", null)
        assertTrue(manager.install("fixture").isFailure)
        assertOldInstallation()
    }

    @Test fun failedVerificationRestoresOldInstallation() = runBlocking {
        manager.importPackage(archive()).getOrThrow()
        coEvery { engine.runCommandSyncWithExit(any(), any(), any()) } answers {
            CommandResult("fixture", if (firstArg<String>().endsWith("verify-fixture")) 9 else 0)
        }
        assertTrue(manager.install("fixture").isFailure)
        assertOldInstallation()
        coVerify(exactly = 2) { engine.runCommandSyncWithExit(any(), any(), 600_000L) }
    }

    @Test fun cancelledInstallPropagatesAfterRestoringOldFiles() = runBlocking {
        manager.importPackage(archive()).getOrThrow()
        coEvery { engine.runCommandSyncWithExit(any(), any(), any()) } answers {
            write("bin/tool", "partial binary")
            throw CancellationException("fixture cancellation")
        }
        try {
            manager.install("fixture")
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertOldInstallation()
    }

    @Test fun rollbackPreservesDataWrittenByScriptRatherThanDeletingUserData() = runBlocking {
        manager.importPackage(archive()).getOrThrow()
        coEvery { engine.runCommandSyncWithExit(any(), any(), any()) } answers {
            write("data/new-user.txt", "new persistent data")
            CommandResult("failed fixture", 1)
        }
        assertTrue(manager.install("fixture").isFailure)
        assertOldInstallation()
        assertEquals("new persistent data", File(root, "data/new-user.txt").readText())
    }

    @Test fun rollbackDoesNotFollowInstalledLinksOrDeleteTheirTargets() = runBlocking {
        val outside = temp.newFile().apply { writeText("outside data") }
        Files.createSymbolicLink(File(root, "bin/link").toPath(), outside.toPath())
        manager.importPackage(archive()).getOrThrow()
        coEvery { engine.runCommandSyncWithExit(any(), any(), any()) } returns CommandResult("failed", 1)
        assertTrue(manager.install("fixture").isFailure)
        assertOldInstallation()
        assertTrue(Files.isSymbolicLink(File(root, "bin/link").toPath()))
        assertEquals(outside.toPath(), Files.readSymbolicLink(File(root, "bin/link").toPath()))
        assertEquals("outside data", outside.readText())
    }

    @Test fun failedFreshInstallHasNoRecordAndRetainsDataForRetry() = runBlocking {
        root.deleteRecursively()
        records.remove("fixture")
        manager.importPackage(archive()).getOrThrow()
        coEvery { engine.runCommandSyncWithExit(any(), any(), any()) } answers {
            write("data/user.txt", "new persistent data")
            CommandResult("failed", 1)
        }
        assertTrue(manager.install("fixture").isFailure)
        assertNull(records.get("fixture"))
        assertNull(manager.readManifest("fixture"))
        assertEquals("new persistent data", File(root, "data/user.txt").readText())
        coEvery { engine.runCommandSyncWithExit(any(), any(), any()) } returns CommandResult("ok", 0)
        manager.install("fixture").getOrThrow()
        assertEquals("2", records.get("fixture")!!.version)
        assertEquals("new persistent data", File(root, "data/user.txt").readText())
        assertNoTransactionFiles()
    }

    @Test fun stopFailurePreventsUninstallScriptsAndDeletion() = runBlocking {
        every { processes.stale() } throws IllegalStateException("fixture stop failed")
        assertTrue(manager.uninstall("fixture").isFailure)
        assertOldInstallation()
        coVerify(exactly = 0) { engine.runCommandSyncWithExit(any(), any(), any()) }
    }

    @Test fun uninstallNonzeroAndTimeoutKeepDirectoryAndRecordThenSuccessCanRetry() = runBlocking {
        coEvery { engine.runCommandSyncWithExit(any(), any(), any()) } answers {
            CommandResult("failed", if (firstArg<String>().startsWith("pkill ")) 0 else 8)
        }
        assertTrue(manager.uninstall("fixture").isFailure)
        assertOldInstallation()
        coEvery { engine.runCommandSyncWithExit(any(), any(), any()) } answers {
            CommandResult("timeout", if (firstArg<String>().startsWith("pkill ")) 0 else null)
        }
        assertTrue(manager.uninstall("fixture").isFailure)
        assertOldInstallation()
        coEvery { engine.runCommandSyncWithExit(any(), any(), any()) } returns CommandResult("ok", 0)
        manager.uninstall("fixture").getOrThrow()
        assertFalse(root.exists())
        assertNull(records.get("fixture"))
    }

    @Test fun stopNonzeroAndTimeoutPreventUninstallAndNoMatchingProcessIsSuccess() = runBlocking {
        for (code in listOf(2, null)) {
            coEvery { engine.runCommandSyncWithExit(any(), any(), any()) } returns CommandResult("stop failed", code)
            assertTrue(manager.uninstall("fixture").isFailure)
            assertOldInstallation()
        }
        coVerify(exactly = 0) {
            engine.runCommandSyncWithExit(match { it.endsWith("uninstall-fixture") }, any(), any())
        }
        coEvery { engine.runCommandSyncWithExit(any(), any(), any()) } answers {
            CommandResult("fixture", if (firstArg<String>().startsWith("pkill ")) 1 else 0)
        }
        manager.uninstall("fixture").getOrThrow()
        assertFalse(root.exists())
        assertNull(records.get("fixture"))
    }

    @Test fun cancelledStopAndUninstallPropagateWithoutDeletingFiles() = runBlocking {
        coEvery { engine.runCommandSyncWithExit(any(), any(), any()) } throws CancellationException("stop cancelled")
        try {
            manager.uninstall("fixture")
            fail("Stop cancellation must propagate")
        } catch (_: CancellationException) { }
        assertOldInstallation()
        coEvery { engine.runCommandSync(any(), any(), any()) } returns ""
        coEvery { engine.runCommandSyncWithExit(any(), any(), any()) } answers {
            if (firstArg<String>().startsWith("pkill ")) CommandResult("stopped", 0)
            else throw CancellationException("uninstall cancelled")
        }
        try {
            manager.uninstall("fixture")
            fail("Uninstall cancellation must propagate")
        } catch (_: CancellationException) { }
        assertOldInstallation()
    }
}
