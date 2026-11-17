package com.aharou.feature.agent.domain.container

import org.junit.Assert.*
import org.junit.Test

class RemoteBuiltinAssetStoreTest {
    private class MemoryAccess : RemoteBuiltinAssetStore.Access {
        val files = linkedMapOf<String, ByteArray>()
        var writes = 0
        var fail = false
        var corruptResourceRead = false
        override fun namespaceExists(path: String) = files.keys.any { it.startsWith("$path/") }
        override fun read(path: String): ByteArray? = if (corruptResourceRead && path.startsWith("builtin-skills/") && path in files)
            "corrupted read".toByteArray() else files[path]
        override fun write(path: String, bytes: ByteArray) {
            if (fail) error("fixture write failure")
            files[path] = bytes
            writes++
        }
    }
    private fun hash(text: String) = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun reconnectOfSameVersionKeepsRemoteDocumentEditsAndNewVersionDeploys() {
        val access = MemoryAccess()
        RemoteBuiltinAssetStore(access, 1).deploy("docs", mapOf("guide/test.md" to "seed".toByteArray()))
        access.files["docs/guide/test.md"] = "user edit".toByteArray()
        val writes = access.writes
        RemoteBuiltinAssetStore(access, 1).deploy("docs", mapOf("guide/test.md" to "seed".toByteArray()))
        assertEquals("user edit", access.files.getValue("docs/guide/test.md").toString(Charsets.UTF_8))
        assertEquals(writes, access.writes)
        RemoteBuiltinAssetStore(access, 2).deploy("docs", mapOf("guide/test.md" to "new seed".toByteArray()))
        assertEquals("new seed", access.files.getValue("docs/guide/test.md").toString(Charsets.UTF_8))
    }

    @Test fun knownOldAgentUpgradesButCustomAndDeletedAgentsRemainUserOwned() {
        val access = MemoryAccess()
        access.files["agents/known.md"] = "old".toByteArray()
        access.files["agents/custom.md"] = "custom".toByteArray()
        val files = mapOf("known.md" to "new".toByteArray(), "custom.md" to "new".toByteArray())
        RemoteBuiltinAssetStore(access, 2).deploy("agents", files,
            mapOf("known.md" to setOf(hash("old")), "custom.md" to setOf(hash("old"))), preserveUnknown = true)
        assertEquals("new", access.files.getValue("agents/known.md").toString(Charsets.UTF_8))
        assertEquals("custom", access.files.getValue("agents/custom.md").toString(Charsets.UTF_8))
        access.files.remove("agents/known.md")
        RemoteBuiltinAssetStore(access, 3).deploy("agents", files, preserveUnknown = true)
        assertFalse("agents/known.md" in access.files)
    }

    @Test fun legacyHostWithoutReceiptPreservesHistoricalDeletionButFreshHostSeeds() {
        val legacy = MemoryAccess()
        legacy.files["agents/custom.md"] = "user owned".toByteArray()
        val files = mapOf("deleted.md" to "new default".toByteArray())
        val history = mapOf("deleted.md" to setOf(hash("old default")))
        RemoteBuiltinAssetStore(legacy, 2).deploy("agents", files, history, preserveUnknown = true)
        assertFalse("agents/deleted.md" in legacy.files)
        RemoteBuiltinAssetStore(legacy, 3).deploy("agents", files, history, preserveUnknown = true)
        assertFalse("agents/deleted.md" in legacy.files)
        val fresh = MemoryAccess()
        RemoteBuiltinAssetStore(fresh, 2).deploy("agents", files, history, preserveUnknown = true)
        assertEquals("new default", fresh.files.getValue("agents/deleted.md").toString(Charsets.UTF_8))
    }

    @Test fun resourceReadbackFailureNeverPublishesSuccessReceiptAndCanRetry() {
        val access = MemoryAccess().apply { corruptResourceRead = true }
        val files = mapOf("run.py" to "trusted".toByteArray())
        try { RemoteBuiltinAssetStore(access, 1).deploy("builtin-skills/check", files, requireExact = true); fail() }
        catch (expected: IllegalStateException) { assertEquals("BUILTIN_SKILL_RESOURCES_CHANGED", expected.message) }
        assertFalse(access.files.keys.any { it.startsWith("builtin-seed-state/") })
        access.corruptResourceRead = false
        RemoteBuiltinAssetStore(access, 1).deploy("builtin-skills/check", files, requireExact = true)
        assertTrue(access.files.keys.any { it.startsWith("builtin-seed-state/") })
    }

    @Test fun changedScriptIsNotOverwrittenAfterSameVersionDeployment() {
        val access = MemoryAccess()
        val files = mapOf("run.py" to "trusted".toByteArray())
        RemoteBuiltinAssetStore(access, 1).deploy("builtin-skills/check", files, requireExact = true)
        access.files["builtin-skills/check/run.py"] = "changed".toByteArray()
        val writes = access.writes
        try { RemoteBuiltinAssetStore(access, 1).deploy("builtin-skills/check", files, requireExact = true); fail() }
        catch (expected: IllegalStateException) { assertEquals("BUILTIN_SKILL_RESOURCES_CHANGED", expected.message) }
        assertEquals(writes, access.writes)
        assertEquals("changed", access.files.getValue("builtin-skills/check/run.py").toString(Charsets.UTF_8))
    }

    @Test fun failureDoesNotRecordSuccessAndDifferentHostsHaveIndependentReceipts() {
        val first = MemoryAccess().apply { fail = true }
        val files = mapOf("guide.md" to "seed".toByteArray())
        try { RemoteBuiltinAssetStore(first, 1).deploy("docs", files); fail() } catch (_: IllegalStateException) { }
        assertTrue(first.files.isEmpty())
        first.fail = false
        RemoteBuiltinAssetStore(first, 1).deploy("docs", files)
        val second = MemoryAccess()
        RemoteBuiltinAssetStore(second, 1).deploy("docs", files)
        assertEquals("seed", second.files.getValue("docs/guide.md").toString(Charsets.UTF_8))
    }
}
