package com.aharou.feature.settings.domain.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** An ordered set of public provider/model references used as one selectable model. */
data class ModelRouteGroup(
    val id: String,
    val name: String,
    val models: List<ModelRouteMember>,
) {
    val selectionId: String get() = "route:$id"

    companion object {
        fun idFromSelection(selectionId: String): String? =
            selectionId.takeIf { it.startsWith("route:") }?.removePrefix("route:")?.takeIf { it.isNotBlank() }
    }
}

data class ModelRouteMember(val providerId: String, val model: String)

data class ModelRouteGroupForm(val name: String, val models: List<ModelRouteMember>)

/** Stable JSON codec shared by DataStore and the config bridge. */
object ModelRouteGroupCodec {
    private val json = Json { ignoreUnknownKeys = false }

    fun encode(groups: List<ModelRouteGroup>): String = buildJsonArray {
        groups.forEach { group ->
            add(buildJsonObject {
                put("id", JsonPrimitive(group.id))
                put("name", JsonPrimitive(group.name))
                put("models", members(group.models))
            })
        }
    }.toString()

    fun decode(raw: String): List<ModelRouteGroup> {
        if (raw.isBlank()) return emptyList()
        return json.parseToJsonElement(raw).jsonArray.map { item ->
            val value = item.jsonObject
            ModelRouteGroupCodec.validate(ModelRouteGroup(
                id = value.requiredText("id"),
                name = value.requiredText("name"),
                models = decodeMembers(value["models"] ?: error("models is required")),
            ))
        }
    }

    fun form(group: ModelRouteGroup): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(group.name))
        put("models", members(group.models))
    }

    fun parseForm(raw: String): ModelRouteGroupForm {
        val value = json.parseToJsonElement(raw).jsonObject
        require(value.keys == setOf("name", "models")) { "model route form contains unsupported fields" }
        val form = ModelRouteGroupForm(
            name = value.requiredText("name").trim(),
            models = decodeMembers(value["models"] ?: error("models is required")),
        )
        require(form.name.isNotBlank()) { "name must not be blank" }
        require(form.name.length <= 80) { "name is too long" }
        require(form.models.isNotEmpty()) { "models must not be empty" }
        require(form.models.none { it.providerId.startsWith("route:") }) { "providerId must be a real provider id" }
        return form
    }

    fun validate(group: ModelRouteGroup): ModelRouteGroup {
        require(group.id.isNotBlank() && !group.id.contains(':')) { "invalid route id" }
        parseForm(form(group).toString())
        return group
    }

    private fun members(values: List<ModelRouteMember>): JsonArray = buildJsonArray {
        values.forEach { member ->
            add(buildJsonObject {
                put("providerId", JsonPrimitive(member.providerId))
                put("model", JsonPrimitive(member.model))
            })
        }
    }

    private fun decodeMembers(element: kotlinx.serialization.json.JsonElement): List<ModelRouteMember> =
        element.jsonArray.map { item ->
            val value = item.jsonObject
            require(value.keys == setOf("providerId", "model")) { "model reference contains unsupported fields" }
            ModelRouteMember(value.requiredText("providerId"), value.requiredText("model"))
                .also { require(it.providerId.isNotBlank() && it.model.isNotBlank()) }
        }

    private fun JsonObject.requiredText(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("$key must be a string")
}
