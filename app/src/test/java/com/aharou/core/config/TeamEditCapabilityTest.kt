package com.aharou.core.config

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class TeamEditCapabilityTest {
    @After fun reset() { TeamEditCapability.revoke() }
    @Test fun disabledByDefaultAndRevocationRejectsOldAddress() {
        TeamEditCapability.revoke()
        assertNull(TeamEditCapability.address.value)
        val address = TeamEditCapability.issue("/project/a")
        assertEquals("ok", TeamEditCapability.use(address, "/project/a") { "ok" })
        TeamEditCapability.revoke()
        assertTrue(runCatching { TeamEditCapability.use(address, "/project/a") { "not allowed" } }.isFailure)
    }
    @Test fun newGrantAndAnotherWorkspaceCannotReuseTheOldCapability() {
        val address = TeamEditCapability.issue("/project/a")
        assertTrue(runCatching { TeamEditCapability.use(address, "/project/b") { true } }.isFailure)
        TeamEditCapability.issue("/project/a")
        assertTrue(runCatching { TeamEditCapability.use(address, "/project/a") { true } }.isFailure)
    }
    @Test fun onlyTeamControlFieldsAreAllowedAndAddressEncodingRoundTrips() {
        val address = TeamEditCapability.issue("/project/a")
        val field = "teams.project:6162.routes"
        assertEquals(field, TeamEditCapability.use(TeamEditCapability.childAddress(address, field), "/project/a") { it })
        for (path in listOf("providers.a.api_key", "soul.name", "team.stages.project:6162:1.file", "teams.project:6162.remove")) {
            assertFalse(TeamEditCapability.allowed(path))
            assertTrue(runCatching { TeamEditCapability.use(TeamEditCapability.childAddress(address, path), "/project/a") { it } }.isFailure)
        }
    }
}
