package com.aharou.feature.agent.domain.tool

import org.junit.Assert.*
import org.junit.Test

class FileVersionRegistryTest {
    @Test fun externalSaveNeverRewritesSessionObservations() {
        val versions = FileVersionRegistry()
        versions.record("a", "file", "old")
        versions.record("b", "other", "other-version")
        versions.recordExternal("file", "new")
        assertEquals("old", versions.get("a", "file"))
        assertNull(versions.get("b", "file"))
        assertEquals("用户/编辑器", versions.lastWriter("file"))
        versions.record("a", "file", "new")
        assertEquals("new", versions.get("a", "file"))
    }
}
