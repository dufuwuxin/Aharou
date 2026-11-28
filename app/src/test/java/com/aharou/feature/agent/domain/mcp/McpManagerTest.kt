package com.aharou.feature.agent.domain.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpManagerTest {
    @Test
    fun idleEvictionAppliesOnlyToExpiredNonKeepAliveStdioServers() {
        assertTrue(isIdleMcpServerEvictable(isStdio = true, keepAlive = false, hasActiveCalls = false, idleMillis = 601_000, timeoutMillis = 600_000))
        assertFalse(isIdleMcpServerEvictable(isStdio = false, keepAlive = false, hasActiveCalls = false, idleMillis = 900_000, timeoutMillis = 600_000))
        assertFalse(isIdleMcpServerEvictable(isStdio = true, keepAlive = true, hasActiveCalls = false, idleMillis = 900_000, timeoutMillis = 600_000))
        assertFalse(isIdleMcpServerEvictable(isStdio = true, keepAlive = false, hasActiveCalls = true, idleMillis = 900_000, timeoutMillis = 600_000))
        assertFalse(isIdleMcpServerEvictable(isStdio = true, keepAlive = false, hasActiveCalls = false, idleMillis = 600_000, timeoutMillis = 600_000))
    }

    @Test
    fun evictionClaimAndNewCallAreMutuallyExclusive() {
        val gate = McpCallEvictionGate()

        assertTrue(gate.claimEviction("server"))
        assertFalse(gate.beginCall("server"))
        gate.finishEviction("server")
        assertTrue(gate.beginCall("server"))
        assertFalse(gate.claimEviction("server"))
        gate.endCall("server")
        assertTrue(gate.claimEviction("server"))
    }
}
