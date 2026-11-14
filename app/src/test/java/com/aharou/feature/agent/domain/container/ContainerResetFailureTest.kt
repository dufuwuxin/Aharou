package com.aharou.feature.agent.domain.container

import android.content.ContextWrapper
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException

class ContainerResetFailureTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun rootLinkResetCannotRemoveTheOutsideInstallationMarker() {
        val outside = temporary.newFolder("outside")
        val marker = File(outside, ".installed").apply { writeText("KEEP") }
        val link = File(temporary.root, "root-link")
        java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        val context = object : ContextWrapper(null) {
            override fun getFilesDir() = temporary.root
            override fun getCacheDir() = temporary.root
        }
        val installer = ContainerInstaller(context, ContainerOsDetector(context))
        val method = ContainerInstaller::class.java.getDeclaredMethod("purgeRootfs", File::class.java,
            File::class.java, kotlin.jvm.functions.Function1::class.java).apply { isAccessible = true }
        method.invoke(installer, link, File(link, ".installed"), { _: Int -> })
        assertFalse(java.nio.file.Files.exists(link.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("KEEP", marker.readText())
    }

    @Test fun deletionFailureCannotReturnAsSuccessfulReset() {
        val context = object : ContextWrapper(null) {
            override fun getFilesDir() = temporary.root
            override fun getCacheDir() = temporary.root
        }
        val installer = ContainerInstaller(context, ContainerOsDetector(context))
        val root = mockk<File>()
        val marker = mockk<File>()
        every { root.exists() } returns true
        every { root.toPath() } returns File(temporary.root, "missing-fixture-path").toPath()
        every { root.delete() } returns false
        every { root.name } returns "fixture"
        every { marker.delete() } returns false
        every { marker.exists() } returns true
        val method = ContainerInstaller::class.java.getDeclaredMethod("purgeRootfs", File::class.java,
            File::class.java, kotlin.jvm.functions.Function1::class.java).apply { isAccessible = true }
        val result = runCatching { method.invoke(installer, root, marker, { _: Int -> }) }
        assertTrue("A failed delete cannot be reported as reset success", result.isFailure)
        val failure = result.exceptionOrNull() as? InvocationTargetException
        assertTrue(failure?.cause is IOException)
    }
}
