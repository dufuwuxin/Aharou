package com.aharou.feature.agent.domain.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch

class SubAgentWriteLeaseTest {
    @Test
    fun registerRetainsExistingReadOnlyLeaseBeyondTwoHundredSessions() {
        val leases = SubAgentWriteLease()
        leases.register("first", emptySet())
        repeat(202) { leases.register("session-$it", setOf("file-$it")) }
        assertEquals(emptySet<String>(), leases.pathsFor("first"))
        assertNotNull(WriteLease.denialReason(leases.pathsFor("first"), "secret.txt"))
    }

    @Test
    fun grantRetainsExistingReadOnlyLeaseBeyondTwoHundredSessions() {
        val leases = SubAgentWriteLease()
        leases.register("first", emptySet())
        repeat(202) { leases.grant("session-$it", setOf("file-$it")) }
        assertEquals(emptySet<String>(), leases.pathsFor("first"))
    }

    @Test
    fun concurrentGrantsDoNotLosePaths() {
        val leases = SubAgentWriteLease()
        leases.register("first", emptySet())
        val start = CountDownLatch(1)
        val threads = List(32) { index ->
            Thread {
                start.await()
                leases.grant("first", setOf("file-$index"))
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join() }
        assertEquals((0 until 32).map { "file-$it" }.toSet(), leases.pathsFor("first"))
    }

    @Test
    fun grantMergesPathsAndReleaseOnlyRemovesItsOwnSession() {
        val leases = SubAgentWriteLease()
        leases.register("first", setOf("one.kt"))
        leases.register("second", emptySet())
        assertEquals(setOf("one.kt", "two.kt"), leases.grant("first", setOf("two.kt")))
        leases.release("first")
        assertNull(leases.pathsFor("first"))
        assertEquals(emptySet<String>(), leases.pathsFor("second"))
    }
}
