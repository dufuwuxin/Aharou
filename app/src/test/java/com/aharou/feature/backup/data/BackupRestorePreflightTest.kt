package com.aharou.feature.backup.data

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRestorePreflightTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun missingMalformedAndDuplicateMetadataAreRejected() = runTest {
        val invalid = listOf(
            backupFixture("workspaces/demo/keep.txt" to "replacement"),
            backupFixture("metadata.json" to "{}"),
            backupFixture("metadata.json" to VALID_METADATA, "metadata.json" to VALID_METADATA)
        )
        for (archive in invalid) {
            assertTrue(runCatching { BackupRestorePreflight.validate(archive.inputStream(), json) {} }.isFailure)
        }
    }

    @Test fun metadataMayFollowFilesAndLegacySnapshotRemainsReadable() = runTest {
        for (name in listOf("metadata.json", "snapshot.json")) {
            val archive = backupFixture("workspaces/demo/keep.txt" to "replacement", name to VALID_METADATA)
            val metadata = BackupRestorePreflight.validate(archive.inputStream(), json) { assertEquals(1, it) }
            assertEquals(1, metadata.schemaVersion)
        }
    }

    @Test fun invalidJsonlAndGzipTrailerAreRejectedBeforeRestore() = runTest {
        for (record in listOf("{}\n", "\r\n")) {
            val invalidJson = backupFixture("metadata.json" to VALID_METADATA, "messages.jsonl" to record)
            assertTrue(runCatching { BackupRestorePreflight.validate(invalidJson.inputStream(), json) {} }.isFailure)
        }
        val invalidCrc = backupFixture("metadata.json" to VALID_METADATA)
        invalidCrc[invalidCrc.size - 8] = (invalidCrc[invalidCrc.size - 8].toInt() xor 1).toByte()
        assertTrue(runCatching { BackupRestorePreflight.validate(invalidCrc.inputStream(), json) {} }.isFailure)
    }

    @Test fun unsupportedSchemaIsRejected() = runTest {
        val archive = backupFixture("metadata.json" to VALID_METADATA)
        assertTrue(runCatching {
            BackupRestorePreflight.validate(archive.inputStream(), json) { error("newer schema") }
        }.isFailure)
    }
}

internal const val VALID_METADATA = "{\"schemaVersion\":1,\"createdAt\":1}"

internal fun backupFixture(vararg entries: Pair<String, String>): ByteArray {
    val output = ByteArrayOutputStream()
    GzipCompressorOutputStream(output).use { gzip ->
        TarArchiveOutputStream(gzip).use { tar ->
            entries.forEach { (name, content) ->
                val bytes = content.toByteArray(Charsets.UTF_8)
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
    }
    return output.toByteArray()
}
