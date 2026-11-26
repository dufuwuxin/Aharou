package com.aharou.feature.agent.data

import com.aharou.core.config.*
import com.aharou.core.config.fields.ClosureField
import com.aharou.feature.agent.domain.model.AgentMode
import com.aharou.feature.agent.domain.subagent.*
import com.aharou.feature.agent.domain.teamworkflow.TeamWorkflowParser
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.WorkspacePathMapper
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

/** Registered only in the isolated, user-enabled capability registry. */
@Singleton
class ConfigTeamRoleFields @Inject constructor(
    private val roles: AgentDefinitionRepository,
    private val fileAccess: FileAccessProvider
) {
    fun forWorkspace(workspace: String): ConfigTeamRoleFields =
        ConfigTeamRoleFields(roles.forWorkspace(workspace), fileAccess.forWorkspace(workspace).checked())

    fun creationTemplate(): JsonObject = form(AgentDefinition(name = "", description = "", prompt = ""))

    /** Safe CRUD index: exposes names and ids, never existing role prompts/configuration. */
    fun collectionSummary() = buildJsonObject {
        put("roles", buildJsonArray {
            AgentDefinitionScope.entries.forEach { scope -> roles.listInScope(scope).forEach { role -> add(buildJsonObject {
                put("id", JsonPrimitive(id(scope, role.name))); put("scope", JsonPrimitive(scope.name.lowercase()))
                put("name", JsonPrimitive(role.name)); put("description", JsonPrimitive(role.description))
            }) } }
        })
        put("formTemplate", creationTemplate())
        put("createValueShape", JsonPrimitive("{scope:global|project,form:formTemplate}"))
    }

    fun registerInto(registry: ConfigRegistry) { registry.register(Roles()) }

    private fun id(scope: AgentDefinitionScope, name: String) = scope.name.lowercase() + ":" +
        name.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun all(): Map<String, Pair<AgentDefinitionScope, AgentDefinition>> = buildMap {
        AgentDefinitionScope.entries.forEach { scope -> roles.listInScope(scope).forEach { put(id(scope, it.name), scope to it) } }
    }

    private fun form(role: AgentDefinition): JsonObject = buildJsonObject {
        put("name", JsonPrimitive(role.name)); put("description", JsonPrimitive(role.description)); put("prompt", role.prompt)
        put("providerId", role.providerId?.let(::JsonPrimitive) ?: JsonNull)
        put("model", role.model?.let(::JsonPrimitive) ?: JsonNull)
        put("reasoningEffort", role.reasoningEffort?.let(::JsonPrimitive) ?: JsonNull)
        put("mode", role.mode?.name?.let(::JsonPrimitive) ?: JsonNull)
        fun strings(key: String, values: Iterable<String>) { put(key, buildJsonArray { values.forEach { add(it) } }) }
        strings("allowedTools", role.allowedTools); strings("disallowedTools", role.disallowedTools)
        strings("skills", role.skills); strings("inject", role.inject.map { it.token }); strings("tags", role.tags)
        put("keywordInjections", buildJsonArray { role.keywordInjections.forEach { item -> add(buildJsonObject {
            put("keywords", buildJsonArray { item.keywords.forEach { add(it) } }); put("injectContent", item.injectContent)
        }) } })
    }

    private fun decode(value: ConfigValue): AgentDefinitionForm {
        val obj = Json.parseToJsonElement(value.jsonString()).jsonObject
        fun text(key: String): String = obj.getValue(key).jsonPrimitive.let {
            require(it.isString) { "$key must be a string" }; it.content
        }
        fun nullable(key: String): String? = if (obj.getValue(key) == JsonNull) null else text(key)
        fun strings(key: String) = obj.getValue(key).jsonArray.map {
            require(it.jsonPrimitive.isString) { "$key must contain strings" }; it.jsonPrimitive.content
        }
        val mode = nullable("mode")?.let { token -> AgentMode.entries.firstOrNull { it.name == token }
            ?: error("Unknown role mode") }
        return AgentDefinitionForm(
            name = text("name"), description = text("description"), prompt = text("prompt"),
            providerId = nullable("providerId"), model = nullable("model"), reasoningEffort = nullable("reasoningEffort"), mode = mode,
            allowedTools = strings("allowedTools"), disallowedTools = strings("disallowedTools"), skills = strings("skills"),
            inject = strings("inject").map { InjectPart.fromToken(it) ?: error("Unknown injection") }.toSet(),
            tags = strings("tags"), keywordInjections = obj.getValue("keywordInjections").jsonArray.map { item ->
                val entry = item.jsonObject
                KeywordInjection(entry.getValue("keywords").jsonArray.map { require(it.jsonPrimitive.isString); it.jsonPrimitive.content },
                    entry.getValue("injectContent").jsonPrimitive.let { require(it.isString); it.content })
            }
        )
    }

    /** Scan both scopes, including shadowed definitions; never silently remove references. */
    private fun references(name: String): List<String> = buildList {
        fun matches(value: String?) = value?.equals(name, ignoreCase = true) == true
        for (root in listOf("~/.aharou", "${WorkspacePathMapper.CONTAINER_ROOT}/.aharou")) {
            val teamRoot = "$root/teams"
            if (fileAccess.isDirectory(teamRoot)) fileAccess.listFiles(teamRoot).filter { !it.isDirectory && it.name.endsWith(".md") }.forEach { file ->
                val path = "$teamRoot/${file.name}"
                val team = AgentTeamParser.parse(fileAccess, path) ?: error("Cannot inspect team reference: $path")
                if (team.members.any { matches(it.name) }) add("$path:members")
                if (team.admins.any(::matches)) add("$path:admins")
                team.stages.forEach { stage -> stage.tasks.filter { matches(it.agent) }.forEach { add("$path:stage=${stage.id}:task=${it.id}") } }
            }
            val workflowRoot = "$root/workflows"
            if (fileAccess.isDirectory(workflowRoot)) fileAccess.listFiles(workflowRoot).filter { !it.isDirectory && it.name.endsWith(".md") }.forEach { file ->
                val path = "$workflowRoot/${file.name}"
                val workflow = TeamWorkflowParser.parse(fileAccess, path) ?: error("Cannot inspect workflow reference: $path")
                workflow.teams.filter { matches(it.lead) }.forEach { add("$path:lead:${it.teamName}") }
            }
        }
    }

    private fun requireUnreferenced(name: String) {
        val refs = references(name)
        require(refs.isEmpty()) { "Role is referenced; update references first: ${refs.joinToString("; ")}" }
    }

    private inner class Roles : ConfigCollection {
        override val basePath = "roles"
        override val displayName = "Role definitions"
        override val description = "Scoped role CRUD. Add {scope:global|project,form:{...}}; remove {id}. Global changes affect all projects in this execution environment. Get the collection revision before add/remove. Form replaces all fields; preserve nullable provider/model/mode and every list."
        override fun childIds() = all().keys.toList()
        override fun fields(forId: String): List<ConfigField> {
            if (forId !in all()) return emptyList()
            return listOf(ClosureField(path = "roles.$forId.form", displayName = "Complete role form",
                description = "Full role form: name,description,prompt,providerId,model,reasoningEffort,mode,allowedTools,disallowedTools,skills,inject,keywordInjections,tags. Rename is rejected while referenced. Changes affect future dispatches.",
                valueSchema = ConfigSchema.Json,
                reader = { ConfigValue.decode(form(all().getValue(forId).second).toString())!! },
                writer = { value ->
                    val (scope, role) = all().getValue(forId)
                    val updated = decode(value)
                    if (updated.name.trim() != role.name) requireUnreferenced(role.name)
                    roles.save(updated, scope, role.name)?.let { error("Role save failed: $it") }
                }))
        }
        override fun add(payload: ConfigValue): String {
            val obj = (payload as? ConfigValue.Obj)?.value ?: error("Expected role object")
            val scopeToken = (obj["scope"] as? ConfigValue.Str)?.value ?: error("Missing scope")
            val scope = AgentDefinitionScope.entries.firstOrNull { it.name.lowercase() == scopeToken } ?: error("Unknown scope")
            val role = decode(obj.getValue("form"))
            roles.save(role, scope)?.let { error("Role create failed: $it") }
            return id(scope, role.name.trim())
        }
        override fun remove(id: String) {
            val (scope, role) = all()[id] ?: error("Unknown scoped role")
            requireUnreferenced(role.name)
            check(roles.delete(role.name, scope)) { "Role delete failed" }
        }
    }
}
