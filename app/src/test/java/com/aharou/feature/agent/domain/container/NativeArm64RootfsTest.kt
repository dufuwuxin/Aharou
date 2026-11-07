package com.aharou.feature.agent.domain.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class NativeArm64RootfsTest {
    @get:Rule val temp = TemporaryFolder()

    private fun image(machine: Int = 183, elfClass: Int = 2, distro: String = "ubuntu"): File {
        val root = temp.newFolder()
        File(root, "etc").mkdirs()
        File(root, "etc/os-release").writeText("ID=$distro\nVERSION_ID=24.04\n")
        File(root, "bin").mkdirs()
        val elf = ByteArray(64)
        elf[0] = 0x7f; elf[1] = 69; elf[2] = 76; elf[3] = 70
        elf[4] = elfClass.toByte(); elf[5] = 1; elf[6] = 1
        elf[18] = machine.toByte(); elf[19] = (machine shr 8).toByte()
        File(root, "bin/sh").writeBytes(elf)
        return root
    }

    private fun refused(root: File) {
        try { NativeArm64Rootfs.requireSupported(root); fail("unsupported rootfs accepted") }
        catch (expected: IOException) { assertTrue(expected.message.orEmpty().isNotBlank()) }
    }

    @Test fun nativeArm64UbuntuAccepted() { NativeArm64Rootfs.requireUbuntu2404(image()) }
    @Test fun amd64RejectedDespiteArm64Filename() { refused(image(machine = 62)) }
    @Test fun arm32Rejected() { refused(image(machine = 40, elfClass = 1)) }
    @Test fun elf32Aarch64Rejected() { refused(image(elfClass = 1)) }
    @Test fun alpineRejectedDespiteNativeArm64() { refused(image(distro = "alpine")) }
    @Test fun missingShellRejected() { val root = image(); File(root, "bin/sh").delete(); refused(root) }
    @Test fun scriptInsteadOfElfRejected() { val root = image(); File(root, "bin/sh").writeText("#!/bin/bash"); refused(root) }
    @Test fun missingOsIdentityRejected() { val root = image(); File(root, "etc/os-release").delete(); refused(root) }
    @Test fun alpineMarkerCannotBeHiddenByUbuntuIdentity() { val root = image(); File(root, "etc/alpine-release").writeText("3.24"); refused(root) }

    @Test fun absoluteShellLinkIsResolvedInsideGuest() {
        val root = image(); val dash = File(root, "bin/dash")
        File(root, "bin/sh").renameTo(dash)
        Files.createSymbolicLink(File(root, "bin/sh").toPath(), File("/bin/dash").toPath())
        NativeArm64Rootfs.requireSupported(root)
        assertEquals(dash, NativeArm64Rootfs.resolveGuest(root, "/bin/sh"))
    }

    @Test fun mergedUsrAndRelativeShellLinksAccepted() {
        val root = image(); File(root, "usr/bin").mkdirs()
        File(root, "bin/sh").renameTo(File(root, "usr/bin/dash")); File(root, "bin").delete()
        Files.createSymbolicLink(File(root, "bin").toPath(), File("usr/bin").toPath())
        Files.createSymbolicLink(File(root, "usr/bin/sh").toPath(), File("dash").toPath())
        NativeArm64Rootfs.requireSupported(root)
    }

    @Test fun escapeAndLinkLoopRejected() {
        for (target in listOf("../../outside", "sh")) {
            val root = image(); File(root, "bin/sh").delete()
            Files.createSymbolicLink(File(root, "bin/sh").toPath(), File(target).toPath()); refused(root)
        }
    }

    @Test fun wrongVersionNotAcceptedAsBuiltin() {
        val root = image(); File(root, "etc/os-release").writeText("ID=ubuntu\nVERSION_ID=26.04")
        try { NativeArm64Rootfs.requireUbuntu2404(root); fail("wrong builtin version") } catch (_: IOException) { }
    }
}
