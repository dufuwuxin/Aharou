package com.aharou.feature.agent.domain.session

import com.aharou.feature.agent.domain.model.AgentMode
import com.aharou.feature.settings.domain.model.FallbackTarget
import org.junit.Assert.*
import org.junit.Test

class DualModelSettingsTest {
    private val planning = FallbackTarget("same", "plan:latest")
    private val execution = FallbackTarget("same", "build")
    private val settings = DualModelSettings(true, planning, execution)

    @Test fun disabledDoesNotRouteEvenWhenIncomplete() {
        AgentMode.entries.forEach { assertNull(DualModelSettings().target(it)) }
        assertNull(settings.copy(enabled = false).target(AgentMode.PLAN))
    }
    @Test fun planUsesPlanning() { assertEquals(planning, settings.target(AgentMode.PLAN)) }
    @Test fun buildUsesExecution() { assertEquals(execution, settings.target(AgentMode.BUILD)) }
    @Test fun autoUsesExecution() { assertEquals(execution, settings.target(AgentMode.AUTO)) }
    @Test fun allowUsesExecution() { assertEquals(execution, settings.target(AgentMode.ALLOW)) }
    @Test fun incompleteEnabledFailsForEveryMode() {
        AgentMode.entries.forEach { mode ->
            assertTrue(runCatching { settings.copy(execution = null).target(mode) }.isFailure)
            assertTrue(runCatching { settings.copy(planning = FallbackTarget("", "x")).target(mode) }.isFailure)
        }
    }
    @Test fun sameProviderDoesNotCollapseModels() {
        assertNotEquals(settings.target(AgentMode.PLAN), settings.target(AgentMode.AUTO))
        assertTrue(settings.isConfigured)
    }
    @Test fun encodingPreservesColonModelAndNull() {
        assertEquals(planning, DualModelSettings.decodeTarget(DualModelSettings.encodeTarget(planning)))
        assertNull(DualModelSettings.decodeTarget(DualModelSettings.encodeTarget(null)))
        assertEquals("same:plan:latest", DualModelSettings.encodeTarget(planning))
    }
    @Test fun malformedEncodingFailsRatherThanBecomingMissing() {
        listOf("provider", ":model", "provider:").forEach {
            assertTrue(runCatching { DualModelSettings.decodeTarget(it) }.isFailure)
        }
    }
    @Test fun disabledRetainsPartialChoices() {
        val partial = DualModelSettings(planning = planning)
        assertFalse(partial.isConfigured)
        assertEquals(planning, partial.planning)
        assertNull(partial.target(AgentMode.PLAN))
    }
}
