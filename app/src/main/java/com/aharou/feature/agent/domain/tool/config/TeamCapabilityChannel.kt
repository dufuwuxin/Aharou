package com.aharou.feature.agent.domain.tool.config

import android.content.Context
import com.aharou.core.config.ConfigAccess
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.TeamEditCapability
import com.aharou.core.config.audit.*
import com.aharou.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.*
import java.util.UUID

internal fun teamCapabilityRequest(context: Context, action: String, address: String, raw: String?, workspace: String?,
    fields: com.aharou.feature.agent.data.ConfigTeamControlFields,
    roles: com.aharou.feature.agent.data.ConfigTeamRoleFields? = null): ToolResult =
    try {
        TeamEditCapability.use(address, workspace) { path -> synchronized(com.aharou.feature.agent.domain.skill.SkillParser) {
            val registry = ConfigRegistry.isolated().also {
                fields.forWorkspace(requireNotNull(workspace)).registerInto(it)
                roles?.forWorkspace(workspace)?.registerInto(it)
            }
            fun collectionValue(collection: com.aharou.core.config.ConfigCollection): ConfigValue = ConfigValue.Obj(
                collection.childIds().associateWith { id -> ConfigValue.Obj(collection.fields(id).associate { it.path to it.read() }) })
            if (path == "roles") {
                val collection = registry.collection(path) ?: error("Role channel unavailable")
                val before = collectionValue(collection)
                val revision = TeamEditCapability.fingerprint(before.jsonString())
                return@use when (action) {
                    "get", "list", "schema" -> ToolResult.Success(buildJsonObject {
                        roles?.let { put("formTemplate", it.creationTemplate()) }
                        put("collection", path); put("description", collection.description); put("revision", revision)
                        put("actions", buildJsonArray { listOf("get", "add", "remove").forEach { add(it) } })
                        put("value", Json.parseToJsonElement(before.jsonString()))
                    })
                    "add", "remove" -> {
                        val input = Json.parseToJsonElement(requireNotNull(raw)).jsonObject
                        check(input["expectedRevision"]?.jsonPrimitive?.content == revision) { "Stale role collection revision; get roles again" }
                        val value = ConfigValue.decode(input.getValue("value").toString()) ?: error("Invalid role payload")
                        val id = if (action == "add") collection.add(value) else {
                            val id = input.getValue("value").jsonObject.getValue("id").jsonPrimitive.content
                            collection.remove(id); id
                        }
                        val auditRecorded = runCatching { ConfigAuditLog.get().append(ConfigAuditEntry(UUID.randomUUID().toString(), System.currentTimeMillis(),
                            ConfigAuditActor.AGENT, null, "team-capability", path, before.jsonString(), collectionValue(collection).jsonString(),
                            System.currentTimeMillis(), ConfigAuditStatus.APPLIED, null, "User-enabled scoped role channel")) }.isSuccess
                        ToolResult.Success(buildJsonObject { put("applied", true); put("id", id); put("auditRecorded", auditRecorded) })
                    }
                    else -> error("Role collection only allows get/list/schema/add/remove")
                }
            }
            if (path.isEmpty()) {
                require(action in setOf("get", "list", "schema")) { "Choose a returned field address first" }
                ToolResult.Success(buildJsonObject {
                    put("expires", "one hour or immediately when disabled; app restart revokes")
                    put("usage", "get a field address for revision; set with value={expectedRevision,value}. Routes, stage conditions, communication and complete scoped role forms are writable. Roles collection uses add/remove with value={expectedRevision,value}; role forms use set. Global changes affect every project using that team.")
                    if (roles != null) put("collections", buildJsonArray { add(buildJsonObject {
                        put("address", TeamEditCapability.childAddress(address, "roles")); put("collection", "roles")
                        put("description", registry.collection("roles")!!.description)
                        put("actions", buildJsonArray { listOf("get", "add", "remove").forEach { add(it) } })
                    }) })
                    put("fields", buildJsonArray {
                        listOf("teams", "team.stages", "roles").flatMap { base ->
                            registry.collection(base)?.let { collection -> collection.childIds().flatMap { collection.fields(it).map { field -> field.path } } }.orEmpty()
                        }.filter(TeamEditCapability::allowed).forEach { field ->
                            val definition = registry.resolveField(field) ?: error("Unknown field")
                            add(buildJsonObject {
                                put("address", TeamEditCapability.childAddress(address, field))
                                put("field", field); put("type", definition.valueSchema.helpDescription)
                                put("access", definition.access.name); put("description", definition.description)
                                put("value", Json.parseToJsonElement(definition.read().jsonString()))
                            })
                        }
                    })
                })
            } else {
                val field = registry.resolveField(path) ?: error("Unknown team field")
                val old = field.read()
                val revision = TeamEditCapability.fingerprint(old.jsonString())
                when (action) {
                    "get", "schema" -> ToolResult.Success(buildJsonObject {
                        put("field", path); put("type", field.valueSchema.helpDescription); put("description", field.description); put("access", field.access.name); put("revision", revision); put("value", Json.parseToJsonElement(old.jsonString()))
                    })
                    "set" -> {
                        require(field.access == ConfigAccess.READWRITE)
                        val input = Json.parseToJsonElement(requireNotNull(raw)).jsonObject
                        check(input["expectedRevision"]?.jsonPrimitive?.content == revision) { "Stale team revision; get the field again" }
                        val value = ConfigValue.decode(input.getValue("value").toString()) ?: error("Invalid value")
                        field.valueSchema.validate(value)
                        field.write(value)
                        val auditRecorded = runCatching { ConfigAuditLog.get().append(ConfigAuditEntry(UUID.randomUUID().toString(), System.currentTimeMillis(),
                            ConfigAuditActor.AGENT, null, "team-capability", path, old.jsonString(), value.jsonString(),
                            System.currentTimeMillis(), ConfigAuditStatus.APPLIED, null, "User-enabled scoped team channel")) }.isSuccess
                        ToolResult.Success(buildJsonObject { put("field", path); put("applied", true); put("auditRecorded", auditRecorded) })
                    }
                    else -> error("Only get/list/set are allowed; no reveal, logs, add or remove")
                }
            }
        } }
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (error: Exception) {
        val detail = error.message?.takeIf { it.startsWith("Role is referenced; update references first:") }
            ?: "disabled, expired, wrong workspace, stale revision, unknown scope or invalid field/value"
        ToolResult.Error("Team channel rejected the request: $detail", "TEAM_CHANNEL_REJECTED")
    }
