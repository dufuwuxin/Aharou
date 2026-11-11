package com.aharou.feature.agent.domain.session

import com.aharou.feature.agent.data.local.dao.AgentMessageDao
import com.aharou.feature.agent.data.local.dao.toHistoryRow
import com.aharou.feature.agent.data.local.database.AgentDatabase
import com.aharou.feature.agent.data.local.entity.AgentMessageEntity
import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.presentation.MessageRole
import com.aharou.feature.agent.domain.model.AgentImage
import com.aharou.feature.agent.domain.tool.ToolCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import org.junit.Assert.assertFalse
import com.aharou.feature.agent.domain.tool.foldStoredToolResults
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MessagePersistenceArchiveTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val dao = mockk<AgentMessageDao>(relaxed = true)
    private val database = mockk<AgentDatabase>(relaxed = true)

    private fun persistence(store: MessageArchiveStore) = MessagePersistenceUseCase(dao, database, store)

    private fun entity(id: String, role: MessageRole, timestamp: Long) = AgentMessageEntity(
        id = id,
        sessionId = "session",
        role = role.name,
        content = "long".repeat(60_000),
        timestamp = timestamp
    )

    private fun cache(useCase: MessagePersistenceUseCase): Map<*, *> =
        MessagePersistenceUseCase::class.java.getDeclaredField("historyCache").let {
            it.isAccessible = true
            it.get(useCase) as Map<*, *>
        }

    @Test
    fun oldArchivedToolsFoldBeforeCachingButArchivesAndRecentToolsStayComplete() = runTest {
        val store = MessageArchiveStore(File(temporaryFolder.root, "fold"))
        val calls = (0..10).joinToString(",") { """{"id":"call$it","name":"readFile","arguments":{}}""" }
        val assistant = entity("assistant", MessageRole.ASSISTANT, 1).copy(
            content = "answer", reasoning = "thinking", signature = "signed", toolCallsJson = "[$calls]"
        )
        val originals = (0..10).map {
            entity("tool$it", MessageRole.TOOL, it + 2L).copy(
                content = if (it == 1) "plain".repeat(40_000) else
                    """{"output_path":"/archive/$it","output_total_chars":240000,"body":"${"x".repeat(240_000)}"}""",
                toolCallId = "call$it", toolName = "readFile"
            )
        }
        val stored = (listOf(assistant) + originals).map { store.prepareForStorage(it) }
        coEvery { dao.getHistoryRowsBySessionOnce("session") } returns stored.map { it.toHistoryRow() }
        val history = persistence(store).buildHistory("session", "[running]")
        val tools = history.filterIsInstance<AgentMessage.ToolResultMessage>()
        val expected = foldStoredToolResults(originals.map {
            AgentMessage.ToolResultMessage(id = it.toolCallId!!, messageId = it.id, toolName = "readFile", result = it.content)
        })
        assertEquals(expected, tools)
        assertTrue(tools.first().result.length < 200)
        assertEquals(originals[1].content, tools[1].result)
        assertEquals(originals.takeLast(8).map { it.content }, tools.takeLast(8).map { it.result })
        assertEquals(originals, stored.drop(1).map { store.restore(it) })
        val restoredAssistant = history.first() as AgentMessage.AssistantMessage
        assertEquals("assistant", restoredAssistant.id)
        assertEquals("thinking", restoredAssistant.reasoning)
        assertEquals("signed", restoredAssistant.signature)
        assertEquals(11, restoredAssistant.toolCalls.size)
    }

    @Test
    fun orphanToolArchivesAreNeverRestoredAndDanglingCallsAreRemoved() = runTest {
        val store = spyk(MessageArchiveStore(File(temporaryFolder.root, "orphan")))
        val orphan = store.prepareForStorage(entity("orphan", MessageRole.TOOL, 3).copy(toolCallId = "orphan"))
        val assistant = entity("assistant", MessageRole.ASSISTANT, 1).copy(content = "answer",
            toolCallsJson = """[{"id":"valid","name":"readFile","arguments":{}},{"id":"missing","name":"readFile","arguments":{}}]""")
        val valid = entity("valid", MessageRole.TOOL, 2).copy(content = "ok", toolCallId = "valid")
        coEvery { dao.getHistoryRowsBySessionOnce("session") } returns listOf(assistant, valid, orphan).map { it.toHistoryRow() }
        val history = persistence(store).buildHistory("session", "[running]")
        coVerify(exactly = 0) { store.restoreBounded(match { it.id == "orphan" }, any()) }
        assertEquals(listOf("valid"), (history.first() as AgentMessage.AssistantMessage).toolCalls.map { it.id })
        assertEquals("valid", (history.last() as AgentMessage.ToolResultMessage).id)
    }

    @Test
    fun oversizedHistoryIsReturnedButNotRetainedInCache() = runTest {
        val store = MessageArchiveStore(File(temporaryFolder.root, "budget"))
        val rows = (0..39).map {
            entity("large$it", MessageRole.ASSISTANT, it.toLong()).copy(content = "ok", signature = "s".repeat(500_000))
        }
        coEvery { dao.getHistoryRowsBySessionOnce("session") } returns rows.map { it.toHistoryRow() }
        val useCase = persistence(store)
        assertEquals(40, useCase.buildHistory("session", "[running]").size)
        assertFalse(cache(useCase).containsKey("session"))
    }

    @Test
    fun versionChangeClearsOtherStaleHistoryEntries() = runTest {
        val store = MessageArchiveStore(File(temporaryFolder.root, "stale"))
        coEvery { dao.getHistoryRowsBySessionOnce(any()) } returns listOf(entity("small", MessageRole.USER, 1).copy(content = "small").toHistoryRow())
        val useCase = persistence(store)
        useCase.buildHistory("old", "[running]")
        useCase.invalidateHistory("other")
        useCase.buildHistory("new", "[running]")
        assertFalse(cache(useCase).containsKey("old"))
        assertTrue(cache(useCase).containsKey("new"))
    }

    @Test
    fun combinedHistoryBudgetEvictsOldestSession() = runTest {
        val store = MessageArchiveStore(File(temporaryFolder.root, "combined"))
        val row = entity("signed", MessageRole.ASSISTANT, 1).copy(
            content = "answer", signature = "s".repeat(1_200_000)
        ).toHistoryRow()
        coEvery { dao.getHistoryRowsBySessionOnce(any()) } returns listOf(row)
        val useCase = persistence(store)
        repeat(4) { useCase.buildHistory("session$it", "[running]") }
        assertEquals(setOf("session1", "session2", "session3"), cache(useCase).keys)
    }

    @Test
    fun retainedBudgetIncludesImagesAndNestedToolArguments() {
        val useCase = persistence(MessageArchiveStore(File(temporaryFolder.root, "estimate")))
        val estimate = MessagePersistenceUseCase::class.java
            .getDeclaredMethod("historyRetainedBytes", List::class.java).apply { isAccessible = true }
        val large = "x".repeat(5_000_000)
        val image = AgentMessage.UserMessage(content = "image", images = listOf(AgentImage("image/jpeg", large)))
        val arguments = AgentMessage.AssistantMessage(content = "call", toolCalls = listOf(
            ToolCall("call", "readFile", mapOf("nested" to JsonArray(listOf(
                JsonObject(mapOf("payload" to JsonPrimitive(large)))
            ))))
        ))
        assertTrue((estimate.invoke(useCase, listOf(image)) as Long) >= large.length.toLong() * 2)
        assertTrue((estimate.invoke(useCase, listOf(arguments)) as Long) >= large.length.toLong() * 2)
    }

    @Test
    fun invalidationDuringBuildDoesNotCacheStaleResult() = runTest {
        val useCase = persistence(MessageArchiveStore(File(temporaryFolder.root, "late")))
        coEvery { dao.getHistoryRowsBySessionOnce("session") } answers {
            useCase.invalidateHistory("other")
            listOf(entity("small", MessageRole.USER, 1).copy(content = "answer").toHistoryRow())
        }
        assertEquals(1, useCase.buildHistory("session", "[running]").size)
        assertTrue(cache(useCase).isEmpty())
    }

    @Test
    fun persistedContentAndReasoningRemainCompleteAfterHistoryReload() = runTest {
        val store = MessageArchiveStore(File(temporaryFolder.root, "archive"))
        val saved = slot<AgentMessageEntity>()
        coEvery { dao.insert(capture(saved)) } returns Unit
        val useCase = persistence(store)
        val content = "content".repeat(50_000)
        val reasoning = "reasoning".repeat(50_000)
        useCase.persist("session", MessageRole.ASSISTANT, content, id = "message", reasoning = reasoning)
        assertTrue(saved.captured.content.toByteArray().size <= MessagePersistenceUseCase.MAX_CONTENT_BYTES)
        assertTrue(requireNotNull(saved.captured.reasoning).toByteArray().size <= MessagePersistenceUseCase.MAX_CONTENT_BYTES)
        coEvery { dao.getHistoryRowsBySessionOnce("session") } returns listOf(saved.captured.toHistoryRow())
        val reloaded = persistence(MessageArchiveStore(File(temporaryFolder.root, "archive")))
        assertEquals(content, reloaded.restore(saved.captured).content)
        assertEquals(reasoning, reloaded.restore(saved.captured).reasoning)
        val history = reloaded.buildHistory("session", "[running]").single() as AgentMessage.AssistantMessage
        assertEquals(content, history.content)
        assertEquals(reasoning, history.reasoning)
    }

    @Test
    fun updateContentPreservesOldSnapshotAndStoresBoundedNewSnapshot() = runTest {
        val store = MessageArchiveStore(File(temporaryFolder.root, "archive"))
        val original = entity("message", MessageRole.ASSISTANT, 1)
        val old = store.prepareForStorage(original)
        coEvery { dao.getMessageById("message") } returns old
        val savedContent = slot<String>()
        coEvery { dao.updateMessageContent("message", capture(savedContent)) } returns Unit
        val replacement = "new".repeat(100_000)
        persistence(store).updateContent("message", replacement)
        assertTrue(savedContent.captured.toByteArray().size <= MessagePersistenceUseCase.MAX_CONTENT_BYTES)
        assertEquals(replacement, store.restore(old.copy(content = savedContent.captured)).content)
        assertEquals(original, store.restore(old))
    }

    @Test
    fun deletingUserAlsoDeletesLaterRowsAndTheirArchives() = runTest {
        val store = mockk<MessageArchiveStore>(relaxed = true)
        val user = entity("user", MessageRole.USER, 1)
        val response = entity("assistant", MessageRole.ASSISTANT, 2)
        coEvery { dao.getMessageById("user") } returns user
        coEvery { dao.getMessagesBySessionOnce("session") } returns listOf(user, response)
        persistence(store).deleteMessage("user")
        coVerify(exactly = 1) { dao.deleteMessagesAfterTimestamp("session", 1) }
        coVerify(exactly = 1) { dao.deleteMessageById("user") }
        coVerify(exactly = 1) { store.deleteMessage("session", "user") }
        coVerify(exactly = 1) { store.deleteMessage("session", "assistant") }
    }

    @Test
    fun rewindDeletesOlderSummaryAndMarkerArchivesButPreservesEarlierMessages() = runTest {
        val store = mockk<MessageArchiveStore>(relaxed = true)
        val kept = entity("kept", MessageRole.USER, 1)
        val summary = entity("summary", MessageRole.ASSISTANT, 2).copy(isContextSummary = true)
        val marker = entity("marker", MessageRole.USER, 3).copy(isCompactionMarker = true)
        val removed = entity("removed", MessageRole.ASSISTANT, 4)
        coEvery { dao.getMessagesBySessionOnce("session") } returns listOf(kept, summary, marker, removed)
        persistence(store).rewindConversation("session", 4)
        coVerify(exactly = 1) { dao.rewindConversation("session", 4) }
        coVerify(exactly = 0) { store.deleteMessage("session", "kept") }
        coVerify(exactly = 1) { store.deleteMessage("session", "summary") }
        coVerify(exactly = 1) { store.deleteMessage("session", "marker") }
        coVerify(exactly = 1) { store.deleteMessage("session", "removed") }
    }

    @Test
    fun deletingFromTimestampOnlyRemovesArchivesForDeletedRows() = runTest {
        val store = mockk<MessageArchiveStore>(relaxed = true)
        val user = entity("user", MessageRole.USER, 1)
        val response = entity("assistant", MessageRole.ASSISTANT, 2)
        coEvery { dao.getMessagesBySessionOnce("session") } returns listOf(user, response)
        persistence(store).deleteMessagesFromTimestamp("session", 2)
        coVerify(exactly = 1) { dao.deleteMessagesFromTimestamp("session", 2) }
        coVerify(exactly = 0) { store.deleteMessage("session", "user") }
        coVerify(exactly = 1) { store.deleteMessage("session", "assistant") }
    }
}
