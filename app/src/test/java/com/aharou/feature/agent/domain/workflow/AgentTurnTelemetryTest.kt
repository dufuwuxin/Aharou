package com.aharou.feature.agent.domain.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentTurnTelemetryTest {
    @Test fun cacheHitPercentageKeepsFractionalProviderUsage() {
        assertEquals(2.5173333333, cacheHitPercentage(300_000, 7_552)!!, 0.000001)
        assertEquals(0.0, cacheHitPercentage(100, 0)!!, 0.0)
        assertNull(cacheHitPercentage(100, -1))
        assertNull(cacheHitPercentage(0, 10))
    }
}
