package com.aharou.feature.agent.domain.workflow

import org.junit.Assert.*
import org.junit.Test

class ContextWatermarksTest {
    @Test fun configuredMaxIsAHardLimitBelowTheProviderWindow() {
        val limits = ContextWatermarks.from(10000, 60, 90)
        assertEquals(6000, limits.warnTokens)
        assertEquals(9000, limits.maxTokens)
        assertFalse(limits.exceeded(8999))
        assertTrue(limits.exceeded(9000))
    }

    @Test fun invalidOrderingDoesNotPutWarningAfterTheHardLimit() {
        assertEquals(ContextWatermarks(800, 800), ContextWatermarks.from(1000, 95, 80))
    }

    @Test fun unknownAndTinyBudgetsDoNotDisableTheHardLimit() {
        assertEquals(ContextWatermarks(1, 1), ContextWatermarks.from(0, 60, 90))
        assertEquals(ContextWatermarks(1, 1), ContextWatermarks.from(1, 1, 1))
    }

    @Test fun percentagesAreBoundedAndLargeBudgetsDoNotOverflow() {
        assertEquals(ContextWatermarks(10, 1000), ContextWatermarks.from(1000, -10, 200))
        assertEquals(Int.MAX_VALUE, ContextWatermarks.from(Int.MAX_VALUE, 100, 100).maxTokens)
    }
}
