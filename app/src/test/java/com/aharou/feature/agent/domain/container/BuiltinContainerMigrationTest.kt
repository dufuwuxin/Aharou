package com.aharou.feature.agent.domain.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE

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

    @Test fun unreadableManagedBindingCanBeCopiedRetriedAndCleanedUp() {
        val migration = BuiltinContainerMigration(temp.root)
        val source = File(migration.legacy, "root/workspace").apply { mkdirs() }
        File(source, "keep.txt").writeText("original")
        stage(migration)
        val sourcePath = source.toPath()
        val modifiedTime = FileTime.fromMillis(1_700_000_000_000L)
        Files.setLastModifiedTime(sourcePath, modifiedTime)
        val stageArchive = File(migration.stage, "root/.container-migration/rootfs/workspace")
        val finalArchive = File(migration.destination, "root/.container-migration/rootfs/workspace")
        val noPermissions = emptySet<PosixFilePermission>()
        val ownerPermissions = setOf(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE)
        Files.setPosixFilePermissions(sourcePath, noPermissions)
        try {
            var validations = 0
            try {
                migration.commit(version) {
                    validations++
                    if (validations == 2) throw IOException("stop after archive")
                }
                fail("must stop before cleanup")
            } catch (_: IOException) { }
            assertEquals(2, validations)
            assertEquals(noPermissions, Files.getPosixFilePermissions(sourcePath, NOFOLLOW_LINKS))
            assertEquals(noPermissions, Files.getPosixFilePermissions(stageArchive.toPath(), NOFOLLOW_LINKS))
            assertEquals(modifiedTime, Files.getLastModifiedTime(stageArchive.toPath(), NOFOLLOW_LINKS))
            assertEquals(modifiedTime, Files.getLastModifiedTime(sourcePath, NOFOLLOW_LINKS))
            Files.setPosixFilePermissions(stageArchive.toPath(), ownerPermissions)
            assertEquals("original", File(stageArchive, "keep.txt").readText())
            Files.setPosixFilePermissions(stageArchive.toPath(), noPermissions)

            stage(migration)
            commit(migration)
            assertFalse(migration.legacy.exists())
            assertEquals(noPermissions, Files.getPosixFilePermissions(finalArchive.toPath(), NOFOLLOW_LINKS))
            assertEquals(modifiedTime, Files.getLastModifiedTime(finalArchive.toPath(), NOFOLLOW_LINKS))
            Files.setPosixFilePermissions(finalArchive.toPath(), ownerPermissions)
            assertEquals("original", File(finalArchive, "keep.txt").readText())
        } finally {
            if (Files.exists(sourcePath, NOFOLLOW_LINKS)) Files.setPosixFilePermissions(sourcePath, ownerPermissions)
            if (Files.exists(stageArchive.toPath(), NOFOLLOW_LINKS)) Files.setPosixFilePermissions(stageArchive.toPath(), ownerPermissions)
            if (Files.exists(finalArchive.toPath(), NOFOLLOW_LINKS)) Files.setPosixFilePermissions(finalArchive.toPath(), ownerPermissions)
        }
    }

    @Test fun unreadableNestedTreeIsRemovedWithoutFollowingLinks() {
        val migration = BuiltinContainerMigration(temp.root)
        val parent = temp.newFolder("restricted").toPath()
        val child = Files.createDirectory(parent.resolve("child"))
        val outside = temp.newFolder("outside-cleanup").toPath()
        Files.write(outside.resolve("sentinel"), "untouched".toByteArray())
        Files.write(child.resolve("keep.txt"), "fixture".toByteArray())
        Files.createSymbolicLink(child.resolve("outside"), outside)
        val ownerPermissions = setOf(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE)
        Files.setPosixFilePermissions(child, emptySet())
        Files.setPosixFilePermissions(parent, emptySet())
        try {
            migration.removeTree(parent.toFile())
            assertFalse(Files.exists(parent, NOFOLLOW_LINKS))
            assertEquals("untouched", File(outside.toFile(), "sentinel").readText())
        } finally {
            if (Files.exists(parent, NOFOLLOW_LINKS)) Files.setPosixFilePermissions(parent, ownerPermissions)
            if (Files.exists(child, NOFOLLOW_LINKS)) Files.setPosixFilePermissions(child, ownerPermissions)
        }
    }

    @Test fun nestedPermissionRestoreRunsChildrenBeforeParentsAndRethrowsOriginal() {
        val migration = BuiltinContainerMigration(temp.root)
        val parent = temp.newFolder("parent").toPath()
        val child = Files.createDirectory(parent.resolve("child"))
        val noPermissions = emptySet<PosixFilePermission>()
        val ownerPermissions = setOf(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE)
        val adjusted = linkedMapOf(parent to noPermissions, child to noPermissions)
        Files.setPosixFilePermissions(parent, ownerPermissions)
        Files.setPosixFilePermissions(child, ownerPermissions)
        val original = IOException("delete failed")
        try {
            try {
                migration.restoreAfterDeleteFailure(adjusted, original)
                fail("must rethrow")
            } catch (error: IOException) {
                assertSame(original, error)
            }
            assertEquals(noPermissions, Files.getPosixFilePermissions(parent, NOFOLLOW_LINKS))
            Files.setPosixFilePermissions(parent, ownerPermissions)
            assertEquals(noPermissions, Files.getPosixFilePermissions(child, NOFOLLOW_LINKS))
        } finally {
            Files.setPosixFilePermissions(parent, ownerPermissions)
            Files.setPosixFilePermissions(child, ownerPermissions)
        }
    }

    @Test fun interruptedRenameRestoresBackupBeforeRetry() {
        val migration = BuiltinContainerMigration(temp.root)
        val backup = File(temp.root, "rootfs_ubuntu.previous/root").apply { mkdirs() }
        File(backup, "keep").writeText("backup-home")
        stage(migration); commit(migration)
        assertEquals("backup-home", File(migration.destination, "root/keep").readText())
    }
}
