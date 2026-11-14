package com.aharou.feature.agent.data

import com.aharou.core.config.ConfigCollection
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigRisk
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.core.config.fields.ReadOnlyField
import com.aharou.feature.agent.domain.subagent.AgentDefinitionScope
import com.aharou.feature.agent.domain.teamworkflow.TeamWorkflowEntry
import com.aharou.feature.agent.domain.teamworkflow.TeamWorkflowForm
import com.aharou.feature.agent.domain.teamworkflow.TeamWorkflowRepository
import com.aharou.feature.agent.domain.teamworkflow.WorkflowTeamRefForm
import com.aharou.feature.agent.domain.teamworkflow.toForm
import com.aharou.feature.agent.domain.subagent.AgentTeamEntry
import com.aharou.feature.agent.domain.subagent.AgentTeamRepository
import com.aharou.feature.agent.domain.subagent.AgentTeamStageForm
import com.aharou.feature.agent.domain.subagent.TeamFlowNode
import com.aharou.feature.agent.domain.subagent.TeamRoute
import com.aharou.feature.agent.domain.subagent.TeamRouteAction
import com.aharou.feature.agent.domain.subagent.TeamCommunication
import com.aharou.feature.agent.domain.subagent.toForm
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfigTeamControlFields @Inject constructor(
    private val teams: AgentTeamRepository,
    private val workflows: TeamWorkflowRepository
) {
    fun forWorkspace(workspace: String): ConfigTeamControlFields =
        ConfigTeamControlFields(teams.forWorkspace(workspace), workflows.forWorkspace(workspace))

    fun registerInto(registry: ConfigRegistry) {
        registry.register(Stages()); registry.register(Teams()); registry.register(Workflows())
    }

    private fun entries(): Map<String, Pair<AgentTeamEntry, Int>> = buildMap {
        teams.listAll().forEach { entry -> entry.team.stages.indices.forEach { index ->
            val nameId = entry.team.name.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            put("${entry.scope.name.lowercase()}:$nameId:${index + 1}", entry to index)
        } }
    }

    private fun stage(id: String): AgentTeamStageForm {
        val (entry, index) = entries()[id] ?: throw ConfigError.InvalidValue("Unknown team stage")
        return entry.team.toForm(entry.scope).stages[index]
    }

    private fun update(id: String, change: (AgentTeamStageForm) -> AgentTeamStageForm) {
        val (entry, index) = entries()[id] ?: throw ConfigError.InvalidValue("Unknown team stage")
        val form = entry.team.toForm(entry.scope)
        // Retain per-task prompts and write leases instead of expanding the shared prompt again.
        val stages = form.stages.mapIndexed { i, item -> (if (i == index) change(item) else item).copy(prompt = "") }
        teams.save(form.copy(stages = stages), entry.team.name)?.let {
            throw ConfigError.InvalidValue("Failed to save team: $it")
        }
    }

    /** Team-scoped routes are part of the team collection, not global behavior switches. */
    private inner class Teams : ConfigCollection {
        override val basePath = "teams"
        override val displayName = "Team workflow"
        override val description = "Stable scope:hex-name ids; routes use concrete stage/task ids."
        override val addable = false
        override val removable = false
        private fun all() = entries().mapKeys { (id, _) -> id.substringBeforeLast(':') }
            .mapValues { (_, value) -> value.first }
        override fun childIds() = all().keys.toList()
        override fun fields(forId: String): List<ConfigField> {
            if (forId !in all()) return emptyList()
            fun entry() = all()[forId] ?: throw ConfigError.InvalidValue("Unknown team")
            return listOf(
                ClosureField(path = "$basePath.$forId.nodes", displayName = "Workflow endpoints",
                    description = "Read-only stable stage/task ids and member labels.", valueSchema = ConfigSchema.Json,
                    reader = { ConfigValue.Arr(entry().team.stages.flatMap { stage -> stage.tasks.map { task ->
                        ConfigValue.Obj(mapOf("stageId" to ConfigValue.Str(stage.id), "stage" to ConfigValue.Str(stage.title),
                            "taskId" to ConfigValue.Str(task.id), "member" to ConfigValue.Str(task.agent.orEmpty())))
                    } }) }, writer = { throw ConfigError.PermissionDenied("Edit members and stages in the team editor") }),
                ClosureField(path = "$basePath.$forId.routes", displayName = "Conditional workflow routes",
                    description = "Array of fromStage/fromTask/toStage/toTask/action; EXECUTE, REVIEW or REJECT. Applied next run.",
                    valueSchema = ConfigSchema.Json,
                    reader = { ConfigValue.Arr(entry().team.routes.map { route -> ConfigValue.Obj(mapOf(
                        "fromStage" to ConfigValue.Str(route.from.stageId), "fromTask" to ConfigValue.Str(route.from.taskId),
                        "toStage" to ConfigValue.Str(route.to.stageId), "toTask" to ConfigValue.Str(route.to.taskId),
                        "action" to ConfigValue.Str(route.action.name))) }) }, writer = { value ->
                        val array = (value as? ConfigValue.Arr)?.value ?: throw ConfigError.TypeMismatch("array")
                        val routes = array.map { item ->
                            val fields = (item as? ConfigValue.Obj)?.value ?: throw ConfigError.TypeMismatch("object")
                            fun str(key: String) = (fields[key] as? ConfigValue.Str)?.value?.takeIf { it.isNotBlank() }
                                ?: throw ConfigError.InvalidValue("Missing $key")
                            val action = TeamRouteAction.entries.firstOrNull { it.name == str("action") }
                                ?: throw ConfigError.InvalidValue("Invalid route action")
                            TeamRoute(TeamFlowNode(str("fromStage"), str("fromTask")), TeamFlowNode(str("toStage"), str("toTask")), action)
                        }
                        val current = entry()
                        val form = current.team.toForm(current.scope)
                        teams.save(form.copy(routes = routes, stages = form.stages.map { it.copy(prompt = "") }), current.team.name)?.let {
                            throw ConfigError.InvalidValue("Invalid team workflow: $it")
                        }
                    })
            )
        }
        override fun add(payload: ConfigValue): String = throw ConfigError.PermissionDenied("Use the team editor")
        override fun remove(id: String) { throw ConfigError.PermissionDenied("Use the team editor") }
    }

    /** Uses the same scope-qualified files as the settings editor, never a second settings store. */
    private inner class Workflows : ConfigCollection {
        override val basePath = "workflows"
        override val displayName = "Multi-team workflows"
        override val description = "Ordered team chains. Ids are scope:UTF8-hex-name; changes apply to the next run."

        private fun id(entry: TeamWorkflowEntry): String =
            "${entry.scope.name.lowercase()}:" + entry.workflow.name.toByteArray(Charsets.UTF_8)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        private fun all() = workflows.listAll().associateBy(::id)
        private fun entry(id: String) = all()[id] ?: throw ConfigError.InvalidValue("Unknown workflow")
        override fun childIds() = all().keys.toList()

        private fun save(form: TeamWorkflowForm, originalName: String? = null) {
            workflows.save(form, originalName)?.let { throw ConfigError.InvalidValue("Failed to save workflow: $it") }
        }

        private fun text(value: ConfigValue): String =
            (value as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")

        private fun refs(value: ConfigValue): List<WorkflowTeamRefForm> =
            ((value as? ConfigValue.Arr)?.value ?: throw ConfigError.TypeMismatch("array")).map { item ->
                val obj = (item as? ConfigValue.Obj)?.value ?: throw ConfigError.TypeMismatch("object")
                if (obj.keys.any { it !in setOf("team", "acceptsReject", "lead") }) throw ConfigError.InvalidValue("Unknown team reference field")
                WorkflowTeamRefForm(
                    team = text(obj["team"] ?: throw ConfigError.InvalidValue("Missing team")),
                    acceptsReject = obj["acceptsReject"]?.let {
                        (it as? ConfigValue.Bool)?.value ?: throw ConfigError.TypeMismatch("boolean")
                    } ?: true,
                    lead = obj["lead"]?.let(::text).orEmpty()
                )
            }

        private fun rounds(value: ConfigValue): String {
            val n = (value as? ConfigValue.Int)?.value ?: throw ConfigError.TypeMismatch("integer")
            if (n !in 1..9) throw ConfigError.InvalidValue("Expected 1..9")
            return n.toString()
        }

        override fun fields(forId: String): List<ConfigField> {
            if (forId !in all()) return emptyList()
            fun form() = entry(forId).let { it.workflow.toForm(it.scope) }
            fun field(key: String, schema: ConfigSchema, read: (TeamWorkflowForm) -> ConfigValue,
                change: (TeamWorkflowForm, ConfigValue) -> TeamWorkflowForm) = ClosureField(
                path = "$basePath.$forId.$key", displayName = key, description = "Workflow $key; applied next run.",
                valueSchema = schema, risk = ConfigRisk.SENSITIVE, revertable = key != "name",
                reader = { read(form()) }, writer = { value ->
                    val current = form()
                    save(change(current, value), current.name)
                })
            return listOf(
                field("name", ConfigSchema.Str(), { ConfigValue.Str(it.name) }, { f, v -> f.copy(name = text(v)) }),
                field("description", ConfigSchema.Str(), { ConfigValue.Str(it.description) }, { f, v -> f.copy(description = text(v)) }),
                ReadOnlyField(path = "$basePath.$forId.scope", displayName = "scope", description = "Immutable file scope.",
                    valueSchema = ConfigSchema.Str(), reader = { ConfigValue.Str(form().scope.name.lowercase()) }),
                field("charter", ConfigSchema.Str(), { ConfigValue.Str(it.charter) }, { f, v -> f.copy(charter = text(v)) }),
                field("maxRejectRounds", ConfigSchema.Int(1, 9), { ConfigValue.Int(it.maxRejectRounds.toInt()) },
                    { f, v -> f.copy(maxRejectRounds = rounds(v)) }),
                field("teams", ConfigSchema.Json, { f -> ConfigValue.Arr(f.teams.map { ref -> ConfigValue.Obj(mapOf(
                    "team" to ConfigValue.Str(ref.team), "acceptsReject" to ConfigValue.Bool(ref.acceptsReject), "lead" to ConfigValue.Str(ref.lead)
                )) }) }, { f, v -> f.copy(teams = refs(v)) })
            )
        }

        override fun add(payload: ConfigValue): String {
            val obj = (payload as? ConfigValue.Obj)?.value ?: throw ConfigError.TypeMismatch("object")
            if (obj.keys.any { it !in setOf("name", "description", "scope", "charter", "maxRejectRounds", "teams") }) {
                throw ConfigError.InvalidValue("Unknown workflow field")
            }
            val scopeText = text(obj["scope"] ?: throw ConfigError.InvalidValue("Missing scope: global or project"))
            val scope = AgentDefinitionScope.entries.firstOrNull { it.name.lowercase() == scopeText }
                ?: throw ConfigError.InvalidValue("Expected global or project")
            val form = TeamWorkflowForm(
                name = text(obj["name"] ?: throw ConfigError.InvalidValue("Missing name")), scope = scope,
                description = obj["description"]?.let(::text).orEmpty(), charter = obj["charter"]?.let(::text).orEmpty(),
                maxRejectRounds = obj["maxRejectRounds"]?.let(::rounds) ?: "2",
                teams = refs(obj["teams"] ?: throw ConfigError.InvalidValue("Missing teams"))
            )
            save(form)
            return "${scope.name.lowercase()}:" + form.name.trim().toByteArray(Charsets.UTF_8)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        override fun remove(id: String) {
            val current = entry(id)
            if (!workflows.delete(current.workflow.name, current.scope)) throw ConfigError.InvalidValue("Failed to delete workflow")
        }
    }

    private inner class Stages : ConfigCollection {
        override val basePath = "team.stages"
        override val displayName = "Team communication"
        override val description = "Stage ids are scope:UTF8-hex-team-name:position. Changes apply to the next run."
        override val addable = false
        override val removable = false
        override fun childIds(): List<String> = entries().keys.toList()
        override fun fields(forId: String): List<ConfigField> {
            if (forId !in entries()) return emptyList()
            return listOf(
                ClosureField(path = "$basePath.$forId.communication", displayName = "Communication scope",
                    description = "same_stage, parent_only or run; enforced during message routing.", valueSchema = ConfigSchema.Str(),
                    reader = { ConfigValue.Str(stage(forId).communication.token) }, writer = { value ->
                        val policy = TeamCommunication.fromToken((value as? ConfigValue.Str)?.value)
                            ?: throw ConfigError.InvalidValue("Expected same_stage, parent_only or run")
                        update(forId) { it.copy(communication = policy) }
                    }),
                ClosureField(path = "$basePath.$forId.message_limit", displayName = "Message budget",
                    description = "Per member per run, shared across rework rounds.", valueSchema = ConfigSchema.Int(1, 100),
                    reader = { ConfigValue.Int(stage(forId).messageLimit) }, writer = { value ->
                        val limit = (value as? ConfigValue.Int)?.value ?: throw ConfigError.TypeMismatch("integer")
                        if (limit !in 1..100) throw ConfigError.InvalidValue("Expected 1..100")
                        update(forId) { it.copy(messageLimit = limit) }
                    }),
                ClosureField(path = "$basePath.$forId.communication_guide", displayName = "Communication guide",
                    description = "Stage-specific collaboration instructions, without extending task or write authority.", valueSchema = ConfigSchema.Str(),
                    reader = { ConfigValue.Str(stage(forId).communicationGuide) }, writer = { value ->
                        val guide = (value as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                        update(forId) { it.copy(communicationGuide = guide) }
                    })
            )
        }
        override fun add(payload: ConfigValue): String = throw ConfigError.PermissionDenied("Use the team editor")
        override fun remove(id: String) { throw ConfigError.PermissionDenied("Use the team editor") }
    }
}
