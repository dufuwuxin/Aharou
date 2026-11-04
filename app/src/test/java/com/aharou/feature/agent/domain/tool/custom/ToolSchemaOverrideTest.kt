package com.aharou.feature.agent.domain.tool.custom

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSchemaOverrideTest {
    private val original: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf("description" to mapOf("type" to "string", "enum" to listOf("one"), "description" to "old")),
        "required" to listOf("description")
    )
    private val json = Gson().toJson(original)

    @Test fun metadataChangesKeepStructure() {
        val edited = json.replace("old", "new").dropLast(1) + ",\"title\":\"Input\",\"examples\":[{}],\"\$comment\":\"Note\"}"
        assertTrue(ToolSchemaOverride.isCompatible(original, edited))
        assertEquals("Input", ToolSchemaOverride.apply(original, edited)["title"])
    }

    @Test fun structuralChangesAreRejectedIncludingParameterNamedDescription() {
        listOf(
            json.replace("string", "number"),
            json.replace("one", "two"),
            json.replace("\"required\":[\"description\"]", "\"required\":[]"),
            json.replace("\"description\":{", "\"renamed\":{"),
            json.dropLast(1) + ",\"additionalProperties\":false}",
            "{bad JSON", "[]", json.dropLast(1) + ",\"title\":42}"
        ).forEach { assertFalse(it, ToolSchemaOverride.isCompatible(original, it)) }
    }

    @Test fun staleOrMalformedOverridesFallBackToCurrentOriginal() {
        val updated = original + ("additionalProperties" to false)
        assertEquals(updated, ToolSchemaOverride.apply(updated, json))
        assertEquals(original, ToolSchemaOverride.apply(original, "invalid"))
    }

    @Test fun enumObjectValuesCannotHideStructuralChangesAsAnnotations() {
        val schema: Map<String, Any> = mapOf("enum" to listOf(mapOf("description" to "value")))
        assertFalse(ToolSchemaOverride.isCompatible(schema, """{"enum":[{"description":"changed"}]}"""))
    }
}
