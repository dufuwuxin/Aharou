package com.aharou.feature.agent.domain.schedule

import android.app.Application
import android.content.Intent
import com.aharou.feature.agent.data.local.dao.ScheduledTaskDao
import com.aharou.feature.agent.domain.runner.AgentTurnRunner
import com.aharou.feature.agent.domain.workflow.AgentEvent
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class ScheduledTaskExecutionServiceTest {
    @Test fun realServiceKeepsSecondRunAliveAndRecordsFailedEventAsFailure() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val service = Robolectric.buildService(ScheduledTaskExecutionService::class.java).get()
        val runner = mockk<AgentTurnRunner>()
        val dao = mockk<ScheduledTaskDao>()
        val outcomes = mutableMapOf<String, String>()
        coEvery { dao.recordOutcome(any(), any(), any()) } answers {
            outcomes[firstArg()] = secondArg()
        }
        val finishFirst = CompletableDeferred<Unit>()
        val finishSecond = CompletableDeferred<Unit>()
        every { runner.run(match { it.text == "first" }) } returns flow {
            finishFirst.await()
            emit(AgentEvent.Completed)
        }
        every { runner.run(match { it.text == "second" }) } returns flow {
            finishSecond.await()
            emit(AgentEvent.Failed("expected failure"))
        }
        service.agentTurnRunner = runner
        service.scheduledTaskDao = dao
        fun start(id: String, startId: Int) = service.onStartCommand(Intent().apply {
            putExtra(ScheduledTaskExecutionService.EXTRA_TASK_ID, id)
            putExtra(ScheduledTaskExecutionService.EXTRA_PROMPT, id)
            putExtra(ScheduledTaskExecutionService.EXTRA_TARGET_SESSION_ID, "session-$id")
        }, 0, startId)
        try {
            start("first", 1)
            start("second", 2)
            finishFirst.complete(Unit)
            assertEquals(ScheduledTaskOutcome.COMPLETED, outcomes["first"])
            assertNull(outcomes["second"])
            assertFalse(shadowOf(service).isStoppedBySelf)
            finishSecond.complete(Unit)
            assertEquals(ScheduledTaskOutcome.FAILED, outcomes["second"])
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally {
            service.onDestroy()
            Dispatchers.resetMain()
        }
    }
}
