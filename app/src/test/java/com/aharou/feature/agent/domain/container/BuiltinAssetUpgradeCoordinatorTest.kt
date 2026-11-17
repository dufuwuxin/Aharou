package com.aharou.feature.agent.domain.container

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BuiltinAssetUpgradeCoordinatorTest {
    @org.junit.Test fun sameVersionDoesNotRedeployAcceptedDirectoryAfterUserEdits() {
        val dir = java.nio.file.Files.createTempDirectory("builtin-once").toFile()
        try {
            val coordinator = BuiltinAssetUpgradeCoordinator(dir, 1, false)
            val skill = java.io.File(dir, "builtin-skills/example/SKILL.md")
            coordinator.extractDirectory("skills") { skill.parentFile!!.mkdirs(); skill.writeText("seed") }
            skill.writeText("user edit")
            coordinator.extractDirectory("skills") { skill.writeText("overwritten") }
            val restarted = BuiltinAssetUpgradeCoordinator(dir, 1, true)
            restarted.extractDirectory("skills") { skill.writeText("overwritten again") }
            org.junit.Assert.assertEquals("user edit", skill.readText())
        } finally { dir.deleteRecursively() }
    }

    @get:Rule val temp = TemporaryFolder()

    @Test fun freshInstallSeedsWithoutAsking() {
        var writes = 0
        val coordinator = BuiltinAssetUpgradeCoordinator(temp.root, 1, false, { writes++ })
        assertEquals(BuiltinAssetUpgradeCoordinator.Choice.FRESH, coordinator.state.value.choice)
        coordinator.installIfAllowed()
        assertEquals(1, writes)
    }

    @Test fun existingDirectoryOrReceiptRequiresChoiceBeforeAnyWrite() {
        File(temp.root, "builtin-seed-state").mkdirs()
        var writes = 0
        val coordinator = BuiltinAssetUpgradeCoordinator(temp.root, 2, true, { writes++ })
        coordinator.installIfAllowed()
        assertEquals(BuiltinAssetUpgradeCoordinator.Choice.PENDING, coordinator.state.value.choice)
        assertEquals(0, writes)
    }

    @Test fun refusalAndDismissalKeepFilesAndRememberThisVersion() {
        val old = File(temp.root, "builtin-skills/example/SKILL.md").apply {
            parentFile!!.mkdirs(); writeText("old instructions")
        }
        val custom = File(temp.root, "skills/custom/SKILL.md").apply {
            parentFile!!.mkdirs(); writeText("custom instructions")
        }
        var writes = 0
        var skipped = 0
        val coordinator = BuiltinAssetUpgradeCoordinator(temp.root, 2, true, { writes++ }, { skipped++ })
        coordinator.choose(false) // The dialog uses the same refusal for Back/outside dismissal.
        coordinator.installIfAllowed()
        assertEquals(0, writes)
        assertEquals(1, skipped)
        assertEquals("old instructions", old.readText())
        assertEquals("custom instructions", custom.readText())
        assertEquals("old instructions", coordinator.selectedFiles("skills").getValue("example/SKILL.md").decodeToString())
        val restart = BuiltinAssetUpgradeCoordinator(temp.root, 2, true, { writes++ })
        assertEquals(BuiltinAssetUpgradeCoordinator.Choice.KEEP, restart.state.value.choice)
        restart.installIfAllowed()
        assertEquals(0, writes)
        assertEquals(BuiltinAssetUpgradeCoordinator.Choice.PENDING,
            BuiltinAssetUpgradeCoordinator(temp.root, 3, true).state.value.choice)
    }

    @Test fun acceptanceUpdatesOnlyAfterChoiceAndIsRemembered() {
        var writes = 0
        val coordinator = BuiltinAssetUpgradeCoordinator(temp.root, 2, true, { writes++ })
        assertEquals(0, writes)
        coordinator.choose(true)
        assertEquals(1, writes)
        coordinator.choose(false)
        assertEquals(BuiltinAssetUpgradeCoordinator.Choice.APPLY, coordinator.state.value.choice)
        assertEquals(BuiltinAssetUpgradeCoordinator.Choice.APPLY,
            BuiltinAssetUpgradeCoordinator(temp.root, 2, true).state.value.choice)
        assertEquals(BuiltinAssetUpgradeCoordinator.Choice.PENDING,
            BuiltinAssetUpgradeCoordinator(temp.root, 3, true).state.value.choice)
    }

    @Test fun missingOldAssetsAreExplicitAndNeverFilledWhenDeclined() {
        val coordinator = BuiltinAssetUpgradeCoordinator(temp.root, 2, true,
            { File(temp.root, "builtin-skills/new/SKILL.md").apply { parentFile!!.mkdirs(); writeText("new") } })
        assertTrue(coordinator.state.value.missingPreviousSkills)
        coordinator.choose(false)
        coordinator.installIfAllowed()
        assertTrue(coordinator.selectedFiles("skills").isEmpty())
        assertFalse(File(temp.root, "builtin-skills").exists())
    }

    @Test fun interruptedUnansweredPromptDoesNotBecomeConsent() {
        BuiltinAssetUpgradeCoordinator(temp.root, 2, true)
        val restart = BuiltinAssetUpgradeCoordinator(temp.root, 2, true)
        assertEquals(BuiltinAssetUpgradeCoordinator.Choice.PENDING, restart.state.value.choice)
        assertFalse(restart.canUpgrade)
    }

    @Test fun corruptReceiptDoesNotBecomeFreshInstall() {
        File(temp.root, ".builtin-asset-upgrade-v1").writeText("broken")
        assertEquals(BuiltinAssetUpgradeCoordinator.Choice.PENDING,
            BuiltinAssetUpgradeCoordinator(temp.root, 2, false).state.value.choice)
    }
    @Test fun emptyOldSkillDirectoryIsReportedAsMissing() {
        File(temp.root, "builtin-skills").mkdirs()
        assertTrue(BuiltinAssetUpgradeCoordinator(temp.root, 2, true).state.value.missingPreviousSkills)
    }

    @Test fun remoteBootstrapCannotConsumeCurrentApkWhilePendingOrDeclined() = runBlocking {
        val coordinator = BuiltinAssetUpgradeCoordinator(temp.root, 2, true)
        val connection = RemoteSshConnection(mockk(), mockk(), mockk(), mockk(), coordinator)
        val target = mockk<RemoteSshConnection.BootstrapTarget>()
        val files = mapOf("new.md" to "new APK".toByteArray())
        try { connection.uploadDocs(files, target); fail("pending must stay retryable") }
        catch (error: IllegalStateException) { assertEquals("BUILTIN_ASSET_UPGRADE_PENDING", error.message) }
        try { connection.uploadBuiltinFiles("agents", files, target = target); fail("pending must not seed") }
        catch (error: IllegalStateException) { assertEquals("BUILTIN_ASSET_UPGRADE_PENDING", error.message) }
        coordinator.choose(false)
        connection.uploadDocs(files, target)
        connection.uploadBuiltinFiles("agents", files, target = target)
        verify { target wasNot io.mockk.Called }
    }

    @Test fun retainedRemoteResourcesCannotBeOverwrittenEvenByCurrentApkBytes() = runBlocking {
        File(temp.root, "builtin-skills/old/script.py").apply { parentFile!!.mkdirs(); writeText("old") }
        val coordinator = BuiltinAssetUpgradeCoordinator(temp.root, 2, true)
        coordinator.choose(false)
        val connection = RemoteSshConnection(mockk(), mockk(), mockk(), mockk(), coordinator)
        val client = mockk<SSHClient>()
        every { client.isConnected } returns true
        every { client.isAuthenticated } returns true
        val target = RemoteSshConnection.BootstrapTarget(client, "/home/test", mockk())
        for ((field, value) in listOf("sshClient" to client, "bootstrap" to target)) {
            RemoteSshConnection::class.java.getDeclaredField(field).apply { isAccessible = true }.set(connection, value)
        }
        val sftp = mockk<SFTPClient>(relaxed = true)
        every { client.newSFTPClient() } returns sftp
        every { sftp.lstat(any()) } returns null
        every { sftp.statExistence(any()) } returns null
        try {
            connection.uploadBuiltinSkillResources(mapOf("old/script.py" to "new APK".toByteArray()), target)
            fail("retained installation cannot repair or replace remote resources")
        } catch (error: IllegalStateException) {
            assertEquals("BUILTIN_SKILL_RESOURCES_RETAINED", error.message)
        }
        verify(exactly = 0) { sftp.mkdirs(any()) }
        verify(exactly = 0) { sftp.open(any<String>(), any()) }
    }

    @Test fun failedAcceptanceStaysPendingAndRetryDoesNotPretendToRollbackPartialWrites() {
        val doc = File(temp.root, "docs/old.md").apply { parentFile!!.mkdirs(); writeText("old guide") }
        var failed = true
        val coordinator = BuiltinAssetUpgradeCoordinator(temp.root, 2, true, {
            doc.writeText("partly updated guide")
            if (failed) throw java.io.IOException("interrupted extraction")
        })
        try { coordinator.choose(true); fail("failed extraction must not close the prompt") }
        catch (_: java.io.IOException) { }
        assertEquals(BuiltinAssetUpgradeCoordinator.Choice.PENDING, coordinator.state.value.choice)
        assertFalse(coordinator.canUpgrade)
        assertEquals("partly updated guide", doc.readText())
        assertEquals(BuiltinAssetUpgradeCoordinator.Choice.PENDING,
            BuiltinAssetUpgradeCoordinator(temp.root, 2, true).state.value.choice)
        failed = false
        coordinator.choose(true)
        assertEquals(BuiltinAssetUpgradeCoordinator.Choice.APPLY, coordinator.state.value.choice)
    }

    @Test fun refusalAndRestartRetainOldDocumentsWithoutTouchingProductionSingleton() {
        val doc = File(temp.root, "docs/guide.md").apply { parentFile!!.mkdirs(); writeText("old guide") }
        val coordinator = BuiltinAssetUpgradeCoordinator(temp.root, 2, true, { doc.writeText("new guide") })
        coordinator.choose(false)
        val restarted = BuiltinAssetUpgradeCoordinator(temp.root, 2, true, { doc.writeText("new guide") })
        restarted.installIfAllowed()
        assertEquals("old guide", restarted.selectedFiles("docs").getValue("guide.md").decodeToString())
        assertEquals("old guide", doc.readText())
        val separateFresh = BuiltinAssetUpgradeCoordinator(temp.newFolder(), 2, false)
        assertEquals(BuiltinAssetUpgradeCoordinator.Choice.FRESH, separateFresh.state.value.choice)
    }

}
