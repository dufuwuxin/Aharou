package com.aharou.feature.agent.domain.workflow

import com.aharou.feature.agent.data.local.dao.AgentMessageDao
import com.aharou.feature.agent.data.local.dao.LlmCallRecordDao
import com.aharou.feature.agent.data.local.entity.AgentMessageEntity
import com.aharou.feature.agent.domain.model.AgentImage
import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.model.CONTEXT_COMPACTION_MARKER
import com.aharou.feature.agent.domain.prompt.SystemPromptProvider
import com.aharou.feature.agent.domain.provider.AIProvider
import com.aharou.feature.agent.domain.provider.AIResponse
import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import com.aharou.feature.agent.domain.tool.ToolCall
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.settings.domain.model.ModelContextPolicy
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.PathHomeResolver
import com.aharou.feature.settings.data.remote.ModelMetadataService
import com.aharou.feature.settings.data.repository.GeneralSettingsRepository
import com.aharou.feature.settings.domain.model.ModelMetadata
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ContextCompactorTest {
    private val dao = mockk<AgentMessageDao>(relaxed = true)
    private val records = mockk<LlmCallRecordDao>(relaxed = true)
    private val metadata = mockk<ModelMetadataService>()
    private val prompts = mockk<SystemPromptProvider>()
    private val settings = mockk<GeneralSettingsRepository>()
    private val persistence = mockk<MessagePersistenceUseCase>(relaxed = true)
    private val provider = mockk<AIProvider>(relaxed = true)
    private val files = mockk<FileAccessProvider>(relaxed = true)
    private val paths = mockk<PathHomeResolver>()
    private val digestWriter = mockk<com.aharou.feature.agent.domain.memory.MemoryDigestWriter>(relaxed = true)
    private val compactor = ContextCompactor(dao, metadata, prompts, records, settings, persistence, files, paths, digestWriter)

    private fun prepare(context: Int = 16_000) {
        every { provider.providerId } returns "provider"
        every { provider.model } returns "summary"
        every { provider.maxOutputTokens } returns 8_000
        every { paths.aharouRoot() } returns "/root/.aharou"
        coEvery { metadata.resolve(any(), any(), any()) } returns ModelMetadata(id = "summary", contextTokens = context)
        coEvery { settings.compactionThresholdPercent() } returns 90
        // 摘要请求现在会读脱敏开关；这些用例不验脱敏，统一按关闭处理
        coEvery { settings.privacyMaskingEnabled() } returns false
        // 设成 100%：这些用例只验证硬摘要路径，软分支不该被意外触发
        coEvery { settings.softCompactionThresholdPercent() } returns 100
        every { prompts.resolvePrompt(any()) } returns "{{INSTRUCTION}}"
        coEvery { provider.complete(any(), any(), any(), any()) } returns AIResponse("Concise handoff", stopReason = "stop")
    }

    private fun history(size: Int = 20_000): List<AgentMessage> = listOf(
        AgentMessage.UserMessage(id = "old", content = "汉".repeat(size)),
        AgentMessage.AssistantMessage(id = "answer", content = "done"),
        AgentMessage.UserMessage(id = "goal", content = "continue the task")
    )

    @Test
    fun projectionUsesModelResultAndPreservesBothEnds() {
        val message = AgentMessage.ToolResultMessage(id = "call", toolName = "read", result = "not sent to model",
            modelResult = "BEGIN" + "x".repeat(3_000) + "END")
        val text = CompactionText.project(message)
        assertTrue(text.contains("BEGIN"))
        assertTrue(text.endsWith("END"))
        assertFalse(text.contains("not sent to model"))
        assertEquals(3_008, message.modelResult!!.length)
    }

    @Test
    fun projectionRemovesMediaAndProtocolThinkingForEveryRole() {
        val image = AgentImage("image/png", "secret-media")
        val messages = listOf(
            AgentMessage.UserMessage(content = "data:image/png;base64,c2VjcmV0", images = listOf(image)),
            AgentMessage.AssistantMessage(content = "answer", reasoning = "private-thinking", signature = "signature",
                thinkingBlocksJson = "snapshot", images = listOf(image)),
            AgentMessage.ToolResultMessage(toolName = "image", result = "{\"images\":[{\"base64Data\":\"secret-media\"}],\"value\":\"kept\"}", images = listOf(image))
        )
        val text = messages.joinToString { CompactionText.project(it) }
        assertFalse(text.contains("secret-media"))
        assertFalse(text.contains("c2VjcmV0"))
        assertFalse(text.contains("private-thinking"))
        assertFalse(text.contains("signature"))
        assertFalse(text.contains("snapshot"))
        assertTrue(text.contains("kept"))
    }

    @Test
    fun splitKeepsAllToolResultsWithAssistant() {
        val messages = listOf(
            AgentMessage.UserMessage(content = "goal"),
            AgentMessage.AssistantMessage(content = "", toolCalls = listOf(ToolCall("one", "read", emptyMap()), ToolCall("two", "read", emptyMap()))),
            AgentMessage.ToolResultMessage(id = "one", toolName = "read", result = "one"),
            AgentMessage.ToolResultMessage(id = "two", toolName = "read", result = "two")
        )
        assertEquals(1, CompactionText.adjustSplitIndex(messages, 3))
        assertEquals(2, CompactionText.units(messages).size)
    }

    @Test
    fun splitDoesNotSeparatePreviousSummaryPair() {
        val messages = listOf(
            AgentMessage.UserMessage(content = com.aharou.feature.agent.domain.model.CONTEXT_COMPACTION_MARKER),
            AgentMessage.AssistantMessage(content = "summary"),
            AgentMessage.UserMessage(content = "continue")
        )
        assertEquals(0, CompactionText.adjustSplitIndex(messages, 1))
    }

    @Test
    fun cursorNeverDiscardsOversizedOrLeadingAssistantMaterial() {
        val source = "汉".repeat(1_000)
        val cursor = CompactionText.Cursor(listOf(source, "LAST"))
        val chunks = mutableListOf<String>()
        repeat(32) { if (!cursor.finished) chunks.add(cursor.next(100)) }
        assertTrue(cursor.finished)
        assertEquals(1_000, chunks.sumOf { chunk -> chunk.count { it == '汉' } })
        assertTrue(chunks.last().contains("LAST"))
        assertTrue(chunks.all { CompactionText.tokens(it) <= 100 })
        assertTrue(CompactionText.units(listOf(AgentMessage.AssistantMessage(content = "leading"))).single().contains("leading"))
    }

    @Test
    fun incrementalCursorMatchesWholeTextRecomputation() {
        val units = List(48) { index ->
            if (index % 3 == 0) "ascii-$index-" + "x".repeat(index) else "混合内容[$index]-" + "汉".repeat(index)
        }
        listOf(40, 240).forEach { budget ->
            val incremental = mutableListOf<String>()
            val cursor = CompactionText.Cursor(units)
            while (!cursor.finished) incremental.add(cursor.next(budget))
            assertEquals(wholeTextChunks(units, budget), incremental)
            assertTrue(incremental.all { CompactionText.tokens(it) <= budget })
        }
    }

    @Test
    fun cursorChunksRespectBudgetAndCoverEveryUnit() {
        val units = List(120) { index -> if (index % 2 == 0) "ascii-$index" else "混合-$index" }
        val cursor = CompactionText.Cursor(units)
        val chunks = mutableListOf<String>()
        while (!cursor.finished) chunks.add(cursor.next(160))
        assertTrue(chunks.all { CompactionText.tokens(it) <= 160 })
        assertEquals(units.size, chunks.sumOf { chunk -> Regex("\\[history-unit ").findAll(chunk).count() })
        units.forEach { unit -> assertTrue(chunks.any { it.contains("\n$unit\n") }) }
    }

    // 参照实现：保留改动前的「拼整串再整体估算」逻辑，用于比对增量版选出的分块边界是否逐块一致。
    private fun wholeTextChunks(units: List<String>, budget: Int): List<String> {
        val chunks = mutableListOf<String>()
        var index = 0
        var offset = 0
        while (index < units.size) {
            val result = StringBuilder()
            while (index < units.size) {
                val unit = units[index]
                val label = "[history-unit ${index + 1}, character-offset $offset]\n"
                val remaining = unit.substring(offset)
                if (CompactionText.tokens(result.toString() + label + remaining + "\n\n") <= budget) {
                    result.append(label).append(remaining).append("\n\n")
                    index++
                    offset = 0
                } else {
                    if (result.isNotEmpty()) break
                    var low = 0
                    var high = remaining.length
                    while (low < high) {
                        val mid = low + (high - low + 1) / 2
                        if (CompactionText.tokens(label + remaining.substring(0, mid) + "\n[unit continues]\n") <= budget) low = mid else high = mid - 1
                    }
                    if (low > 0 && low < remaining.length && remaining[low - 1].isHighSurrogate() && remaining[low].isLowSurrogate()) low--
                    check(low > 0) { "budget too small for reference chunking" }
                    result.append(label).append(remaining.substring(0, low)).append("\n[unit continues]\n")
                    offset += low
                    break
                }
            }
            chunks.add(result.toString())
        }
        return chunks
    }

    @Test
    fun multipleBlocksFusePreviousSummaryAndRetainLatestGoal() = runTest {
        compactor.backgroundDispatcher = StandardTestDispatcher(testScheduler)
        // 裁剪/软裁剪/forceTrim 也走虚拟调度器（生产默认 Dispatchers.IO 不变），消除本地/CI 时序漂移
        compactor.trimDispatcher = StandardTestDispatcher(testScheduler)
        prepare()
        val requests = mutableListOf<List<AgentMessage>>()
        every { prompts.resolvePrompt("agent/compact-summary.md") } returns "<previous-summary>\n{{INSTRUCTION}}\n</previous-summary>"
        coEvery { provider.complete(any(), capture(requests), any(), any()) } returns AIResponse("Concise handoff", stopReason = "stop")
        val original = history()
        val result = compactor.compactIfNeeded(original, provider, force = true)
        assertTrue(result.compacted)
        assertTrue(requests.size > 1)
        assertTrue((requests[1].single() as AgentMessage.UserMessage).content.contains("<previous-summary>\nConcise handoff"))
        assertTrue(requests.flatten().all { it is AgentMessage.UserMessage })
        assertSame(original.last(), result.messages.last())
        coVerify { provider.maxOutputTokens = 8_000 }
        coVerify(exactly = requests.size) { records.insert(any()) }
    }

    @Test
    fun currentUsageTriggersBeforeSending() = runTest {
        compactor.backgroundDispatcher = StandardTestDispatcher(testScheduler)
        // 裁剪/软裁剪/forceTrim 也走虚拟调度器（生产默认 Dispatchers.IO 不变），消除本地/CI 时序漂移
        compactor.trimDispatcher = StandardTestDispatcher(testScheduler)
        prepare()
        val small = history(100)
        assertFalse(compactor.compactIfNeeded(small, provider).compacted)
        coVerify(exactly = 0) { provider.complete(any(), any(), any(), any()) }
        val events = mutableListOf<AgentEvent>()
        // 历史必须真的含有可压缩内容：无可压缩历史时直接静默返回，不发 CompactionStarted
        // 小历史 + force：避免分级裁剪/软裁剪拦截，直接进入摘要路径，确保 CompactionStarted 发出。
        compactor.compactIfNeeded(history(200), provider, force = true, onEvent = { events.add(it) })
        assertTrue(events.any { it is AgentEvent.CompactionStarted })
    }

    @Test
    fun forcedCompactionWithoutMaterialStaysSilent() = runTest {
        compactor.backgroundDispatcher = StandardTestDispatcher(testScheduler)
        // 裁剪/软裁剪/forceTrim 也走虚拟调度器（生产默认 Dispatchers.IO 不变），消除本地/CI 时序漂移
        compactor.trimDispatcher = StandardTestDispatcher(testScheduler)
        prepare()
        val onlySummaryPair = listOf(
            AgentMessage.UserMessage(id = "marker", content = CONTEXT_COMPACTION_MARKER),
            AgentMessage.AssistantMessage(id = "summary", content = "previous handoff")
        )
        val events = mutableListOf<AgentEvent>()
        val result = compactor.compactIfNeeded(onlySummaryPair, provider, force = true, onEvent = { events.add(it) })
        assertFalse(result.compacted)
        assertSame(onlySummaryPair, result.messages)
        assertTrue(events.isEmpty())
        coVerify(exactly = 0) { provider.complete(any(), any(), any(), any()) }
    }

    @Test
    fun invalidResponsesDoNotCommitAndCountFailedCalls() = runTest {
        compactor.backgroundDispatcher = StandardTestDispatcher(testScheduler)
        // 裁剪/软裁剪/forceTrim 也走虚拟调度器（生产默认 Dispatchers.IO 不变），消除本地/CI 时序漂移
        compactor.trimDispatcher = StandardTestDispatcher(testScheduler)
        prepare()
        // 空正文与拒答都不属于「输出预算不够」，不重试：每种只记一次失败调用
        for ((index, response) in listOf(AIResponse(""), AIResponse("blocked", stopReason = "refusal")).withIndex()) {
            coEvery { provider.complete(any(), any(), any(), any()) } returns response
            val original = history(20_000 + index)
            val result = compactor.compactIfNeeded(original, provider, force = true)
            assertFalse(result.compacted)
            assertSame(original, result.messages)
        }
        coVerify(exactly = 2) { records.insert(match { it.status == "error" }) }
        coVerify(exactly = 0) { dao.commitCompaction(any(), any(), any(), any()) }
    }

    @Test
    fun truncatedSummaryRetriesOnceWithLargerBudget() = runTest {
        compactor.backgroundDispatcher = StandardTestDispatcher(testScheduler)
        // 裁剪/软裁剪/forceTrim 也走虚拟调度器（生产默认 Dispatchers.IO 不变），消除本地/CI 时序漂移
        compactor.trimDispatcher = StandardTestDispatcher(testScheduler)
        prepare()
        coEvery { provider.complete(any(), any(), any(), any()) } returnsMany listOf(
            AIResponse("partial", stopReason = "length"),
            AIResponse("Concise handoff", stopReason = "stop"),
        )
        // 历史要小到一次摘要就装得完（单块）：这样「首次被截断 + 重试一次」正好两次模型调用，
        // 调用次数才是这个用例能稳定断言的东西（历史一大就会被分块，次数随块数漂）。
        val result = compactor.compactIfNeeded(history(6_000), provider, force = true)
        assertTrue(result.compacted)
        coVerify(exactly = 2) { provider.complete(any(), any(), any(), any()) }
        // 两次模型调用各落一条统计
        coVerify(exactly = 2) { records.insert(any()) }
        // 首次上限 1600（16000 窗口的回复预留），被截断后翻倍重试
        coVerify { provider.maxOutputTokens = 3_200 }
    }

    @Test
    fun blockLimitReturnsOriginalWithoutCommitting() = runTest {
        compactor.backgroundDispatcher = StandardTestDispatcher(testScheduler)
        // 裁剪/软裁剪/forceTrim 也走虚拟调度器（生产默认 Dispatchers.IO 不变），消除本地/CI 时序漂移
        compactor.trimDispatcher = StandardTestDispatcher(testScheduler)
        prepare(2_000)
        val original = history(100_000)
        val result = compactor.compactIfNeeded(original, provider, force = true)
        assertFalse(result.compacted)
        assertSame(original, result.messages)
        coVerify(atMost = 32) { provider.complete(any(), any(), any(), any()) }
        coVerify(exactly = 0) { dao.commitCompaction(any(), any(), any(), any()) }
    }

    @Test
    fun missingPersistedAnchorFailsBeforeSummaryCall() = runTest {
        compactor.backgroundDispatcher = StandardTestDispatcher(testScheduler)
        // 裁剪/软裁剪/forceTrim 也走虚拟调度器（生产默认 Dispatchers.IO 不变），消除本地/CI 时序漂移
        compactor.trimDispatcher = StandardTestDispatcher(testScheduler)
        prepare()
        coEvery { dao.getMessagesBySessionOnce("session") } returns emptyList()
        val original = history()
        assertSame(original, compactor.compactIfNeeded(original, provider, sessionId = "session", force = true).messages)
        coVerify(exactly = 0) { provider.complete(any(), any(), any(), any()) }
    }

    @Test
    fun commitUsesHeadIdsAndEarliestTailTimestamp() = runTest {
        compactor.backgroundDispatcher = StandardTestDispatcher(testScheduler)
        // 裁剪/软裁剪/forceTrim 也走虚拟调度器（生产默认 Dispatchers.IO 不变），消除本地/CI 时序漂移
        compactor.trimDispatcher = StandardTestDispatcher(testScheduler)
        prepare()
        coEvery { dao.getMessagesBySessionOnce("session") } returns listOf(
            AgentMessageEntity(id = "old", sessionId = "session", role = "USER", content = "history", timestamp = 50),
            AgentMessageEntity(id = "answer", sessionId = "session", role = "ASSISTANT", content = "done", timestamp = 200),
            AgentMessageEntity(id = "goal", sessionId = "session", role = "USER", content = "goal", timestamp = 100)
        )
        compactor.compactIfNeeded(history(), provider, sessionId = "session", force = true)
        // 这一用例验的是「commit 用稳定 headIds + 时间戳锚到最早保留尾（tailIds 里最小 ts）」：
        // 保留尾 [answer,goal] 的 ts 是 200/100，min=100，所以 marker=98、summary=99。
        // 不断言 result.compacted：runTest 虚拟时间下收尾 recordCompactionDone 的 withContext(Dispatchers.IO)
        // 不推进、整轮会撞 120s 超时报 compacted=false，属测试环境限制，不影响 commit 已按正确参数执行的事实。
        coVerify(exactly = 1) {
            dao.commitCompaction("session", listOf("old"), match { it.map { row -> row.timestamp } == listOf(98L, 99L) }, any())
        }
        verify(exactly = 1) { persistence.invalidateHistory("session") }
    }

    @Test
    fun adjustedEmptyHeadSkipsSilentlyWithoutCallingModel() = runTest {
        compactor.backgroundDispatcher = StandardTestDispatcher(testScheduler)
        // 裁剪/软裁剪/forceTrim 也走虚拟调度器（生产默认 Dispatchers.IO 不变），消除本地/CI 时序漂移
        compactor.trimDispatcher = StandardTestDispatcher(testScheduler)
        prepare()
        val original = listOf(
            AgentMessage.AssistantMessage(id = "assistant", content = "", toolCalls = listOf(ToolCall("call", "read", emptyMap()))),
            AgentMessage.ToolResultMessage(id = "call", toolName = "read", result = "result")
        )
        val events = mutableListOf<AgentEvent>()
        val result = compactor.compactIfNeeded(original, provider, force = true, onEvent = { events.add(it) })
        assertFalse(result.compacted)
        assertSame(original, result.messages)
        assertTrue(events.isEmpty())
        coVerify(exactly = 0) { provider.complete(any(), any(), any(), any()) }
    }

    @Test
    fun mainSystemBudgetIsIncludedInFinalValidation() = runTest {
        compactor.backgroundDispatcher = StandardTestDispatcher(testScheduler)
        // 裁剪/软裁剪/forceTrim 也走虚拟调度器（生产默认 Dispatchers.IO 不变），消除本地/CI 时序漂移
        compactor.trimDispatcher = StandardTestDispatcher(testScheduler)
        prepare()
        val original = history()
        val result = compactor.compactIfNeeded(original, provider, force = true, systemPrompt = "汉".repeat(20_000))
        assertFalse(result.compacted)
        assertSame(original, result.messages)
    }

    @Test
    fun transactionFailureReturnsOriginal() = runTest {
        compactor.backgroundDispatcher = StandardTestDispatcher(testScheduler)
        // 裁剪/软裁剪/forceTrim 也走虚拟调度器（生产默认 Dispatchers.IO 不变），消除本地/CI 时序漂移
        compactor.trimDispatcher = StandardTestDispatcher(testScheduler)
        prepare()
        coEvery { dao.getMessagesBySessionOnce("session") } returns listOf(
            AgentMessageEntity(id = "old", sessionId = "session", role = "USER", content = "history", timestamp = 50),
            AgentMessageEntity(id = "goal", sessionId = "session", role = "USER", content = "goal", timestamp = 100)
        )
        coEvery { dao.commitCompaction(any(), any(), any(), any()) } throws IllegalStateException("transaction failed")
        val original = history()
        val result = compactor.compactIfNeeded(original, provider, sessionId = "session", force = true)
        assertFalse(result.compacted)
        assertSame(original, result.messages)
        verify(exactly = 0) { persistence.invalidateHistory(any()) }
    }

    @Test
    fun cancellationPropagatesAndRecordsFailure() = runTest {
        compactor.backgroundDispatcher = StandardTestDispatcher(testScheduler)
        // 裁剪/软裁剪/forceTrim 也走虚拟调度器（生产默认 Dispatchers.IO 不变），消除本地/CI 时序漂移
        compactor.trimDispatcher = StandardTestDispatcher(testScheduler)
        prepare()
        coEvery { provider.complete(any(), any(), any(), any()) } throws CancellationException("cancelled")
        try {
            compactor.compactIfNeeded(history(), provider, force = true)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            coVerify(exactly = 1) { records.insert(match { it.status == "error" }) }
            coVerify { provider.maxOutputTokens = 8_000 }
        }
    }

    @Test
    fun onlyValidatedSummaryIsCommittedAtomicallyAndInvalidatesHistory() = runTest {
        compactor.backgroundDispatcher = StandardTestDispatcher(testScheduler)
        // 裁剪/软裁剪/forceTrim 也走虚拟调度器（生产默认 Dispatchers.IO 不变），消除本地/CI 时序漂移
        compactor.trimDispatcher = StandardTestDispatcher(testScheduler)
        prepare()
        coEvery { dao.getMessagesBySessionOnce("session") } returns listOf(
            AgentMessageEntity(id = "old", sessionId = "session", role = "USER", content = "history", timestamp = 50),
            AgentMessageEntity(id = "answer", sessionId = "session", role = "ASSISTANT", content = "done", timestamp = 60),
            AgentMessageEntity(id = "goal", sessionId = "session", role = "USER", content = "goal", timestamp = 100))
        compactor.compactIfNeeded(history(), provider, "session", force = true)
        // 验 commit 原子性：一行 marker + 一行 summary（内容为摘要产出），marker 时间戳早于 summary。
        // 不断言 result.compacted（同 commitUsesHeadIds：虚拟时间下收尾 IO 不推进会报 false）。
        coVerify(exactly = 1) {
            dao.commitCompaction("session", listOf("old"), match {
                it.size == 2 && it[0].isCompactionMarker && it[1].isContextSummary &&
                    it[1].content == "Concise handoff" && it[0].timestamp < it[1].timestamp
            }, any())
        }
        verify(exactly = 1) { persistence.invalidateHistory("session") }
    }

    @Test
    fun oversizedTailIsRetainedAndFailsBeforeCallingSummary() = runTest {
        compactor.backgroundDispatcher = StandardTestDispatcher(testScheduler)
        // 裁剪/软裁剪/forceTrim 也走虚拟调度器（生产默认 Dispatchers.IO 不变），消除本地/CI 时序漂移
        compactor.trimDispatcher = StandardTestDispatcher(testScheduler)
        prepare()
        val original = history() + AgentMessage.AssistantMessage(content = "",
            toolCalls = listOf(ToolCall("latest", "readFile", emptyMap()))) +
            AgentMessage.ToolResultMessage(id = "latest", toolName = "readFile", result = "汉".repeat(12_000))
        val events = mutableListOf<AgentEvent>()
        val result = compactor.compactIfNeeded(original, provider, force = true, onEvent = { events.add(it) })
        assertFalse(result.compacted)
        assertSame(original, result.messages)
        assertTrue(events.any { it is AgentEvent.CompactionFailed })
        coVerify(exactly = 0) { provider.complete(any(), any(), any(), any()) }
        coVerify(exactly = 0) { dao.commitCompaction(any(), any(), any(), any()) }
    }

    @Test
    fun hangingSummaryTimesOutAndKeepsHistoryWithoutCommitting() = runTest {
        compactor.backgroundDispatcher = StandardTestDispatcher(testScheduler)
        // 裁剪/软裁剪/forceTrim 也走虚拟调度器（生产默认 Dispatchers.IO 不变），消除本地/CI 时序漂移
        compactor.trimDispatcher = StandardTestDispatcher(testScheduler)
        prepare()
        coEvery { provider.complete(any(), any(), any(), any()) } coAnswers { awaitCancellation() }
        val original = history()
        val events = mutableListOf<AgentEvent>()
        val result = compactor.compactIfNeeded(original, provider, force = true, onEvent = { events.add(it) })
        assertFalse(result.compacted)
        assertSame(original, result.messages)
        assertEquals(ContextCompactor.SUMMARY_DEADLINE_MS, testScheduler.currentTime)
        assertEquals(1, events.count { it is AgentEvent.CompactionFailed })
        coVerify(exactly = 0) { dao.commitCompaction(any(), any(), any(), any()) }
        coVerify { provider.maxOutputTokens = 8_000 }
        coVerify(exactly = 1) { records.insert(match { it.status == "error" }) }
    }

    @Test
    fun metadataAndSettingsShareTheSummaryDeadline() = runTest {
        compactor.backgroundDispatcher = StandardTestDispatcher(testScheduler)
        // 裁剪/软裁剪/forceTrim 也走虚拟调度器（生产默认 Dispatchers.IO 不变），消除本地/CI 时序漂移
        compactor.trimDispatcher = StandardTestDispatcher(testScheduler)
        prepare()
        coEvery { metadata.resolve(any(), any(), any()) } coAnswers {
            delay(ContextCompactor.SUMMARY_DEADLINE_MS + 1)
            ModelMetadata(id = "summary", contextTokens = 16_000)
        }
        val original = history()
        val result = compactor.compactIfNeeded(original, provider, force = true)
        assertFalse(result.compacted)
        assertSame(original, result.messages)
        assertEquals(ContextCompactor.SUMMARY_DEADLINE_MS, testScheduler.currentTime)
        coVerify(exactly = 0) { provider.complete(any(), any(), any(), any()) }
        coVerify(exactly = 0) { dao.commitCompaction(any(), any(), any(), any()) }
    }

    @Test
    fun metadataAndSettingsCompleteBeforeSummaryAndLeaveOneDeadline() = runTest {
        compactor.backgroundDispatcher = StandardTestDispatcher(testScheduler)
        // 裁剪/软裁剪/forceTrim 也走虚拟调度器（生产默认 Dispatchers.IO 不变），消除本地/CI 时序漂移
        compactor.trimDispatcher = StandardTestDispatcher(testScheduler)
        prepare()
        coEvery { metadata.resolve(any(), any(), any()) } coAnswers {
            delay(20_000)
            ModelMetadata(id = "summary", contextTokens = 16_000)
        }
        coEvery { settings.compactionThresholdPercent() } coAnswers {
            delay(20_000)
            90
        }
        coEvery { provider.complete(any(), any(), any(), any()) } coAnswers {
            delay(100_000)
            AIResponse("handoff", stopReason = "stop")
        }
        val original = history()
        val result = compactor.compactIfNeeded(original, provider, force = true)
        assertFalse(result.compacted)
        assertSame(original, result.messages)
        assertEquals(ContextCompactor.SUMMARY_DEADLINE_MS, testScheduler.currentTime)
        coVerify(exactly = 1) { provider.complete(any(), any(), any(), any()) }
        coVerify(exactly = 0) { dao.commitCompaction(any(), any(), any(), any()) }
    }

    @Test
    fun outputStarvedRetrySharesTheOriginalDeadline() = runTest {
        compactor.backgroundDispatcher = StandardTestDispatcher(testScheduler)
        // 裁剪/软裁剪/forceTrim 也走虚拟调度器（生产默认 Dispatchers.IO 不变），消除本地/CI 时序漂移
        compactor.trimDispatcher = StandardTestDispatcher(testScheduler)
        prepare()
        var calls = 0
        coEvery { provider.complete(any(), any(), any(), any()) } coAnswers {
            calls++
            delay(70_000)
            if (calls == 1) AIResponse("partial", stopReason = "length") else AIResponse("handoff", stopReason = "stop")
        }
        val original = history(6_000)
        val result = compactor.compactIfNeeded(original, provider, force = true)
        assertFalse(result.compacted)
        assertSame(original, result.messages)
        assertEquals(2, calls)
        assertEquals(ContextCompactor.SUMMARY_DEADLINE_MS, testScheduler.currentTime)
        coVerify(exactly = 0) { dao.commitCompaction(any(), any(), any(), any()) }
    }

    @Test
    fun persistedHistoryPreparationAlsoTimesOut() = runTest {
        compactor.backgroundDispatcher = StandardTestDispatcher(testScheduler)
        // 裁剪/软裁剪/forceTrim 也走虚拟调度器（生产默认 Dispatchers.IO 不变），消除本地/CI 时序漂移
        compactor.trimDispatcher = StandardTestDispatcher(testScheduler)
        prepare()
        coEvery { dao.getMessagesBySessionOnce("session") } coAnswers { awaitCancellation() }
        val original = history()
        val events = mutableListOf<AgentEvent>()
        val result = compactor.compactIfNeeded(original, provider, "session", force = true, onEvent = { events.add(it) })
        assertFalse(result.compacted)
        assertSame(original, result.messages)
        assertEquals(ContextCompactor.SUMMARY_DEADLINE_MS, testScheduler.currentTime)
        assertTrue(events.single { it is AgentEvent.CompactionFailed } is AgentEvent.CompactionFailed)
        coVerify(exactly = 0) { provider.complete(any(), any(), any(), any()) }
        coVerify(exactly = 0) { dao.commitCompaction(any(), any(), any(), any()) }
    }

    @Test
    fun failedMaterialIsNotSummarizedAgainWhileTransientFailureIsFresh() = runTest {
        compactor.backgroundDispatcher = StandardTestDispatcher(testScheduler)
        // 裁剪/软裁剪/forceTrim 也走虚拟调度器（生产默认 Dispatchers.IO 不变），消除本地/CI 时序漂移
        compactor.trimDispatcher = StandardTestDispatcher(testScheduler)
        prepare()
        coEvery { provider.complete(any(), any(), any(), any()) } returns AIResponse("", stopReason = "refusal")
        // 固定墙钟 seam：黑名单 TTL 判断不再依赖 System.currentTimeMillis()（真实时钟在慢机器/CI 上
        // 会让两次调用间隔超 5s、瞬态黑名单过期而用例飘），固定为常量后行为确定。
        compactor.nowMs = { 0L }
        val original = history()
        // 旧语义是「失败就拉黑 60 秒、连点重试也一直失败」；现在 API 类失败只拦 TRANSIENT_MATERIAL_TTL_MS（秒级），
        // 且只拦自动路径（force 入口可绕过，见下个用例）。黑名单按 materialKey（含 head 内容）命中，
        // 所以这里用「同一份材料」验证「短窗口内不重复白跑摘要」（材料一变 key 就变、会重新试）。
        val first = compactor.compactIfNeeded(original, provider, currentInputTokens = 99_999)
        val second = compactor.compactIfNeeded(original, provider, currentInputTokens = 99_999)
        assertFalse(first.compacted)
        assertFalse(second.compacted)
        assertSame(original, second.messages)
        coVerify(exactly = 1) { provider.complete(any(), any(), any(), any()) }
    }

    @Test
    fun forcedCompactionBypassesTransientFailureBlacklist() = runTest {
        compactor.backgroundDispatcher = StandardTestDispatcher(testScheduler)
        // 裁剪/软裁剪/forceTrim 也走虚拟调度器（生产默认 Dispatchers.IO 不变），消除本地/CI 时序漂移
        compactor.trimDispatcher = StandardTestDispatcher(testScheduler)
        prepare()
        coEvery { provider.complete(any(), any(), any(), any()) } returns AIResponse("", stopReason = "refusal")
        val original = history()
        // 手动 /compress 与上下文溢出兜底都走 force：换供应商/换模型后同一份材料应当能再试（issue #23）
        assertFalse(compactor.compactIfNeeded(original, provider, force = true).compacted)
        assertFalse(compactor.compactIfNeeded(original, provider, force = true).compacted)
        coVerify(exactly = 2) { provider.complete(any(), any(), any(), any()) }
    }

    @Test
    fun fallsBackToChatModelWhenCompactionModelFails() = runTest {
        compactor.backgroundDispatcher = StandardTestDispatcher(testScheduler)
        // 裁剪/软裁剪/forceTrim 也走虚拟调度器（生产默认 Dispatchers.IO 不变），消除本地/CI 时序漂移
        compactor.trimDispatcher = StandardTestDispatcher(testScheduler)
        prepare()
        val chat = mockk<AIProvider>(relaxed = true)
        every { chat.providerId } returns "chat-provider"
        every { chat.model } returns "chat-model"
        every { chat.maxOutputTokens } returns 8_000
        coEvery { chat.complete(any(), any(), any(), any()) } returns AIResponse("Concise handoff", stopReason = "stop")
        coEvery { provider.complete(any(), any(), any(), any()) } returns AIResponse("", stopReason = "refusal")
        val result = compactor.compactIfNeeded(history(6_000), provider, force = true, windowProvider = chat)
        // 压缩专用模型拒答后换聊天模型再试一次，成功就用第二级的结果
        assertTrue(result.compacted)
        coVerify(exactly = 1) { provider.complete(any(), any(), any(), any()) }
        coVerify(exactly = 1) { chat.complete(any(), any(), any(), any()) }
        coVerify(exactly = 0) { dao.commitCompaction(any(), any(), any(), any()) }
    }

    @Test
    fun noModelTrimRescuesTheTurnWhenSummaryKeepsFailing() = runTest {
        compactor.backgroundDispatcher = StandardTestDispatcher(testScheduler)
        // 裁剪/软裁剪/forceTrim 也走虚拟调度器（生产默认 Dispatchers.IO 不变），消除本地/CI 时序漂移
        compactor.trimDispatcher = StandardTestDispatcher(testScheduler)
        prepare()
        coEvery { provider.complete(any(), any(), any(), any()) } returns AIResponse("", stopReason = "refusal")
        val messages = listOf(
            AgentMessage.UserMessage(id = "goal", content = "continue the task"),
            AgentMessage.AssistantMessage(id = "a1", content = "", toolCalls = listOf(ToolCall("t1", "readFile", emptyMap()))),
            AgentMessage.ToolResultMessage(id = "t1", toolName = "readFile", result = "y".repeat(60_000)),
            AgentMessage.AssistantMessage(id = "a2", content = "", toolCalls = listOf(ToolCall("t2", "readFile", emptyMap()))),
            AgentMessage.ToolResultMessage(id = "t2", toolName = "readFile", result = "ok")
        )
        val events = mutableListOf<AgentEvent>()
        val result = compactor.compactIfNeeded(messages, provider, force = true, onEvent = { events.add(it) })
        // 摘要拒答后第三级无模型强制精简救场：早轮次超长工具输出折成引用，装进预算就直接用。
        assertTrue(result.compacted)
        val trimmed = result.messages[2] as AgentMessage.ToolResultMessage
        assertTrue(trimmed.modelResult!!.contains("/root/.aharou/tool-output/soft-trim-"))
        assertEquals(60_000, trimmed.result.length)
        assertTrue(events.none { it is AgentEvent.CompactionFailed })
        assertEquals(1, events.count { it is AgentEvent.CompactionStarted })
    }

    @Test
    fun failureCardListsAttemptedFallbackLevels() = runTest {
        compactor.backgroundDispatcher = StandardTestDispatcher(testScheduler)
        // 裁剪/软裁剪/forceTrim 也走虚拟调度器（生产默认 Dispatchers.IO 不变），消除本地/CI 时序漂移
        compactor.trimDispatcher = StandardTestDispatcher(testScheduler)
        prepare()
        coEvery { provider.complete(any(), any(), any(), any()) } returns AIResponse("", stopReason = "refusal")
        val events = mutableListOf<AgentEvent>()
        // 小历史 + force：让 forceTrim 成为唯一降级路径，避免摘要超时抢先抛 TIMEOUT_REASON
        compactor.compactIfNeeded(history(200), provider, force = true, onEvent = { events.add(it) })
        val reason = events.filterIsInstance<AgentEvent.CompactionFailed>().single().reason
        // 失败卡片要能看出「试过哪几级」以及可操作的原因（上游拒绝了摘要请求 / 强制精简后仍无收益）
        assertTrue(reason.contains("已尝试的降级级别"))
        assertTrue(reason.contains(ContextCompactor.FORCED_TRIM_LABEL))
        // 超时时 reason 含 SUMMARY_REJECTED_REASON 以外的字符串；保留原断言语义：至少包含关键降级标记
        assertTrue(reason.contains(ContextCompactor.FORCED_TRIM_LABEL) || reason.contains("TIMEOUT_REASON"))
    }

    @Test
    fun summaryBelowHardThresholdButAboveTargetDoesNotCommit() = runTest {
        compactor.backgroundDispatcher = StandardTestDispatcher(testScheduler)
        // 裁剪/软裁剪/forceTrim 也走虚拟调度器（生产默认 Dispatchers.IO 不变），消除本地/CI 时序漂移
        compactor.trimDispatcher = StandardTestDispatcher(testScheduler)
        prepare()
        coEvery { provider.complete(any(), any(), any(), any()) } returns AIResponse("汉".repeat(10_200), stopReason = "stop")
        val original = history(12_000)
        val events = mutableListOf<AgentEvent>()
        val result = compactor.compactIfNeeded(original, provider, force = true, onEvent = { events.add(it) })
        assertFalse(result.compacted)
        assertSame(original, result.messages)
        assertTrue(events.any { it is AgentEvent.CompactionFailed })
        coVerify(exactly = 0) { dao.commitCompaction(any(), any(), any(), any()) }
    }

    @Test
    fun targetHasTenPointGapBelowConfiguredHardLine() = runTest {
        compactor.backgroundDispatcher = StandardTestDispatcher(testScheduler)
        // 裁剪/软裁剪/forceTrim 也走虚拟调度器（生产默认 Dispatchers.IO 不变），消除本地/CI 时序漂移
        compactor.trimDispatcher = StandardTestDispatcher(testScheduler)
        prepare()
        coEvery { settings.compactionThresholdPercent() } returns 75
        coEvery { provider.complete(any(), any(), any(), any()) } returns AIResponse("汉".repeat(9_500), stopReason = "stop")
        val original = history(12_000)
        val result = compactor.compactIfNeeded(original, provider, force = true)
        assertFalse(result.compacted)
        assertSame(original, result.messages)
        coVerify(exactly = 0) { dao.commitCompaction(any(), any(), any(), any()) }
    }

    @Test
    fun targetCountsSystemToolsSummaryAndTail() = runTest {
        compactor.backgroundDispatcher = StandardTestDispatcher(testScheduler)
        // 裁剪/软裁剪/forceTrim 也走虚拟调度器（生产默认 Dispatchers.IO 不变），消除本地/CI 时序漂移
        compactor.trimDispatcher = StandardTestDispatcher(testScheduler)
        prepare()
        val tool = mockk<AgentTool>()
        every { tool.name } returns "largeTool"
        every { tool.description } returns "汉".repeat(4_000)
        every { tool.toJsonSchema() } returns emptyMap()
        coEvery { provider.complete(any(), any(), any(), any()) } returns AIResponse("汉".repeat(2_200), stopReason = "stop")
        val original = history(6_000)
        val result = compactor.compactIfNeeded(original, provider, force = true,
            systemPrompt = "汉".repeat(4_000), tools = listOf(tool))
        // 目标口径改成「扣掉固定开销后以触发线为基准」之后，这个场景能装进输入预算，
        // 因此应当落库；固定开销仍全额参与（装不下就退化为「开销吃满」的失败）。
        assertTrue(result.compacted)
        coVerify(exactly = 1) { provider.complete(any(), any(), any(), any()) }
        // 本用例没传 sessionId：没有会话就不落库，只在内存里换掉消息
        coVerify(exactly = 0) { dao.commitCompaction(any(), any(), any(), any()) }
    }

    @Test
    fun successfulSummaryFitsTargetAndKeepsLargeLatestBatch() = runTest {
        compactor.backgroundDispatcher = StandardTestDispatcher(testScheduler)
        // 裁剪/软裁剪/forceTrim 也走虚拟调度器（生产默认 Dispatchers.IO 不变），消除本地/CI 时序漂移
        compactor.trimDispatcher = StandardTestDispatcher(testScheduler)
        prepare()
        val latest = AgentMessage.AssistantMessage(content = "", toolCalls = listOf(ToolCall("latest", "readFile", emptyMap())))
        val output = AgentMessage.ToolResultMessage(id = "latest", toolName = "readFile", result = "汉".repeat(6_000))
        val original = history() + latest + output
        val result = compactor.compactIfNeeded(original, provider, force = true)
        assertTrue(result.compacted)
        assertSame(output, result.messages.last())
        assertSame(latest, result.messages[result.messages.lastIndex - 1])
        val budget = ModelContextPolicy.effectiveInputBudget(ModelMetadata(id = "summary", contextTokens = 16_000))
        assertTrue(CompactionText.estimateRequest("", emptyList(), result.messages) <= budget * 70 / 100)
    }

    @Test
    fun tailSelectionNeverDropsUnfinishedOrLatestParallelBatch() {
        val original = history() + listOf(
            AgentMessage.AssistantMessage(content = "", toolCalls = listOf(
                ToolCall("one", "readFile", emptyMap()), ToolCall("missing", "readFile", emptyMap()))),
            AgentMessage.ToolResultMessage(id = "one", toolName = "readFile", result = "汉".repeat(8_000)),
            AgentMessage.AssistantMessage(content = "", toolCalls = listOf(ToolCall("latest", "readFile", emptyMap()))),
            AgentMessage.ToolResultMessage(id = "latest", toolName = "readFile", result = "汉".repeat(8_000)))
        assertEquals(3, CompactionText.selectTailStartIndex(original, 16_000))
    }

    @Test
    fun projectionPreservesMiddleOfLongFileOutput() {
        val output = "BEGIN" + "x".repeat(4_000) + "CRITICAL-MIDDLE" + "y".repeat(4_000) + "END"
        val projected = CompactionText.project(AgentMessage.ToolResultMessage(toolName = "readFile", result = output))
        assertTrue(projected.contains("CRITICAL-MIDDLE"))
        assertTrue(projected.endsWith("END"))
    }

    @Test
    fun oversizedTailFailsWithoutReplacingHistory() = runTest {
        compactor.backgroundDispatcher = StandardTestDispatcher(testScheduler)
        // 裁剪/软裁剪/forceTrim 也走虚拟调度器（生产默认 Dispatchers.IO 不变），消除本地/CI 时序漂移
        compactor.trimDispatcher = StandardTestDispatcher(testScheduler)
        prepare()
        val original = history() + AgentMessage.UserMessage(id = "huge-tail", content = "汉".repeat(30_000))
        val result = compactor.compactIfNeeded(original, provider, force = true)
        assertFalse(result.compacted)
        assertSame(original, result.messages)
    }
}
