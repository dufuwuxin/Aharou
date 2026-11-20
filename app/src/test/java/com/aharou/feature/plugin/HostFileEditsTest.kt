package com.aharou.feature.plugin

import com.aharou.feature.plugin.runtime.HostFileEdits
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HostFileEditsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun bodyAndVersionRemainTheSameSnapshotWhenTheHostWritesAgain() {
        val file = temp.newFile().apply { writeText("A") }
        val snapshot = HostFileEdits.read(file)
        file.writeText("B")
        assertEquals("A", snapshot.text)
        assertNotEquals(snapshot.sha256, HostFileEdits.read(file).sha256)
        assertTrue(runCatching { HostFileEdits.write(file, "C", snapshot.sha256) }.isFailure)
        assertEquals("B", file.readText())
    }

    @Test fun failedWriteCannotTruncateTheOriginalAndCleansOnlyItsTemporaryFile() {
        val file = temp.newFile("original.json").apply { writeText("complete original") }
        val previous = HostFileEdits.read(file)
        val before = temp.root.listFiles()!!.map { it.name }.toSet()
        val result = runCatching {
            HostFileEdits.write(file, "replacement", previous.sha256, writeBytes = { target, bytes ->
                target.outputStream().use { it.write(bytes, 0, 2); throw IOException("injected disk failure") }
            })
        }
        assertTrue(result.isFailure)
        assertEquals("complete original", file.readText())
        assertEquals(before, temp.root.listFiles()!!.map { it.name }.toSet())
    }

    @Test fun aConcurrentHostEditBeforeReplacementIsNotOverwritten() {
        val file = temp.newFile().apply { writeText("first") }
        val previous = HostFileEdits.read(file)
        assertTrue(runCatching {
            HostFileEdits.write(file, "replacement", previous.sha256, writeBytes = { target, bytes ->
                target.writeBytes(bytes); file.writeText("new host version")
            })
        }.isFailure)
        assertEquals("new host version", file.readText())
    }
}
