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
        assertEquals("NOT_SIBLING", registry.route("a", "b", "cross run").reason)
        registry.terminal("c", "completed")
        // future/c 无 stage，TEAM_MEMBER_FINISHED 检查被跳过；completed 成员仍可被直发
        assertEquals("delivered", registry.route("a", "c", "question").state)
        registry.terminal("c", "stopped")
        assertEquals("blocked", registry.route("a", "c", "restart").state)
        assertFalse(registry.canMessage("c"))
    }
    @Test fun queuedAndDirectMessagesShareBudgetAndAliasesCannotBypassIt() {
        val registry = TeamRunRegistry()
        val pipeline = buildJsonArray {
            listOf("first", "future", "third", "fourth").forEach { id -> addJsonObject { put("id", id) } }
        }
        val run = registry.begin("parent", pipeline)
        registry.bind(run, "first", "a")
        assertEquals("queued", registry.route("a", "future", "one").state)
        assertEquals("blocked", registry.route("a", "future", " one ").state)
        assertEquals("TEAM_DUPLICATE_MESSAGE", registry.route("a", "future", " one ").reason)
        assertEquals("one", registry.bind(run, "future", "b"))
        var deliveries = 0
        // two 成功投递给 b：消耗 a→b 同 pair 第 2 条（HARD_PAIR_MAX=2 满）
        assertEquals("delivered", registry.route("a", "b", "two") { deliveries++; true }.state)
        assertEquals(1, deliveries)
        // three 会是 a→b 同 pair 第 3 条，撞 TEAM_PAIR_LIMIT
        assertEquals("blocked", registry.route("a", "future", "three") { true }.state)
        assertEquals("TEAM_PAIR_LIMIT", registry.route("a", "future", "three") { true }.reason)
        assertEquals("queued", registry.route("a", "third", "three").state)
        assertEquals("queued", registry.route("a", "third", "four").state)
        // five 是 a 的第 5 条，超出 HARD_TOTAL_MAX=4
        assertEquals("blocked", registry.route("a", "fourth", "five").state)
        assertEquals("TEAM_MESSAGE_LIMIT", registry.route("a", "fourth", "five").reason)
        // third 只收到 three；four 虽先于 five 调用，但它是 a 的第 4 条、入队即达总配额上限后被拒绝
        assertEquals("three", registry.bind(run, "third", "c"))
        assertEquals("", registry.bind(run, "fourth", "d"))
    }
    @Test fun failedDeliveryAndInvalidMessagesDoNotConsumeBudget() {
        val registry = TeamRunRegistry()
        val run = registry.begin("parent", tasks())
        registry.bind(run, "first", "a"); registry.bind(run, "future", "b")
        assertEquals("blocked", registry.route("a", "b", "x".repeat(301)).state)
        assertEquals("INVALID_ARGS", registry.route("a", "b", "x".repeat(301)).reason)
        assertEquals("blocked", registry.route("a", "b", " ").state)
        assertEquals("unavailable", registry.route("a", "b", "retry") { false }.state)
        assertEquals("delivered", registry.route("a", "b", "retry") { true }.state)
        assertEquals("delivered", registry.route("a", "b", "\uD83D\uDE00".repeat(300)) { true }.state)
        registry.terminal("a", "completed")
        assertEquals("blocked", registry.route("a", "b", "late").state)
    }
    @Test fun concurrentRoutesCannotOverfillPendingMailbox() {
        val registry = TeamRunRegistry()
        val run = registry.begin("parent", tasks())
        registry.bind(run, "first", "a")
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val futures = (0 until 32).map { n -> pool.submit<String> {
                registry.route("a", "future", "message-$n").state
            } }
            assertEquals(2, futures.count { it.get(5, java.util.concurrent.TimeUnit.SECONDS) == "queued" })
            assertEquals(2, registry.bind(run, "future", "b").split("\n\n").size)
        } finally { pool.shutdownNow() }
    }
    @Test fun parentRestartRestoresOnlyRestartedMembersBudget() {
        val registry = TeamRunRegistry()
        val bus = com.aharou.feature.agent.domain.subagent.SubAgentEventBus(
            com.aharou.feature.agent.domain.subagent.MaxRunningSource { 5 },
            com.aharou.feature.agent.domain.subagent.CommunicationEnabledSource { true }, registry
        )
        val run = registry.begin("parent", tasks())
        registry.bind(run, "first", "a"); registry.bind(run, "future", "b")
        listOf("a", "b").forEach { bus.emit(com.aharou.feature.agent.domain.subagent.SubAgentEvent(
            it, "parent", com.aharou.feature.agent.domain.subagent.SubAgentEventType.SPAWNED
        )) }
        repeat(2) { n ->
            assertEquals("delivered", registry.route("a", "b", "a$n") { bus.sendToSibling("a", it, "a$n") }.state)
            assertEquals("delivered", registry.route("b", "a", "b$n") { bus.sendToSibling("b", it, "b$n") }.state)
        }
        bus.emit(com.aharou.feature.agent.domain.subagent.SubAgentEvent(
            "a", "parent", com.aharou.feature.agent.domain.subagent.SubAgentEventType.COMPLETED
        ))
        assertEquals("blocked", registry.route("a", "b", "late").state)
        bus.emit(com.aharou.feature.agent.domain.subagent.SubAgentEvent(
            "a", "parent", com.aharou.feature.agent.domain.subagent.SubAgentEventType.SPAWNED
        ))
        assertEquals("delivered", registry.route("a", "b", "a0") { bus.sendToSibling("a", it, "a0") }.state)
        assertEquals("blocked", registry.route("b", "a", "new").state)
        assertEquals("TEAM_PAIR_LIMIT", registry.route("b", "a", "new").reason)
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
