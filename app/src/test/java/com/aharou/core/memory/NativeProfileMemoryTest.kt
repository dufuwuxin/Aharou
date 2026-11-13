package com.aharou.core.memory

import com.aharou.feature.agent.domain.context.ContextCategory
import com.aharou.feature.agent.domain.context.ContextLoad
import com.aharou.feature.agent.domain.memory.MemoryFileStorage
import com.aharou.feature.agent.domain.memory.MemoryParser
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.agent.domain.memory.MemorySource
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeProfileMemoryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun legacyProfileAndCoreMigrateOnceWithoutChangingTheOldPersonaFile() {
        val raw = "---\nname: Agent\nstyle: concise\nlang: en\n---\nBe precise.\n"
        val soul = File(temp.root, "SOUL.md").apply { writeText(raw) }
        File(temp.root, "CORE.md").writeText("Project facts")
        NativeProfileMemory.migrate(temp.root, true)
        assertEquals(raw, soul.readText())
        assertEquals("Be precise.", NativeProfileMemory.read(temp.root, NativeProfileMemory.BODY))
        assertEquals("concise", NativeProfileMemory.read(temp.root, NativeProfileMemory.STYLE))
        assertEquals("en", NativeProfileMemory.read(temp.root, NativeProfileMemory.LANGUAGE))
        val core = MemoryParser.parse(File(temp.root, "CORE.md"), MemoryScope.GLOBAL)!!
        assertEquals(ContextCategory.LONG_TERM, core.contextCategory)
        assertEquals("Project facts", core.content)
    }

    @Test fun disabledEditedAndDeletedNativeEntriesAreNotResurrectedByLegacyMigration() {
        File(temp.root, "SOUL.md").writeText("---\nname: Agent\n---\nOriginal personality")
        NativeProfileMemory.migrate(temp.root, true)
        val body = MemorySource.resolveMemoryFile(temp.root, NativeProfileMemory.BODY)
        val entry = MemoryParser.parse(body, MemoryScope.GLOBAL)!!
        assertTrue(MemoryFileStorage.write(body, MemoryParser.format(entry.copy(content = "User edit", contextLoad = ContextLoad.OFF))))
        NativeProfileMemory.migrate(temp.root, true)
        assertEquals("User edit", NativeProfileMemory.read(temp.root, NativeProfileMemory.BODY))
        NativeProfileMemory.write(temp.root, NativeProfileMemory.BODY, "Settings edit")
        assertEquals(ContextLoad.OFF, MemoryParser.parse(body, MemoryScope.GLOBAL)!!.contextLoad)
        assertTrue(body.delete())
        NativeProfileMemory.migrate(temp.root, true)
        assertFalse(body.exists())
    }

    @Test fun coreBodyRoundTripDoesNotNestFrontmatterAndMigrationFailureDoesNotMarkSuccess() {
        NativeProfileMemory.write(temp.root, "CORE", "Initial facts")
        val body = NativeProfileMemory.read(temp.root, "CORE")
        NativeProfileMemory.write(temp.root, "CORE", body)
        assertEquals("Initial facts", NativeProfileMemory.read(temp.root, "CORE"))
        assertEquals(ContextCategory.LONG_TERM,
            MemoryParser.parse(File(temp.root, "CORE.md"), MemoryScope.GLOBAL)!!.contextCategory)
        val blockedRoot = temp.newFile()
        assertFalse(NativeProfileMemory.migrate(blockedRoot, true))
        assertFalse(File(blockedRoot, ".native-profile-v1").exists())
    }

    @Test fun legacyCoreKeepsItsLoadingPolicyAndEmptyInstallationAddsNoProfile() {
        NativeProfileMemory.migrate(temp.root, true)
        assertTrue(temp.root.listFiles().orEmpty().none { it.extension == "md" })
        val other = temp.newFolder()
        File(other, "CORE.md").writeText("Stored facts")
        NativeProfileMemory.migrate(other, false)
        assertEquals(ContextLoad.FULL, MemoryParser.parse(File(other, "CORE.md"), MemoryScope.GLOBAL)!!.contextLoad)
    }
}
