package com.aharou.feature.agent.domain.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知队列的消费语义：peek 不移除、ack 只移除已确认项、drain 原子取走、多会话互不干扰。
 * 这些性质保证同一条后台通知不会既被工具结果搭车送达、又被本轮结束的兜底路径重复送达。
 */
class AgentNotificationCenterTest {

    private fun task(id: String) = PendingNotification(
        kind = AgentNotificationKind.BACKGROUND_TASK,
        sourceId = id,
        title = "build $id",
        outcome = NotificationOutcome.COMPLETED,
        exitCode = 0
    )

    @Test
    fun peek_doesNotRemove() {
        val center = AgentNotificationCenter()
        center.enqueue("s1", task("term-1"))

        assertEquals(1, center.peek("s1").size)
        assertEquals(1, center.peek("s1").size)
        assertEquals(1, center.pendingCount("s1"))
    }

    @Test
    fun ack_removesOnlyAckedItems() {
        val center = AgentNotificationCenter()
        center.enqueue("s1", task("term-1"))
        val peeked = center.peek("s1")
        // peek 之后新到达的事件不应被这次 ack 误删
        center.enqueue("s1", task("term-2"))

        center.ack("s1", peeked.map { it.seq })

        val remaining = center.peek("s1")
        assertEquals(1, remaining.size)
        assertEquals("term-2", remaining.first().sourceId)
    }

    @Test
    fun drain_takesAllAndClears() {
        val center = AgentNotificationCenter()
        center.enqueue("s1", task("term-1"))
        center.enqueue("s1", task("term-2"))

        val drained = center.drain("s1")

        assertEquals(listOf("term-1", "term-2"), drained.map { it.sourceId })
        assertEquals(0, center.pendingCount("s1"))
        assertTrue(center.drain("s1").isEmpty())
    }

    @Test
    fun sessionsAreIsolated() {
        val center = AgentNotificationCenter()
        center.enqueue("s1", task("term-1"))
        center.enqueue("s2", task("term-2"))

        center.clear("s1")

        assertEquals(0, center.pendingCount("s1"))
        assertEquals(1, center.pendingCount("s2"))
    }

    @Test
    fun freshForNewRound_siblingMessagesDoNotWakeCompletedRecipient() {
        val center = AgentNotificationCenter()
        center.enqueue("recipient", task("peer").copy(
            kind = AgentNotificationKind.AGENT_MESSAGE,
            fromSibling = true,
            message = "sent before recipient completed"
        ))
        val snapshot = center.peek("recipient")
        assertEquals(1, snapshot.size)
        assertTrue(center.freshForNewRound(snapshot).isEmpty())
        assertEquals(snapshot, center.peek("recipient"))
        center.ack("recipient", snapshot.map { it.seq })
        assertTrue(center.peek("recipient").isEmpty())
    }

    @Test
    fun freshForNewRound_mixedQueueKeepsParentChildAndBackgroundMessages() {
        val center = AgentNotificationCenter()
        val peer = task("peer").copy(kind = AgentNotificationKind.AGENT_MESSAGE, fromSibling = true)
        val parent = task("parent").copy(kind = AgentNotificationKind.AGENT_MESSAGE, fromParent = true)
        val child = task("child").copy(kind = AgentNotificationKind.AGENT_MESSAGE)
        val persisted = task("user").copy(kind = AgentNotificationKind.USER_MESSAGE, alreadyPersisted = true)
        val background = task("terminal")
        listOf(peer, parent, child, persisted, background).forEach { center.enqueue("s1", it) }
        val snapshot = center.peek("s1")
        assertEquals(listOf(parent, child, background), center.freshForNewRound(snapshot).map { it.copy(seq = 0) })
        val late = center.enqueue("s1", task("late"))
        center.ack("s1", snapshot.map { it.seq })
        assertEquals(listOf(late), center.peek("s1").map { it.seq })
    }

    @Test
    fun clearAll_removesEverySession() {
        val center = AgentNotificationCenter()
        center.enqueue("s1", task("term-1"))
        center.enqueue("s2", task("term-2"))

        center.clearAll()

        assertEquals(0, center.pendingCount("s1"))
        assertEquals(0, center.pendingCount("s2"))
    }
}
