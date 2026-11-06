package com.aharou.feature.agent.domain.prompt

import com.aharou.feature.agent.domain.model.AgentImage
import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.workflow.ContextTokenEstimator
import com.aharou.feature.agent.domain.workflow.RequestContextComposition
import org.junit.Assert.*
import org.junit.Test

class InjectedContextStoreTest {
    private fun record(store: InjectedContextStore, id: String, text: String) {
        store.recordRequest(id, RequestContextComposition.capture(store.beginRequest(id), text, emptyList(), emptyList()))
    }
    @Test fun sessionsRemainIsolatedAndLatestRequestReplacesPrevious() {
        val store = InjectedContextStore()
        record(store, "a", "aaa")
        record(store, "b", "bbb")
        record(store, "a", "new")
        assertTrue(store.bySession.value.getValue("a").contains("new"))
        assertTrue(store.bySession.value.getValue("b").contains("bbb"))
        assertEquals(2, store.bySession.value.size)
    }
    @Test fun promptBuildIsNotPublishedAsAnActualRequest() {
        val store = InjectedContextStore()
        store.record("a", "draft prompt")
        assertNull(store.snapshot("a"))
        assertTrue(store.bySession.value.isEmpty())
    }
    @Test fun retryAndCompactionInvalidateLateResults() {
        val store = InjectedContextStore()
        val v1 = store.beginRequest("a")
        val v2 = store.beginRequest("a")
        store.recordRequest("a", RequestContextComposition.capture(v1, "old", emptyList(), emptyList()))
        assertNull(store.snapshot("a"))
        store.recordRequest("a", RequestContextComposition.capture(v2, "current", emptyList(), emptyList()))
        assertNotNull(store.snapshot("a"))
        store.forget("a")
        store.recordRequest("a", RequestContextComposition.capture(v2, "late", emptyList(), emptyList()))
        assertNull(store.snapshot("a"))
    }
    @Test fun allPartsSumToEstimatorIncludingUnicodeToolsHistoryAndImages() {
        val messages = listOf(AgentMessage.UserMessage(content = "中😀abc", images = listOf(AgentImage("image/png", "PRIVATE_BYTES"))),
            AgentMessage.ToolResultMessage(toolName = "read", result = "large original", modelResult = "folded"))
        val snapshot = RequestContextComposition.capture(1, "system😀", messages, emptyList())
        assertEquals(ContextTokenEstimator.estimate("system😀", messages, emptyList()), snapshot.estimatedTokens)
        assertEquals(snapshot.estimatedTokens, snapshot.parts.sumOf { it.estimatedTokens })
        assertEquals(4096, snapshot.parts.single { it.source == "images" }.estimatedTokens)
        assertFalse(snapshot.text.contains("PRIVATE_BYTES"))
        assertFalse(snapshot.text.contains("large original"))
        assertTrue(snapshot.text.contains("folded"))
    }
    @Test fun externalMetadataDoesNotExposeHeadingsOrBodies() {
        val store = InjectedContextStore()
        record(store, "a", "## PRIVATE_HEADING\nPRIVATE_BODY")
        val json = store.requestMetadata("a").toString() + store.snapshot("a")!!.externalSections()
        assertFalse(json.contains("PRIVATE_HEADING"))
        assertFalse(json.contains("PRIVATE_BODY"))
        assertTrue(json.contains("not provider billing"))
    }
    @Test fun legacyUnicodeHeadingCompositionStillPartitionsExactly() {
        val text = "lead\n## Memory\n中😀\n## Rules\nabc"
        val snapshot = InjectedContextStore.composition(text)
        assertEquals(text.codePointCount(0, text.length), snapshot.characters)
        assertEquals(snapshot.characters, snapshot.sections.sumOf { it.characters })
    }
}
