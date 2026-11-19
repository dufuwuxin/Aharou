package com.aharou.feature.agent.domain.tool

import com.aharou.feature.agent.domain.model.AgentMessage
import org.junit.Assert.*
import org.junit.Test

class StableToolResultProjectionTest {
    private fun result(id: String) = AgentMessage.ToolResultMessage(id, "readFile",
        """{"output_path":"/archive/$id","output_total_chars":2000,"body":"${"x".repeat(2000)}"}""")

    @Test fun appendingMoreThanRetentionNeverRewritesAnAlreadySentResult() {
        val projection = StableToolResultProjection()
        val first = listOf(result("a"))
        val sent = projection.project(first, 8)
        val more = first + (1..12).map { result("b$it") }
        assertEquals(sent, projection.project(more, 8).take(1))
        // A new execution may apply its current retention once, before sending its first request.
        assertTrue((StableToolResultProjection().project(more, 8).first() as AgentMessage.ToolResultMessage).result.length < 200)
    }
    @Test fun retentionChangesTakeEffectAndNeverAlterSourceHistory() {
        val projection = StableToolResultProjection()
        val source = listOf(result("a"))
        assertEquals(source, projection.project(source, 8))
        val folded = projection.project(source, 0)
        assertTrue((folded.single() as AgentMessage.ToolResultMessage).result.length < 200)
        assertTrue(source.single().result.length > 2000)
        assertEquals(source, projection.project(source, 8))
    }
    @Test fun explicitCompactionAndChangedResultsInvalidateTheOldProjection() {
        val projection = StableToolResultProjection()
        val source = result("a")
        projection.project(listOf(source), 8)
        val changed = source.copy(result = "compacted source")
        assertEquals(listOf(changed), projection.project(listOf(changed), 8))
        projection.project(emptyList(), 8)
        assertEquals(listOf(source), projection.project(listOf(source), 8))
    }
}
