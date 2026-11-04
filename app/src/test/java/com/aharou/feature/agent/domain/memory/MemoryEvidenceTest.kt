package com.aharou.feature.agent.domain.memory

import com.aharou.core.memory.AharouMemoryStore
import com.aharou.core.memory.MemoryUsageStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryEvidenceTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun relevantUnreadMemoryOutranksFrequentlyReadUnrelatedMemory() {
        val source = mockk<GlobalMemorySource>()
        val usage = mockk<MemoryUsageStore>(relaxed = true)
        val relevant = Memory("PRoot", "PRoot", MemoryScope.GLOBAL, content = "container")
        val unrelated = Memory("gardening", "gardening", MemoryScope.GLOBAL, content = "plants")
        every { source.listMemories() } returns listOf(unrelated, relevant)
        every { usage.useCount("GLOBAL", "gardening") } returns 100
        val repository = MemoryRepository(source, mockk(), mockk(), mockk(), usage)

        assertEquals(listOf("PRoot"), repository.listMemoriesForPrompt(null, "PRoot", 1).map { it.name })
    }

    @Test
    fun identityKeepsItsQuotaEvenWhenUnrelated() {
        val source = mockk<GlobalMemorySource>()
        val identity = Memory("rule", "rule", MemoryScope.GLOBAL, content = "rule", type = MemoryType.IDENTITY)
        val fact = Memory("PRoot", "PRoot", MemoryScope.GLOBAL, content = "container")
        every { source.listMemories() } returns listOf(fact, identity)
        val repository = MemoryRepository(source, mockk(), mockk(), mockk(), mockk(relaxed = true))

        assertEquals(listOf("rule"), repository.listMemoriesForPrompt(null, "PRoot", 1).map { it.name })
    }

    @Test
    fun pendingIdentityCannotDisplaceConfirmedIdentity() {
        val source = mockk<GlobalMemorySource>()
        val confirmed = Memory("rule", "rule", MemoryScope.GLOBAL, content = "rule", type = MemoryType.IDENTITY)
        val candidate = Memory("PRoot", "PRoot", MemoryScope.GLOBAL, content = "candidate", type = MemoryType.IDENTITY, pending = true)
        every { source.listMemories() } returns listOf(candidate, confirmed)
        val repository = MemoryRepository(source, mockk(), mockk(), mockk(), mockk(relaxed = true))

        assertEquals(listOf("rule"), repository.listMemoriesForPrompt(null, "PRoot", 1).map { it.name })
        assertEquals(listOf("rule", "PRoot"), repository.listMemoriesForPrompt(null, "PRoot", 2).map { it.name })
    }

    @Test
    fun distillationDoesNotOverwriteConfirmedEntry() {
        val repository = mockk<MemoryRepository>(relaxed = true)
        val store = mockk<AharouMemoryStore>()
        every { store.dir } returns tempFolder.root
        val entry = DistilledEntryParser.parse("- [fact] 构建使用 JDK 17。").single()
        every { repository.findMemory(entry.name, null, MemoryScope.GLOBAL) } returns
            Memory(entry.name, "confirmed", MemoryScope.GLOBAL, content = "original", pending = false)

        assertEquals(0, DistilledMemoryWriter(repository, store).write(listOf(entry)))
        verify(exactly = 0) { repository.writeMemory(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun distillationDoesNotOverwriteSimilarManualPendingEntry() {
        val repository = mockk<MemoryRepository>(relaxed = true)
        val store = mockk<AharouMemoryStore>()
        every { store.dir } returns tempFolder.root
        val entry = DistilledEntryParser.parse("- [fact] 构建使用 JDK 17。").single()
        every { repository.findMemory(entry.name, null, MemoryScope.GLOBAL) } returns null
        every { repository.findSimilarMemories(entry.name, entry.description, null, 1, MemoryScope.GLOBAL) } returns
            listOf(Memory("manual", "build", MemoryScope.GLOBAL, content = "original", pending = true))

        assertEquals(0, DistilledMemoryWriter(repository, store).write(listOf(entry)))
        verify(exactly = 0) { repository.writeMemory(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun writerEnforcesPendingAndOptimisticLockForNewCandidate() {
        val repository = mockk<MemoryRepository>(relaxed = true)
        val store = mockk<AharouMemoryStore>()
        every { store.dir } returns tempFolder.root
        val entry = DistilledEntryParser.parse("- [fact] 构建使用 JDK 17。").single().copy(pending = false)
        every { repository.findMemory(entry.name, null, MemoryScope.GLOBAL) } returns null
        every { repository.findSimilarMemories(entry.name, entry.description, null, 1, MemoryScope.GLOBAL) } returns emptyList()
        every { repository.writeMemory(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns MemoryWriteResult.Success

        assertEquals(1, DistilledMemoryWriter(repository, store).write(listOf(entry)))
        verify {
            repository.writeMemory(
                name = entry.name,
                description = entry.description,
                content = entry.content,
                scope = MemoryScope.GLOBAL,
                projectRoot = null,
                type = entry.type,
                pending = true,
                keywords = entry.keywords,
                expectedVersion = MemorySource.VERSION_NEW,
            )
        }
    }
}
