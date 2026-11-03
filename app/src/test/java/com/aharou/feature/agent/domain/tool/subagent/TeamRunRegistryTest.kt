package com.aharou.feature.agent.domain.tool.subagent

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TeamRunRegistryTest {
    private fun tasks() = buildJsonArray {
        addJsonObject { put("id", "first"); put("description", "implement"); put("prompt", "constraints") }
        addJsonObject { put("id", "future"); put("description", "review"); put("prompt", "verify") }
    }
    @Test fun futureTasksHaveIdentityAndQueuedMessagesAreConsumedOnce() {
        val registry = TeamRunRegistry()
        val run = registry.begin("parent", tasks())
        registry.bind(run, "first", "session-a")
        assertEquals(2, registry.roster("session-a").size)
        assertEquals("queued", registry.route("session-a", "future", "check invariant").state)
        assertEquals("check invariant", registry.bind(run, "future", "session-b"))
        assertEquals("", registry.bind(run, "future", "session-b"))
    }
    @Test fun runsWithSameParentCannotCommunicateAndStoppedTasksCannotResume() {
        val registry = TeamRunRegistry()
        val a = registry.begin("parent", tasks())
        val b = registry.begin("parent", tasks())
        registry.bind(a, "first", "a"); registry.bind(a, "future", "c")
        registry.bind(b, "first", "b")
        assertEquals("blocked", registry.route("a", "b", "cross run").state)
        registry.terminal("c", "completed")
        assertEquals("delivered", registry.route("a", "c", "question").state)
        registry.terminal("c", "stopped")
        assertEquals("blocked", registry.route("a", "c", "restart").state)
        assertFalse(registry.canMessage("c"))
    }
    @Test fun reworkKeepsLogicalIdentityButRetiresOldSession() {
        val registry = TeamRunRegistry()
        val run = registry.begin("parent", tasks())
        registry.bind(run, "first", "old"); registry.bind(run, "future", "reviewer")
        registry.reset(run, setOf("first"))
        assertEquals("blocked", registry.route("old", "future", "stale").state)
        assertEquals("queued", registry.route("reviewer", "first", "fix").state)
        assertEquals("fix", registry.bind(run, "first", "new"))
        assertEquals("new", registry.route("reviewer", "first", "question").sessionId)
    }
}
