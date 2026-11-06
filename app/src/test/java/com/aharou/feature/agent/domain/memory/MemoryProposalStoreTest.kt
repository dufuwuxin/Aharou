package com.aharou.feature.agent.domain.memory

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryProposalStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private fun memory(name: String): Memory {
        val file = File(temp.root, "$name.md")
        file.writeText(MemoryParser.format(name, "original", "original body $name", MemoryType.FACT, listOf("key")))
        return MemoryParser.parse(file, MemoryScope.PROJECT)!!
    }

    @Test fun proposalAndRejectNeverWriteInputOrArchive() {
        val input = memory("one")
        val original = input.file!!.readText()
        val store = MemoryProposalStore()
        val proposal = store.propose(listOf(input), "one", "reviewed", input.content, input.type, false)!!
        assertEquals(original, input.file.readText())
        assertFalse(File(temp.root, "archive").exists())
        store.reject(proposal.id)
        assertEquals(original, input.file.readText())
        assertEquals(MemoryProposalOutcome.MISSING, store.approve(proposal.id, proposal.revision))
    }

    @Test fun approvalWritesExactlyReviewedPayloadAndCannotApplyTwice() {
        val input = memory("one")
        val store = MemoryProposalStore()
        val draft = store.propose(listOf(input), "one", "reviewed", input.content, input.type, false)!!
        assertEquals(MemoryProposalOutcome.APPLIED, store.approve(draft.id, draft.revision))
        assertEquals(draft.payload, input.file!!.readText())
        assertEquals(MemoryProposalOutcome.MISSING, store.approve(draft.id, draft.revision))
    }

    @Test fun staleInputRejectsWholeMergeBeforeArchiving() {
        val a = memory("one")
        val b = memory("two")
        val store = MemoryProposalStore()
        val draft = store.propose(listOf(a, b), "joined", "reviewed", "body", MemoryType.WORKFLOW, true)!!
        b.file!!.appendText("\nchanged")
        assertEquals(MemoryProposalOutcome.STALE, store.approve(draft.id, draft.revision))
        assertFalse(File(temp.root, "joined.md").exists())
        assertFalse(File(temp.root, "archive").exists())
        assertTrue(a.file!!.exists())
        assertTrue(b.file.readText().endsWith("changed"))
    }

    @Test fun wrongDraftRevisionDoesNotWrite() {
        val a = memory("one")
        val store = MemoryProposalStore()
        val draft = store.propose(listOf(a), a.name, "reviewed", a.content, a.type, false)!!
        assertEquals(MemoryProposalOutcome.STALE, store.approve(draft.id, "different reviewed draft"))
        assertEquals(a.version, MemoryFileStorage.versionOf(a.file!!.readText()))
    }

    @Test fun mergeApprovalPreservesExactReviewedTypeAndPayload() {
        val a = memory("one")
        val b = memory("two")
        val store = MemoryProposalStore()
        val draft = store.propose(listOf(a, b), "joined", "reviewed", "body", MemoryType.WORKFLOW, true)!!
        assertEquals(MemoryProposalOutcome.APPLIED, store.approve(draft.id, draft.revision))
        assertEquals(draft.payload, File(temp.root, "joined.md").readText())
        assertFalse(a.file!!.exists())
        assertFalse(b.file!!.exists())
        assertEquals(2, File(temp.root, "archive").listFiles()!!.size)
    }
}
