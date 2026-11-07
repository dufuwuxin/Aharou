package com.aharou.feature.agent.domain.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class BuiltinContainerMigrationTest {
    @get:Rule val temp = TemporaryFolder()
    private val version = "ubuntu-24.04-arm64-v1"

    private fun stage(migration: BuiltinContainerMigration): File {
        val root = migration.prepareStage()
        File(root, "etc").mkdirs(); File(root, "bin").mkdirs(); File(root, "root").mkdirs()
        File(root, "etc/os-release").writeText("ID=ubuntu\nVERSION_ID=24.04\n")
        val elf = ByteArray(64)
        elf[0] = 0x7f; elf[1] = 69; elf[2] = 76; elf[3] = 70
        elf[4] = 2; elf[5] = 1; elf[6] = 1; elf[18] = 183.toByte()
        File(root, "bin/sh").writeBytes(elf)
        return root
    }

    private fun commit(migration: BuiltinContainerMigration) =
        migration.commit(version) { NativeArm64Rootfs.requireUbuntu2404(it) }

    @Test fun newInstallAndRepeatedCleanupAreIdempotent() {
        val migration = BuiltinContainerMigration(temp.root); stage(migration); commit(migration)
        migration.finishCleanup(); migration.finishCleanup()
        assertEquals(version, File(migration.destination, ".installed").readText())
        assertFalse(migration.stage.exists())
    }

    @Test fun legacyHomesPreservedButSystemAndExternalBindingsNotCopied() {
        val migration = BuiltinContainerMigration(temp.root)
        File(migration.legacy, "root/.ssh").mkdirs(); File(migration.legacy, "home/u").mkdirs()
        File(migration.legacy, "usr/bin").mkdirs()
        File(migration.legacy, "root/.ssh/key").writeText("fixture-only")
        File(migration.legacy, "home/u/keep.txt").writeText("home")
        File(migration.legacy, "usr/bin/old-tool").writeText("old-system")
        val outside = temp.newFolder("outside"); File(outside, "keep").writeText("untouched")
        Files.createSymbolicLink(File(migration.legacy, "root/workspace").toPath(), outside.toPath())
        Files.createSymbolicLink(File(migration.legacy, "home/u/link").toPath(), outside.toPath())
        stage(migration); assertTrue(migration.legacy.isDirectory); commit(migration)
        assertEquals("fixture-only", File(migration.destination, "root/.ssh/key").readText())
        assertEquals("home", File(migration.destination, "home/u/keep.txt").readText())
        assertFalse(File(migration.destination, "usr/bin/old-tool").exists())
        assertFalse(File(migration.destination, "root/workspace").exists())
        assertTrue(Files.isSymbolicLink(File(migration.destination, "home/u/link").toPath()))
        assertEquals("untouched", File(outside, "keep").readText())
        assertFalse(migration.legacy.exists())
    }

    @Test fun realDataUnderManagedBindingNamesIsArchivedBeforeOldTreeRemoval() {
        val migration = BuiltinContainerMigration(temp.root)
        for (name in listOf("workspace", "shared", ".aharou", ".aicode", "log")) {
            val old = File(migration.legacy, "root/$name").apply { mkdirs() }
            File(old, "standalone.txt").writeText("original-$name")
        }
        stage(migration); commit(migration)
        for (name in listOf("workspace", "shared", ".aharou", ".aicode", "log")) {
            assertEquals("original-$name", File(migration.destination,
                "root/.container-migration/rootfs/$name/standalone.txt").readText())
        }
        assertFalse(migration.legacy.exists())
    }

    @Test fun preexistingArchiveSymlinkCannotRedirectLaterHomeWrites() {
        val migration = BuiltinContainerMigration(temp.root)
        val outside = temp.newFolder("outside-archive")
        File(outside, "sentinel").writeText("untouched")
        File(migration.legacy, "root").mkdirs()
        Files.createSymbolicLink(File(migration.legacy, "root/.container-migration").toPath(), outside.toPath())
        val workspace = File(migration.destination, "root/workspace").apply { mkdirs() }
        File(workspace, "standalone.txt").writeText("must survive")
        stage(migration); commit(migration)
        assertEquals(listOf("sentinel"), outside.listFiles()!!.map { it.name })
        assertEquals("untouched", File(outside, "sentinel").readText())
        assertEquals("must survive", File(migration.destination,
            "root/.container-migration-1/rootfs_ubuntu/workspace/standalone.txt").readText())
        assertTrue(Files.isSymbolicLink(File(migration.destination, "root/.container-migration").toPath()))
    }

    @Test fun failedValidationNeverDeletesOldHomes() {
        val migration = BuiltinContainerMigration(temp.root)
        File(migration.legacy, "root").mkdirs(); File(migration.legacy, "root/keep").writeText("original")
        stage(migration)
        try { migration.commit(version) { throw IOException("probe failure") }; fail("must fail") } catch (_: IOException) { }
        assertEquals("original", File(migration.legacy, "root/keep").readText())
        assertFalse(migration.destination.exists())
        stage(migration); commit(migration)
        assertEquals("original", File(migration.destination, "root/keep").readText())
    }

    @Test fun replacingUbuntuRetainsHomesAsWell() {
        val migration = BuiltinContainerMigration(temp.root)
        File(migration.destination, "root").mkdirs(); File(migration.destination, "root/keep").writeText("ubuntu-home")
        stage(migration); commit(migration)
        assertEquals("ubuntu-home", File(migration.destination, "root/keep").readText())
        assertFalse(File(temp.root, "rootfs_ubuntu.previous").exists())
    }

    @Test fun interruptedRenameRestoresBackupBeforeRetry() {
        val migration = BuiltinContainerMigration(temp.root)
        val backup = File(temp.root, "rootfs_ubuntu.previous/root").apply { mkdirs() }
        File(backup, "keep").writeText("backup-home")
        stage(migration); commit(migration)
        assertEquals("backup-home", File(migration.destination, "root/keep").readText())
    }
}
