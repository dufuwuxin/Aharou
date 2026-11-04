package com.aharou.feature.agent.domain.tool.custom

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken

/** Model-facing annotations only; never changes the executor's accepted arguments. */
object ToolSchemaOverride {
    private val gson = Gson()
    private val annotations = setOf("description", "title", "examples", "\$comment")
    private val schemaMaps = setOf("properties", "patternProperties", "\$defs", "definitions", "dependentSchemas")
    private val schemaArrays = setOf("allOf", "anyOf", "oneOf", "prefixItems")
    private val singleSchemas = setOf(
        "items", "additionalItems", "additionalProperties", "unevaluatedProperties", "unevaluatedItems",
        "contains", "propertyNames", "not", "if", "then", "else", "contentSchema"
    )
    private val mapType = object : TypeToken<Map<String, Any>>() {}.type

    fun isCompatible(original: Map<String, Any>, schemaJson: String): Boolean =
        validated(original, schemaJson) != null

    /** Returns null on malformed JSON, bad annotation types or any structural change. */
    fun validated(original: Map<String, Any>, schemaJson: String): Map<String, Any>? = runCatching {
        val base = JsonParser.parseString(gson.toJson(original))
        val edited = JsonParser.parseString(schemaJson)
        require(edited.isJsonObject)
        require(structure(base) == structure(edited))
        gson.fromJson<Map<String, Any>>(edited, mapType)
    }.getOrNull()

    /** Revalidate against the current original, so an updated MCP schema invalidates stale edits. */
    fun apply(original: Map<String, Any>, schemaJson: String?): Map<String, Any> =
        schemaJson?.let { validated(original, it) } ?: original

    private fun structure(schema: JsonElement): JsonElement {
        if (!schema.isJsonObject) return schema.deepCopy()
        val result = JsonObject()
        for ((key, value) in schema.asJsonObject.entrySet()) {
            if (key in annotations) {
                require(if (key == "examples") value.isJsonArray else value.isJsonPrimitive && value.asJsonPrimitive.isString)
                continue
            }
            result.add(key, when {
                key in schemaMaps && value.isJsonObject -> JsonObject().apply {
                    // Keys here are parameter/definition names, not schema keywords (even "description").
                    value.asJsonObject.entrySet().forEach { (name, child) -> add(name, structure(child)) }
                }
                key in schemaArrays && value.isJsonArray -> JsonArray().apply {
                    value.asJsonArray.forEach { add(structure(it)) }
                }
                key in singleSchemas && value.isJsonArray -> JsonArray().apply {
                    value.asJsonArray.forEach { add(structure(it)) }
                }
                key in singleSchemas -> structure(value)
                else -> value.deepCopy()
            })
        }
        return result
    }
}
