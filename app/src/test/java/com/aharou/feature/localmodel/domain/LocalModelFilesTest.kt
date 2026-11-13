package com.aharou.feature.localmodel.domain

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalModelFilesTest {
    @get:Rule val temp = TemporaryFolder()
    private val bytes = "GGUFcontrolled model bytes".toByteArray()
    private fun spec(content: ByteArray = bytes) = LocalModels.TINY.copy(
        sizeBytes = content.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(content)
            .joinToString("") { "%02x".format(it.toInt() and 255) })

    @Test fun importAndReloadVerifyFormatSizeAndChecksum() {
        val target = File(temp.root, "model.gguf")
        LocalModelFiles.import(ByteArrayInputStream(bytes), target, spec())
        assertArrayEquals(bytes, target.readBytes())
        LocalModelFiles.verify(target, spec())
        target.writeBytes(bytes.copyOf().apply { this[lastIndex] = 42 })
        try { LocalModelFiles.verify(target, spec()); fail("same-size corruption must fail") }
        catch (expected: IOException) { assertEquals("Model checksum mismatch", expected.message) }
    }

    @Test fun invalidAndOversizedImportsNeverReplaceOldModel() {
        val target = File(temp.root, "model.gguf").apply { writeBytes(bytes) }
        for (candidate in listOf("BAD!controlled model bytes".toByteArray(), bytes.copyOf(7), bytes + byteArrayOf(1))) {
            try { LocalModelFiles.import(ByteArrayInputStream(candidate), target, spec()); fail("invalid import accepted") }
            catch (expected: IOException) { assertArrayEquals(bytes, target.readBytes()) }
        }
        assertEquals(listOf("model.gguf"), temp.root.listFiles()!!.map { it.name })
    }

    @Test fun cancellationRemovesOnlyItsStagingAndPreservesOldModel() {
        val target = File(temp.root, "model.gguf").apply { writeBytes(bytes) }
        try {
            LocalModelFiles.import(ByteArrayInputStream(bytes), target, spec()) { throw CancellationException("cancel") }
            fail("cancellation swallowed")
        } catch (expected: CancellationException) {
            assertArrayEquals(bytes, target.readBytes())
            assertEquals(listOf("model.gguf"), temp.root.listFiles()!!.map { it.name })
        }
    }
}
