package com.aharou.feature.localmodel.runtime

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.localmodel.domain.LocalModelManager
import io.mockk.Runs
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalLlmEngineTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Before fun setUp() {
        mockkObject(FileLogger)
        every { FileLogger.i(any(), any()) } just Runs
    }

    @After fun tearDown() { unmockkObject(FileLogger) }

    private fun engine(instance: LocalLlmBackend, onCreate: () -> Unit): LocalLlmEngine {
        val manager = mockk<LocalModelManager>()
        val file = temporaryFolder.newFile().apply { writeText("not a real model") }
        every { manager.isSupportedOnThisDevice() } returns true
        every { manager.isReady("fixture") } returns true
        every { manager.verifiedModelFile("fixture") } returns file
        return LocalLlmEngine(mockk<Context>(), manager).apply {
            createLlm = { onCreate(); instance }
        }
    }

    @Test fun appendedProviderRoundsReuseNativeHistoryAtNonDefaultThreadCounts() = runBlocking {
        for (threads in listOf(2, 3, 4, 8)) {
            val native = mockk<LocalLlmBackend>(relaxed = true)
            every { native.getResponseAsFlow(any()) } returns flowOf("answer")
            var loads = 0
            val engine = engine(native) { loads++ }
            val provider = spyk(LocalProvider(engine), recordPrivateCalls = true)
            every { provider["threadCount"]() } returns threads
            provider.model = "fixture"
            val first = listOf(AgentMessage.UserMessage(content = "first"))
            provider.completeStream("system", first, emptyList(), null).toList()
            provider.completeStream("system", first + listOf(
                AgentMessage.AssistantMessage(content = "answer"),
                AgentMessage.UserMessage(content = "second"),
            ), emptyList(), null).toList()
            assertEquals("threads=$threads", 1, loads)
            coVerify(exactly = 1) { native.load(any(), match { it.numThreads == threads }) }
            verify(exactly = 0) { native.close() }
            verify(exactly = 1) { native.addSystemPrompt("system") }
            verify(exactly = 1) { native.getResponseAsFlow("first") }
            verify(exactly = 1) { native.getResponseAsFlow("second") }
        }
    }

    @Test fun changingParamsLoadsOnceAndStreamDoesNotReloadAgain() = runBlocking {
        val native = mockk<LocalLlmBackend>(relaxed = true)
        every { native.getResponseAsFlow(any()) } returns flowOf("answer")
        var loads = 0
        val engine = engine(native) { loads++ }
        val question = listOf(LocalChatMessage("user", "question"))
        engine.ensureLoaded("fixture")
        engine.stream(question).toList()
        assertEquals(1, loads)
        val changed = LocalGenParams(numThreads = 2)
        engine.ensureLoaded("fixture", changed)
        engine.stream(question, changed).toList()
        engine.ensureLoaded("fixture", changed)
        assertEquals(2, loads)
        verify(exactly = 1) { native.close() }
    }

    @Test fun nonAppendHistoryStillRebuildsWithMatchingParams() = runBlocking {
        val native = mockk<LocalLlmBackend>(relaxed = true)
        every { native.getResponseAsFlow(any()) } returns flowOf("answer")
        var loads = 0
        val engine = engine(native) { loads++ }
        val provider = spyk(LocalProvider(engine), recordPrivateCalls = true)
        every { provider["threadCount"]() } returns 2
        provider.model = "fixture"
        for (question in listOf("old", "replacement")) {
            provider.completeStream("system", listOf(AgentMessage.UserMessage(content = question)),
                emptyList(), null).toList()
        }
        assertEquals(2, loads)
        verify(exactly = 1) { native.close() }
        verify(exactly = 2) { native.addSystemPrompt("system") }
    }
}
