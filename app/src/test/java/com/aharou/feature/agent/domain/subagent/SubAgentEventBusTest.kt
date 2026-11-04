package com.aharou.feature.agent.domain.subagent

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 活跃子代理集合的维护语义：并发槽位靠它计数，只要有一条路径忘了交回槽位，
 * 用户就会在没有任何子代理运行时被告知「已达上限」，直到进程重启。
 */
class SubAgentEventBusTest {

    /** 事件总线依赖 [MaxRunningSource] 与 [CommunicationEnabledSource]，测试直接给固定值。 */
    private fun busWith(
        limit: Int = SubAgentEventBus.DEFAULT_MAX_RUNNING,
        commEnabled: Boolean = true,
    ) = SubAgentEventBus(MaxRunningSource { limit }, CommunicationEnabledSource { commEnabled })

    private fun spawn(bus: SubAgentEventBus, id: String) = bus.emit(
        SubAgentEvent(subSessionId = id, parentSessionId = "parent", type = SubAgentEventType.SPAWNED)
    )

    @Test
    fun siblingMessage_requiresActiveSameParentAndEnabledCommunication() {
        val bus = busWith()
        listOf("a", "b").forEach { spawn(bus, it) }
        assertTrue(bus.sendToSibling("a", "b", "hello"))
        assertFalse(bus.sendToSibling("a", "a", "self"))
        assertFalse(bus.sendToSibling("a", "unknown", "unknown"))
        bus.emit(SubAgentEvent("other", "other-parent", SubAgentEventType.SPAWNED))
        assertFalse(bus.sendToSibling("a", "other", "foreign"))
        val disabled = busWith(commEnabled = false)
        listOf("a", "b").forEach { spawn(disabled, it) }
        assertFalse(disabled.sendToSibling("a", "b", "disabled"))
    }

    @Test
    fun siblingMessage_rejectsInactiveSenderAndRecipientForEveryTerminalPath() {
        listOf(SubAgentEventType.COMPLETED, SubAgentEventType.FAILED,
            SubAgentEventType.STOPPED, SubAgentEventType.DELETED).forEach { terminal ->
            val bus = busWith()
            listOf("a", "b").forEach { spawn(bus, it) }
            bus.emit(SubAgentEvent("b", "parent", terminal))
            bus.registerSibling("b", "parent")
            assertFalse("recipient $terminal", bus.sendToSibling("a", "b", "late"))
            assertFalse("sender $terminal", bus.sendToSibling("b", "a", "late"))
        }
        val bus = busWith()
        listOf("a", "b").forEach { spawn(bus, it) }
        bus.release("b")
        assertFalse(bus.sendToSibling("a", "b", "released"))
        bus.registerSibling("registered-only", "parent")
        assertFalse(bus.sendToSibling("a", "registered-only", "inactive"))
    }

    @Test
    fun siblingMessage_enforcesUnicodeCodePointLimit() {
        val bus = busWith()
        listOf("a", "b").forEach { spawn(bus, it) }
        assertFalse(bus.sendToSibling("a", "b", " "))
        assertFalse(bus.sendToSibling("a", "b", "x".repeat(301)))
        assertFalse(bus.sendToSibling("a", "b", "\uD83D\uDE00".repeat(301)))
        assertTrue(bus.sendToSibling("a", "b", "\uD83D\uDE00".repeat(300)))
        assertTrue(bus.sendToSibling("a", "b", "x".repeat(300)))
    }

    @Test
    fun siblingMessage_duplicateDoesNotDeliverOrSpendBudget() = runBlocking {
        val bus = busWith()
        listOf("a", "b", "c", "d").forEach { spawn(bus, it) }
        val received = mutableListOf<SubAgentEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            bus.events.collect { if (it.fromSibling) received += it }
        }
        try {
            assertTrue(bus.sendToSibling("a", "b", "one"))
            assertFalse(bus.sendToSibling("a", "b", "one"))
            assertTrue(bus.sendToSibling("a", "b", "two"))
            assertFalse(bus.sendToSibling("a", "b", "three"))
            assertTrue(bus.sendToSibling("a", "c", "three"))
            assertTrue(bus.sendToSibling("a", "c", "four"))
            assertFalse(bus.sendToSibling("a", "d", "five"))
            kotlinx.coroutines.yield()
            assertEquals(4, received.size)
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun siblingMessage_spawnResetsOnlySenderBudgetAndRegisterDoesNotReset() {
        val bus = busWith()
        listOf("a", "b").forEach { spawn(bus, it) }
        repeat(2) {
            assertTrue(bus.sendToSibling("a", "b", "a$it"))
            assertTrue(bus.sendToSibling("b", "a", "b$it"))
        }
        bus.registerSibling("a", "parent")
        bus.registerSibling("b", "parent")
        assertFalse(bus.sendToSibling("a", "b", "new"))
        bus.emit(SubAgentEvent("a", "parent", SubAgentEventType.COMPLETED))
        spawn(bus, "a")
        assertTrue(bus.sendToSibling("a", "b", "a0"))
        assertFalse(bus.sendToSibling("b", "a", "new"))
    }

    @Test
    fun siblingMessage_concurrentSendsCannotExceedPairOrTotalBudget() {
        val bus = busWith()
        listOf("a", "b", "c", "d").forEach { spawn(bus, it) }
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until 48).map { n ->
                pool.submit<Pair<String, Boolean>> {
                    start.await()
                    val target = listOf("b", "c", "d")[n % 3]
                    target to bus.sendToSibling("a", target, "message-$n")
                }
            }
            start.countDown()
            val sent = futures.map { it.get(5, TimeUnit.SECONDS) }.filter { it.second }
            assertEquals(4, sent.size)
            assertTrue(sent.groupingBy { it.first }.eachCount().values.all { it <= 2 })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun siblingMessage_consumerRechecksStateAfterSendBeforeDelivery() {
        val bus = busWith()
        listOf("a", "b").forEach { spawn(bus, it) }
        assertTrue(bus.sendToSibling("a", "b", "sent-before-completion"))
        assertTrue(bus.canReceiveSiblingMessage("a", "b"))
        bus.emit(SubAgentEvent("b", "parent", SubAgentEventType.COMPLETED))
        assertFalse(bus.canReceiveSiblingMessage("a", "b"))
        spawn(bus, "b")
        bus.release("a")
        assertFalse(bus.canReceiveSiblingMessage("a", "b"))
    }

    @Test
    fun parentChildMessages_remainUnaffectedByDisabledSiblingCommunication() = runBlocking {
        val bus = busWith(commEnabled = false)
        val received = mutableListOf<SubAgentEvent>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            bus.events.collect { received += it }
        }
        try {
            val sent = listOf(
                SubAgentEvent("a", "parent", SubAgentEventType.MESSAGE_FROM_PARENT, "send"),
                SubAgentEvent("a", "parent", SubAgentEventType.MESSAGE_FROM_SUB, "read")
            )
            sent.forEach { bus.emit(it) }
            kotlinx.coroutines.yield()
            assertEquals(sent, received)
            assertTrue(received.none { it.fromSibling })
            assertEquals(emptySet<String>(), bus.activeSubSessionIds.value)
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun spawn_marksActive() {
        val bus = busWith()
        spawn(bus, "sub-1")

        assertEquals(setOf("sub-1"), bus.activeSubSessionIds.value)
        assertEquals(1, bus.activeCount)
        assertFalse(bus.isFull)
    }

    @Test
    fun terminalEvents_freeSlot() {
        listOf(
            SubAgentEventType.COMPLETED,
            SubAgentEventType.FAILED,
            SubAgentEventType.STOPPED
        ).forEach { type ->
            val bus = busWith()
            spawn(bus, "sub-1")
            bus.emit(SubAgentEvent(subSessionId = "sub-1", parentSessionId = "parent", type = type))

            assertEquals("$type 应交回槽位", emptySet<String>(), bus.activeSubSessionIds.value)
        }
    }

    @Test
    fun isFull_atMaxRunning() {
        val bus = busWith()
        repeat(SubAgentEventBus.DEFAULT_MAX_RUNNING) { spawn(bus, "sub-$it") }

        assertTrue(bus.isFull)

        bus.emit(SubAgentEvent(subSessionId = "sub-0", parentSessionId = "parent", type = SubAgentEventType.COMPLETED))
        assertFalse("腾出一个后应能再派发", bus.isFull)
    }

    /** 上限读用户配置，不是写死的 5：配到 2 时第二个就不该再接受。 */
    @Test
    fun isFull_followsConfiguredLimit() {
        val bus = busWith(limit = 2)
        spawn(bus, "sub-1")
        assertFalse(bus.isFull)

        spawn(bus, "sub-2")
        assertEquals(2, bus.maxRunning)
        assertTrue("配到 2 个时就应判满", bus.isFull)
    }

    /** 用户手动停止走 release：必须真的交回槽位，否则上限被永久占用。 */
    @Test
    fun release_freesSlotAndReportsWhetherItWasActive() {
        val bus = busWith()
        spawn(bus, "sub-1")

        assertTrue(bus.release("sub-1"))
        assertEquals(emptySet<String>(), bus.activeSubSessionIds.value)
    }

    /**
     * 幂等且如实回报：TaskTool 的 stop 已经 emit 过 STOPPED 时，ViewModel 随后的 release
     * 必须返回 false，否则会给父代理重复投递一条「已被终止」通知。
     */
    @Test
    fun release_isIdempotentForUnknownId() {
        val bus = busWith()
        spawn(bus, "sub-1")
        bus.release("sub-1")

        assertFalse(bus.release("sub-1"))
        assertFalse(bus.release("never-existed"))
    }

    @Test
    fun release_doesNotTouchOtherSubAgents() {
        val bus = busWith()
        spawn(bus, "sub-1")
        spawn(bus, "sub-2")

        bus.release("sub-1")

        assertEquals(setOf("sub-2"), bus.activeSubSessionIds.value)
    }
}
