package com.aharou.feature.agent.domain.task

import com.aharou.feature.agent.domain.prompt.PromptFileResolver
import com.aharou.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aharou.feature.agent.domain.workflow.AgentWorkflow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Planned tasks; [ruleFallback] means the model was unavailable or invalid and [ProjectPlanDraft] rules produced them. */
data class ProjectPlanResult(val tasks: List<ProjectTask>, val ruleFallback: Boolean)

/**
 * Starting points chosen for one user supplement. Edits and reruns still go through the repository save path, so
 * requirementsVersion and dependency invalidation stay the only way finished work is redone.
 */
data class ProjectRevision(
    val edits: Map<String, String> = emptyMap(),
    val reruns: Set<String> = emptySet(),
    val added: List<ProjectTask> = emptyList(),
    val ruleFallback: Boolean = false
) {
    /** Existing tasks this revision edits or reruns, in a stable order. */
    val touched: List<String> get() = (edits.keys + reruns).distinct()

    /** The change for [latest], read at commit time so a concurrent edit is never saved over with an old version. */
    fun change(latest: ProjectTask): ProjectTaskChange {
        check(!latest.isRequirementBranch) { "BRANCH_REVISION_REQUIRES_APPEND" }
        return ProjectTaskChange(latest.copy(requirement = edits[latest.id] ?: latest.requirement), force = latest.id in reruns)
    }
}

/**
 * Model-backed planning for project tasks. Only one-shot completions are used (no tools, no code access), so the
 * model picks structure and starting points while [ProjectTaskGraph] stays the authority on what is valid.
 */
@Singleton
class ProjectPlanner @Inject constructor(
    private val promptFileResolver: PromptFileResolver,
    private val agentWorkflow: AgentWorkflow,
    private val definitions: AgentDefinitionRepository
) {
    suspend fun members(): List<String> = try {
        withContext(Dispatchers.IO) { definitions.listEnabled().map { it.definition.name } }
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }

    /**
     * [requirements] become new tasks of [project]; they may hang under or depend on [existing] tasks of that project.
     * A [parentId] forces every new top-level task under that task; the planner then only picks dependencies and members.
     */
    suspend fun plan(project: ProjectPlan, requirements: String, existing: List<ProjectTask>, members: List<String>,
        parentId: String? = null): ProjectPlanResult {
        val draft = project.copy(requirements = requirements)
        val projectTasks = existing.filter { it.projectId == project.id && it.workspace == project.workspace }
        val scope = projectTasks
        require(parentId == null || scope.any { it.id == parentId }) { "PARENT_NOT_FOUND" }
        val parent = parentId?.let { id -> scope.single { it.id == id } }
        val branches = parent?.let { ProjectWorkflowPlan.requirementBranches(it, scope) }.orEmpty()
        val visible = branches.lastOrNull()?.let { ProjectTaskGraph.subtree(it.id, scope) } ?: scope
        val input = buildString {
            append("Project: ").append(project.title)
            append("\nProject requirements:\n").append(project.requirements.take(REQUIREMENT_MAX))
            for (branch in branches) append("\nRequirement branch [").append(branch.id).append("]:\n")
                .append(branch.requirement.take(REQUIREMENT_MAX))
            append("\nProject purpose (background only, not executable requirements): ").append(project.purpose.take(REQUIREMENT_MAX))
            append("\nEnabled members: ").append(members.joinToString(", ").ifBlank { "(none)" })
            parentId?.let { append("\nFixed parent task id: ").append(it).append(" (every new top-level task hangs under it)") }
            append("\nExisting tasks:")
            if (visible.isEmpty()) append(" (none)")
            for (task in ProjectTaskGraph.tree(visible).take(MAX_CONTEXT_TASKS)) {
                append("\n- id=").append(task.task.id).append(" parent=").append(task.task.parentId ?: "-")
                    .append(" status=").append(if (task.task.isRequirementBranch) "REQUIREMENT_BRANCH" else task.task.status.name)
                    .append(" title=").append(task.task.title)
            }
            append("\n\nRequirements:\n").append(requirements)
        }
        val planned = completeJson(PLAN_PROMPT_FILE, input)?.let { mapPlan(it, draft, scope, members, parentId) }
        return if (planned != null) ProjectPlanResult(planned, ruleFallback = false)
            else ProjectPlanResult(attach(ProjectPlanDraft.build(draft), parentId, scope), ruleFallback = true)
    }

    /** Rule drafts under a fixed parent: their roots move under it and anything past the depth limit is dropped. */
    private fun attach(drafts: List<ProjectTask>, parentId: String?, scope: List<ProjectTask>): List<ProjectTask> {
        if (parentId == null) return drafts
        val attached = drafts.map { if (it.parentId == null) it.copy(parentId = parentId) else it }
        return attached.filter { ProjectTaskGraph.depth(it, scope + attached) <= ProjectTaskGraph.MAX_TREE_DEPTH }
    }

    /**
     * Without a usable model answer [anchor] is rerun as is: the supplement already sits in its supplements and reaches the
     * member prompt from there, so copying it into the requirement would show the same words twice.
     */
    suspend fun revise(project: ProjectPlan, anchor: ProjectTask, projectTasks: List<ProjectTask>, text: String, members: List<String>): ProjectRevision {
        check(!anchor.isRequirementBranch) { "BRANCH_REVISION_REQUIRES_APPEND" }
        val branch = ProjectTaskGraph.subtree(anchor.id, projectTasks).take(MAX_CONTEXT_TASKS)
        val anchorContainers = ProjectWorkflowPlan.requirementBranches(anchor, projectTasks).map { it.id }
        val input = buildString {
            append("Project: ").append(project.title)
            append("\nProject purpose (background only, not executable requirements): ").append(project.purpose.take(REQUIREMENT_MAX))
            append("\nEnabled members: ").append(members.joinToString(", ").ifBlank { "(none)" })
            append("\nProject requirements:\n").append(project.requirements.take(REQUIREMENT_MAX))
            for (container in ProjectWorkflowPlan.requirementBranches(anchor, projectTasks))
                append("\nRequirement branch [").append(container.id).append("]:\n").append(container.requirement.take(REQUIREMENT_MAX))
            append("\nAnchor task id: ").append(anchor.id)
            append("\nBranch tasks (anchor first):")
            for (task in branch) {
                append("\n- id=").append(task.id).append(" parent=").append(task.parentId ?: "-")
                    .append(" status=").append(if (task.isRequirementBranch) "REQUIREMENT_BRANCH" else task.status.name)
                    .append("\n  title: ").append(task.title)
                if (!task.isRequirementBranch &&
                    ProjectWorkflowPlan.requirementBranches(task, projectTasks).map { it.id } == anchorContainers)
                    append("\n  requirement: ").append(task.requirement.take(CONTEXT_TEXT_MAX))
                    .append("\n  summary: ").append(task.brief.take(CONTEXT_TEXT_MAX).ifBlank { "(none)" })
            }
            append("\n\nUser supplement:\n").append(text)
        }
        return completeJson(REVISE_PROMPT_FILE, input)?.let { mapRevision(it, project, anchor, branch, projectTasks, members) }
            ?: ProjectRevision(reruns = setOf(anchor.id), ruleFallback = true)
    }

    /** Null means the answer is unusable and the caller falls back to rules. */
    internal fun mapPlan(json: JsonObject, project: ProjectPlan, scope: List<ProjectTask>, members: List<String>,
        parentId: String? = null): List<ProjectTask>? {
        val items = (json["tasks"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
        val allowedParents = parentId?.let { setOf(it) } ?: scope.map { it.id }.toSet()
        val tasks = mapTasks(items, project, scope, members, parentId, allowedParents, ProjectTaskStatus.DRAFT)
        return tasks.takeIf { it.isNotEmpty() && ProjectTaskGraph.validate(scope + it) == null }
    }

    /** Edits and reruns only reach the anchor's own branch; added tasks hang inside that branch (anchor by default). */
    internal fun mapRevision(json: JsonObject, project: ProjectPlan, anchor: ProjectTask, branch: List<ProjectTask>,
        projectTasks: List<ProjectTask>, members: List<String>): ProjectRevision? {
        val operations = (json["operations"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
        val branchIds = branch.map { it.id }.toSet()
        val edits = linkedMapOf<String, String>()
        val reruns = linkedSetOf<String>()
        val adds = mutableListOf<JsonObject>()
        for (operation in operations) {
            val id = operation.text("taskId")
            if (operation.text("op") in setOf("edit", "rerun"))
                check(branch.none { it.id == id && it.isRequirementBranch }) { "BRANCH_REVISION_REQUIRES_APPEND" }
            when (operation.text("op")) {
                "edit" -> operation.text("requirement").takeIf { id in branchIds && it.isNotBlank() }?.let { edits[id] = it.take(REQUIREMENT_MAX) }
                "rerun" -> if (id in branchIds) reruns += id
                "add" -> adds += operation
            }
        }
        val scope = projectTasks.filter { it.projectId == anchor.projectId && it.workspace == anchor.workspace }
        val added = mapTasks(adds, project, scope, members, anchor.id, branchIds, ProjectTaskStatus.READY)
            .takeIf { ProjectTaskGraph.validate(scope + it) == null }.orEmpty()
        if (edits.isEmpty() && reruns.isEmpty() && added.isEmpty()) return null
        return ProjectRevision(edits = edits, reruns = reruns, added = added)
    }

    private fun mapTasks(items: List<JsonObject>, project: ProjectPlan, scope: List<ProjectTask>, members: List<String>,
        defaultParent: String?, allowedParents: Set<String>, status: ProjectTaskStatus): List<ProjectTask> {
        val entries = items.mapIndexedNotNull { index, item ->
            if (item.text("title").isBlank()) null else item.text("key").ifBlank { "#$index" } to item
        }.distinctBy { it.first }.take(MAX_TASKS)
        val ids = entries.associate { it.first to java.util.UUID.randomUUID().toString() }
        val keyById = ids.entries.associate { (key, id) -> id to key }
        val existingIds = scope.map { it.id }.toSet()
        val executableIds = scope.filterNot { it.isRequirementBranch }.map { it.id }.toSet()
        fun resolve(reference: String): String? = ids[reference] ?: reference.takeIf { it in existingIds }
        val parents = entries.associate { (key, item) ->
            val parent = item.text("parentKey").takeIf { it.isNotBlank() }?.let(::resolve)?.takeIf { it != ids[key] }
            key to (parent?.takeIf { it in keyById || it in allowedParents } ?: defaultParent)
        }
        fun depth(key: String): Int {
            var levels = 0
            var parent = parents[key]
            while (true) {
                val current = parent ?: return levels
                val parentKey = keyById[current]
                    ?: return levels + 1 + (scope.firstOrNull { it.id == current }?.let { ProjectTaskGraph.depth(it, scope) } ?: 0)
                if (++levels > entries.size) return Int.MAX_VALUE // A parent cycle among new keys never terminates.
                parent = parents[parentKey]
            }
        }
        // Deeper than the limit (or cyclic) is dropped; a dropped parent always drops its children, which are deeper still.
        val kept = entries.filter { depth(it.first) <= ProjectTaskGraph.MAX_TREE_DEPTH }
        val keptIds = kept.map { ids.getValue(it.first) }.toSet()
        val enabled = members.toSet()
        return kept.mapIndexed { index, (key, item) ->
            val id = ids.getValue(key)
            var existingParent = parents[key]
            val parentSeen = hashSetOf<String>()
            while (existingParent != null && existingParent in keyById && parentSeen.add(existingParent))
                existingParent = parents[keyById.getValue(existingParent)]
            val container = scope.firstOrNull { it.id == existingParent }?.let {
                ProjectWorkflowPlan.requirementBranches(it, scope).lastOrNull()
            }
            val dependencyIds = container?.let { branch ->
                ProjectTaskGraph.subtree(branch.id, scope).filterNot { it.isRequirementBranch }.map { it.id }.toSet()
            } ?: executableIds
            val dependencies = item.names("dependsOn").mapNotNull(::resolve)
                .filter { (it in keptIds || it in dependencyIds) && it != id }.distinct()
            ProjectTask(id = id, workspace = project.workspace, projectId = project.id, title = item.text("title").take(TITLE_MAX),
                requirement = item.text("requirement").ifBlank { item.text("title") }.take(REQUIREMENT_MAX),
                parentId = parents[key], dependencyIds = dependencies, acceptance = item.text("acceptance").take(REQUIREMENT_MAX),
                owner = item.text("owner").takeIf { it in enabled }.orEmpty(), members = item.names("members").filter { it in enabled },
                status = status, order = project.createdAt + index)
        }
    }

    private suspend fun completeJson(file: String, input: String): JsonObject? {
        val prompt = try { promptFileResolver.resolve(file).replace(LEADING_COMMENT, "").trim() }
            catch (_: Exception) { "" }
        if (prompt.isEmpty()) return null
        val raw = try { withTimeoutOrNull(MODEL_TIMEOUT_MS) { agentWorkflow.completeOnce(prompt, input) } }
            catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        if (raw == null) return null
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try { Json.parseToJsonElement(raw.substring(start, end + 1)) as? JsonObject } catch (_: Exception) { null }
    }

    private fun JsonObject.text(name: String): String = (this[name] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    private fun JsonObject.names(name: String): List<String> = (this[name] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }.filter { it.isNotBlank() }

    companion object {
        /** One planning answer adds at most this many tasks; longer answers are cut in their own order. */
        const val MAX_TASKS = 30
        const val PLAN_PROMPT_FILE = "agent/project-plan.md"
        const val REVISE_PROMPT_FILE = "agent/project-revise.md"
        private const val MODEL_TIMEOUT_MS = 180_000L
        private const val MAX_CONTEXT_TASKS = 80
        private const val CONTEXT_TEXT_MAX = 600
        private const val TITLE_MAX = 120
        private const val REQUIREMENT_MAX = 4_000
        private val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")
    }
}
