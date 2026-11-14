package com.aharou.feature.editor.domain

import com.aharou.feature.agent.domain.tool.FileVersionRegistry
import com.aharou.feature.agent.domain.tool.WorkspaceFileLock
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.sha256Fingerprint
import io.mockk.*
import java.nio.charset.Charset
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class EditorDocumentStoreTest {
    private fun access(disk: MutableMap<String, String>): FileAccessProvider = mockk<FileAccessProvider>().also { access ->
        every { access.toDisplayPath(any()) } answers { firstArg() }
        every { access.fingerprint(any()) } answers { disk[firstArg<String>()]?.let { sha256Fingerprint(it.toByteArray()) } }
        every { access.readFile(any()) } answers { disk.getValue(firstArg()) }
        every { access.writeFile(any(), any(), any(), any<Charset>()) } answers {
            disk[firstArg()] = secondArg(); Unit
        }
    }

    @Test fun openAndSaveNeverRefreshAnotherSessionsOldRead() = runTest {
        val versions = FileVersionRegistry()
        val disk = mutableMapOf("/a" to "old")
        val access = access(disk)
        val store = EditorDocumentStore(WorkspaceFileLock(), versions)
        val old = sha256Fingerprint("old".toByteArray())
        versions.record("member", "/a", old)
        store.observe("/a", "old".toByteArray(), access)
        assertNull(versions.lastWriter("/a"))
        assertEquals(old, versions.get("member", "/a"))
        assertEquals(EditorDocumentStore.WriteResult.Saved, store.save("/a", "human", access))
        assertEquals(old, versions.get("member", "/a"))
        assertNotEquals(versions.get("member", "/a"), access.fingerprint("/a"))
    }

    @Test fun toolWriteWhileEditorWaitsIsComparedInsideTheSameLock() = runTest {
        val locks = WorkspaceFileLock()
        val disk = mutableMapOf("/a" to "old")
        val access = access(disk)
        val store = EditorDocumentStore(locks, FileVersionRegistry())
        store.observe("/a", "old".toByteArray(), access)
        val acquired = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val tool = launch { locks.withLock("/a") { acquired.complete(Unit); release.await(); disk["/a"] = "tool" } }
        acquired.await()
        val save = async(start = CoroutineStart.UNDISPATCHED) { store.save("/a", "human", access) }
        assertFalse(save.isCompleted)
        release.complete(Unit)
        tool.join()
        assertEquals(EditorDocumentStore.WriteResult.Conflict("tool"), save.await())
        assertEquals("tool", disk["/a"])
        assertEquals(0, locks.trackedKeyCount())
    }

    @Test fun forceSaveStillWaitsForTheSharedToolLock() = runTest {
        val locks = WorkspaceFileLock()
        val disk = mutableMapOf("/a" to "old")
        val access = access(disk)
        val store = EditorDocumentStore(locks, FileVersionRegistry())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val tool = launch { locks.withLock("/a") { entered.complete(Unit); release.await() } }
        entered.await()
        val save = async(start = CoroutineStart.UNDISPATCHED) { store.save("/a", "forced", access, force = true) }
        assertFalse(save.isCompleted)
        release.complete(Unit)
        tool.join()
        assertEquals(EditorDocumentStore.WriteResult.Saved, save.await())
        assertEquals("forced", disk["/a"])
        assertEquals(0, locks.trackedKeyCount())
    }

    @Test fun staleWorkspaceOverrideUsesTheActualDefaultSshProfile() {
        val ssh = com.aharou.feature.agent.domain.container.ContainerProfile.BUILTIN_UBUNTU.copy(id = "ssh", mode = com.aharou.feature.settings.data.repository.ExecutionMode.REMOTE_SSH)
        val selected = EditorWorkspaceIdentity.resolve("ssh", listOf(ssh), mapOf("/workspace" to "deleted-profile"), "/workspace")
        assertEquals(ssh, selected)
        assertNotEquals(EditorWorkspaceIdentity.key("/workspace", selected.id, "host-a"), EditorWorkspaceIdentity.key("/workspace", selected.id, "host-b"))
    }

    @Test fun samePathOnDifferentEndpointsHasIndependentSameContentDrafts() {
        val one = EditorWorkspaceIdentity.key("/workspace", "ssh", "host-a")
        val two = EditorWorkspaceIdentity.key("/workspace", "ssh", "host-b")
        assertNotEquals(one, two)
        val a = EditorSessionManager.forWorkspace(one)
        val b = EditorSessionManager.forWorkspace(two)
        a.saveSnapshot("file.kt", EditorSessionManager.Snapshot("draft-a", true, 0, 0, 0, "same"))
        b.saveSnapshot("file.kt", EditorSessionManager.Snapshot("draft-b", true, 0, 0, 0, "same"))
        repeat(3) {
            assertEquals("draft-a", EditorSessionManager.forWorkspace(one).snapshot("file.kt")?.content)
            assertEquals("draft-b", EditorSessionManager.forWorkspace(two).snapshot("file.kt")?.content)
        }
        a.forget("file.kt"); b.forget("file.kt")
    }

    @Test fun workspaceDocumentsAndDirtyDraftsAreIndependent() = runTest {
        val one = access(mutableMapOf("/a" to "one"))
        val two = access(mutableMapOf("/a" to "two"))
        val store = EditorDocumentStore(WorkspaceFileLock(), FileVersionRegistry())
        store.observe("/a", "one".toByteArray(), one, "workspace-one")
        store.observe("/a", "two".toByteArray(), two, "workspace-two")
        assertSame(one, store.accessFor("/a", "workspace-one"))
        assertSame(two, store.accessFor("/a", "workspace-two"))
        val first = EditorSessionManager.forWorkspace("test-one")
        val second = EditorSessionManager.forWorkspace("test-two")
        first.saveSnapshot("/a", EditorSessionManager.Snapshot("draft", true, 0, 0, 0, "one"))
        assertNull(second.snapshot("/a"))
        assertEquals("draft", first.snapshot("/a")?.content)
        first.forget("/a")
    }
}
