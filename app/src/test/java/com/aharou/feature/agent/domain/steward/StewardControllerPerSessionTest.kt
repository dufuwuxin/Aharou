package com.aharou.feature.agent.domain.steward

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 智能托管按会话隔离：开关、判定、记账、上限互不串台。纯 JVM，判定与存储都用假实现。 */
@OptIn(ExperimentalCoroutinesApi::class)
class StewardControllerPerSessionTest {

    private val sessionA = "session-a"
    private val sessionB = "session-b"

    /** 内存版设置存储：等价于 SharedPreferences 的每会话键，但不碰 Android。 */
    private class FakeStore : StewardSettingsStore {
        val enabledFlags = mutableMapOf<String, Boolean>()
        val loopLimits = mutableMapOf<String, Int>()
        val unlimitedFlags = mutableMapOf<String, Boolean>()

        override fun enabled(sessionId: String): Boolean = enabledFlags[sessionId] ?: false
        override fun setEnabled(sessionId: String, on: Boolean) {
            enabledFlags[sessionId] = on
        }

        override fun maxLoops(sessionId: String): Int =
            (loopLimits[sessionId] ?: StewardSettings.DEFAULT_MAX_LOOPS)
                .coerceIn(StewardSettings.MIN_LOOPS, StewardSettings.MAX_LOOPS)

        override fun setMaxLoops(sessionId: String, value: Int) {
            loopLimits[sessionId] = value
        }

        override fun unlimited(sessionId: String): Boolean = unlimitedFlags[sessionId] ?: false
        override fun setUnlimited(sessionId: String, value: Boolean) {
            unlimitedFlags[sessionId] = value
        }

        override fun forgetSession(sessionId: String) {
            enabledFlags.remove(sessionId)
            loopLimits.remove(sessionId)
            unlimitedFlags.remove(sessionId)
        }

        override fun clearAllEnabled() {
            enabledFlags.keys.toList().forEach { enabledFlags[it] = false }
        }
    }

    /** 可控判定：默认「没做完，继续下一步」；可按会话挂一个闸门把判定卡住，用来验证互不阻塞。 */
    private class FakeJudge : StewardJudgePort {
        var verdict: StewardJudgement? = StewardJudgement(completed = false, nextInstruction = "继续下一步")
        val judgedSessions = mutableListOf<String>()
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val openGates = mutableSetOf<String>()

        override suspend fun judge(
            originalInstruction: String,
            lastReport: String,
            sessionId: String?,
        ): StewardJudgement? {
            val id = sessionId.orEmpty()
            judgedSessions += id
            if (id in openGates) gates.getValue(id).await()
            return verdict
        }
    }

    private fun controller(store: StewardSettingsStore, judge: StewardJudgePort) =
        StewardController(store, judge)

    @Test
    fun enablingOneSessionDoesNotEnableAnother() = runTest {
        val store = FakeStore()
        val judge = FakeJudge()
        val steward = controller(store, judge)
        val sentA = mutableListOf<String>()
        val sentB = mutableListOf<String>()
        val sendA: suspend (String) -> Unit = { sentA += it }
        val sendB: suspend (String) -> Unit = { sentB += it }

        steward.enable(sessionA, "把文档翻译成英文")
        assertTrue(steward.isEnabled(sessionA))
        assertFalse(steward.isEnabled(sessionB))
        assertFalse(steward.stateOf(sessionB).enabled)

        assertTrue(steward.onAiIdle(sessionA, "已翻完第一章", sendA))
        assertFalse(steward.onAiIdle(sessionB, "随便说点什么", sendB))

        assertEquals(listOf("继续下一步"), sentA)
        assertTrue(sentB.isEmpty())
        assertEquals(1, steward.stateOf(sessionA).loopCount)
        assertEquals(0, steward.stateOf(sessionB).loopCount)
    }

    @Test
    fun eachSessionStopsAtItsOwnLoopLimit() = runTest {
        val store = FakeStore()
        val judge = FakeJudge()
        val steward = controller(store, judge)
        store.loopLimits[sessionA] = 2
        store.loopLimits[sessionB] = 1
        var sentA = 0
        var sentB = 0
        val sendA: suspend (String) -> Unit = { sentA++ }
        val sendB: suspend (String) -> Unit = { sentB++ }

        steward.enable(sessionA, "任务 A")
        steward.enable(sessionB, "任务 B")

        repeat(5) { steward.onAiIdle(sessionA, "报告 A", sendA) }
        repeat(5) { steward.onAiIdle(sessionB, "报告 B", sendB) }

        assertEquals(2, sentA)
        assertEquals(1, sentB)
        assertEquals(2, steward.stateOf(sessionA).loopCount)
        assertEquals(1, steward.stateOf(sessionB).loopCount)
        assertEquals(StewardStatus.Stopped, steward.stateOf(sessionA).status)
        assertEquals(StewardStatus.Stopped, steward.stateOf(sessionB).status)
    }

    @Test
    fun judgingOneSessionDoesNotBlockAnother() = runTest {
        val store = FakeStore()
        val judge = FakeJudge()
        val steward = controller(store, judge)
        val gate = CompletableDeferred<Unit>()
        judge.gates[sessionA] = gate
        judge.openGates += sessionA
        var sentA = false
        var sentB = false
        val sendA: suspend (String) -> Unit = { sentA = true }
        val sendB: suspend (String) -> Unit = { sentB = true }

        steward.enable(sessionA, "任务 A")
        steward.enable(sessionB, "任务 B")

        val jobA = launch { steward.onAiIdle(sessionA, "报告 A", sendA) }
        runCurrent()
        // A 卡在判定里：它自己进了判定中，但 B 照样能立刻判定并续发。
        assertTrue(steward.stateOf(sessionA).checking)
        assertTrue(steward.onAiIdle(sessionB, "报告 B", sendB))
        assertTrue(sentB)
        assertFalse(sentA)

        gate.complete(Unit)
        jobA.join()
        assertTrue(sentA)
        assertFalse(steward.stateOf(sessionA).checking)
    }

    @Test
    fun disableOnlyAffectsItsOwnSession() = runTest {
        val store = FakeStore()
        val judge = FakeJudge()
        val steward = controller(store, judge)
        var sentA = 0
        var sentB = 0
        val sendA: suspend (String) -> Unit = { sentA++ }
        val sendB: suspend (String) -> Unit = { sentB++ }

        steward.enable(sessionA, "任务 A")
        steward.enable(sessionB, "任务 B")
        steward.disable(sessionA)

        assertFalse(steward.isEnabled(sessionA))
        assertTrue(steward.isEnabled(sessionB))
        assertFalse(steward.onAiIdle(sessionA, "报告 A", sendA))
        assertTrue(steward.onAiIdle(sessionB, "报告 B", sendB))
        assertEquals(0, sentA)
        assertEquals(1, sentB)
    }

    @Test
    fun maxLoopsAndUnlimitedArePerSession() = runTest {
        val store = FakeStore()
        val steward = controller(store, FakeJudge())

        steward.setMaxLoops(sessionA, 12)
        steward.setUnlimited(sessionA, true)

        assertEquals(12, store.maxLoops(sessionA))
        assertTrue(store.unlimited(sessionA))
        // B 还是默认值，没被 A 的改动带走。
        assertEquals(StewardSettings.DEFAULT_MAX_LOOPS, store.maxLoops(sessionB))
        assertFalse(store.unlimited(sessionB))
    }

    @Test
    fun initClearsEnabledFlagsLeftFromPreviousRun() = runTest {
        val store = FakeStore()
        // 上一次进程留下的「开着」：基准指令已经不在了，启动时要收回，免得显示开着却什么也不做。
        store.enabledFlags[sessionA] = true
        store.enabledFlags[sessionB] = true

        val steward = controller(store, FakeJudge())

        assertFalse(steward.isEnabled(sessionA))
        assertFalse(steward.isEnabled(sessionB))
    }

    @Test
    fun unlimitedStillStopsAtHardMaxLoops() = runTest {
        val store = FakeStore()
        val steward = controller(store, FakeJudge())
        store.unlimitedFlags[sessionA] = true
        var sent = 0
        val send: suspend (String) -> Unit = { sent++ }

        steward.enable(sessionA, "任务 A")
        repeat(StewardController.HARD_MAX_LOOPS + 5) { steward.onAiIdle(sessionA, "报告", send) }

        assertEquals(StewardController.HARD_MAX_LOOPS, sent)
    }
    @Test
    fun disablingDuringJudgementCannotSendOldResult() = runTest {
        val store = FakeStore()
        val judge = FakeJudge()
        val gate = CompletableDeferred<Unit>()
        judge.gates[sessionA] = gate
        judge.openGates += sessionA
        val steward = controller(store, judge)
        var sent = false
        steward.enable(sessionA, "old task")
        val old = steward.currentTurn(sessionA)!!
        val job = launch { steward.onAiIdle(old, "report") { sent = true } }
        runCurrent()
        steward.disable(sessionA)
        gate.complete(Unit)
        job.join()
        assertFalse(sent)
        assertFalse(steward.isCurrent(old))
        assertEquals(StewardStatus.Stopped, steward.stateOf(sessionA).status)
    }

    @Test
    fun oldFinallyDoesNotReleaseNewJudgement() = runTest {
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        var calls = 0
        val judge = object : StewardJudgePort {
            override suspend fun judge(originalInstruction: String, lastReport: String, sessionId: String?): StewardJudgement {
                calls++
                if (originalInstruction == "old") firstGate.await() else secondGate.await()
                return StewardJudgement(completed = false, nextInstruction = "next")
            }
        }
        val steward = controller(FakeStore(), judge)
        var sent = 0
        steward.enable(sessionA, "old")
        val old = steward.currentTurn(sessionA)!!
        val oldJob = launch { steward.onAiIdle(old, "report") { sent++ } }
        runCurrent()
        steward.enable(sessionA, "new")
        val fresh = steward.currentTurn(sessionA)!!
        val freshJob = launch { steward.onAiIdle(fresh, "report") { sent++ } }
        runCurrent()
        firstGate.complete(Unit)
        oldJob.join()
        assertTrue(steward.stateOf(sessionA).checking)
        assertFalse(steward.onAiIdle(fresh, "duplicate") { sent++ })
        assertEquals(2, calls)
        secondGate.complete(Unit)
        freshJob.join()
        assertEquals(1, sent)
        assertEquals(1, steward.stateOf(sessionA).loopCount)
    }

    @Test
    fun staleAnchorRevisionCannotBindReenabledSession() = runTest {
        val steward = controller(FakeStore(), FakeJudge())
        val stale = steward.enable(sessionA, "")
        steward.disable(sessionA)
        val fresh = steward.enable(sessionA, "")
        steward.bindAnchor(sessionA, "stale database reply", stale)
        assertEquals(null, steward.currentTurn(sessionA))
        steward.bindAnchor(sessionA, "fresh task", fresh)
        assertTrue(steward.currentTurn(sessionA) != null)
        steward.enable(sessionB, "other task")
        assertTrue(steward.isCurrent(steward.currentTurn(sessionA)!!))
    }

}
