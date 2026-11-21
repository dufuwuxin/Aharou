package com.aharou.feature.agent.presentation.component

import com.aharou.feature.agent.data.local.entity.ToolCallReceiptEntity
import com.aharou.feature.agent.domain.task.ProjectPlan
import com.aharou.feature.agent.domain.task.ProjectTask
import com.aharou.feature.agent.domain.task.ProjectTaskStatus
import com.aharou.feature.agent.domain.task.ProjectTaskGraph
import com.aharou.feature.agent.domain.task.ProjectWorkflowPlan
import kotlinx.serialization.json.*

/** Read-only projections. No conversation content, execution commands or guessed identities. */
internal object ProjectMindMapModels {
    fun tasks(project: ProjectPlan, tasks: List<ProjectTask>): List<ProjectTask> = ProjectTaskGraph.tree(
        tasks.filter { it.projectId == project.id && it.workspace == project.workspace }
    ).map { it.task }

    fun roles(tasks: List<ProjectTask>): List<String> = tasks.flatMap {
        listOf(it.owner) + it.members + it.processMembers.values.map { run -> run.role }
    }.filter { it.isNotBlank() }.distinct().sorted()

    fun related(task: ProjectTask, role: String): Boolean = task.owner == role ||
        role in task.members || task.processMembers.values.any { it.role == role }

    fun sessions(task: ProjectTask): List<String> = (task.sessionIds + task.processSessionIds)
        .filter { it.isNotBlank() }.distinct().sorted()

    fun accepted(task: ProjectTask): Boolean = task.status == ProjectTaskStatus.DONE &&
        ProjectWorkflowPlan.delivered(task)

    fun navigate(history: List<String>, target: String): List<String> {
        val index = history.indexOf(target)
        return if (index >= 0) history.take(index + 1) else history + target
    }

    fun ordered(receipts: List<ToolCallReceiptEntity>): List<ToolCallReceiptEntity> = receipts
        .sortedWith(compareBy<ToolCallReceiptEntity> { it.createdAt }.thenBy { it.id })

    fun outcome(receipt: ToolCallReceiptEntity): MapReceiptOutcome = when {
        receipt.category == "DENIED" -> MapReceiptOutcome.DENIED
        receipt.status == "SUCCESS" -> MapReceiptOutcome.SUCCESS
        receipt.status == "PARTIAL" -> MapReceiptOutcome.PARTIAL
        receipt.status == "ERROR" -> MapReceiptOutcome.ERROR
        else -> MapReceiptOutcome.UNKNOWN
    }

    fun kind(receipt: ToolCallReceiptEntity): MapReceiptKind {
        // Only known tools; neither arbitrary shell commands nor summaries prove file changes.
        val name = receipt.toolName.substringAfterLast('.').lowercase()
        return when (name) {
            "websearch", "webfetch" -> MapReceiptKind.WEB
            "memory_cloud_search", "read" -> if (name == "memory_cloud_search" || receipt.toolName == "memory.read")
                MapReceiptKind.MEMORY else MapReceiptKind.OTHER
            "memory" -> if (Regex("""(?:"?action"?)\s*[:=]\s*"?(read|list|search|library_read|library_list|library_search|stats)"?(?=[,}\s]|$)""")
                .containsMatchIn(receipt.argsPreview)) MapReceiptKind.MEMORY else MapReceiptKind.OTHER
            "readfile", "writefile", "editfile", "list", "search" -> MapReceiptKind.FILE
            else -> MapReceiptKind.OTHER
        }
    }

    /** Only explicitly successful editFile result hunks are evidence; args alone never prove a write. */
    fun diff(receipt: ToolCallReceiptEntity): List<String> {
        if (outcome(receipt) != MapReceiptOutcome.SUCCESS ||
            receipt.toolName.substringAfterLast('.').lowercase() != "editfile") return emptyList()
        return runCatching {
            val root = Json.parseToJsonElement(receipt.resultPreview) as? JsonObject
            val hunks = root?.get("hunks") as? JsonArray
            hunks.orEmpty().mapNotNull { (it as? JsonObject)?.get("diff") as? JsonPrimitive }
                .map { it.content }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun matches(receipt: ToolCallReceiptEntity, filter: MapReceiptFilter): Boolean = when (filter) {
        MapReceiptFilter.ALL -> true
        MapReceiptFilter.WEB -> kind(receipt) == MapReceiptKind.WEB
        MapReceiptFilter.MEMORY -> kind(receipt) == MapReceiptKind.MEMORY
        MapReceiptFilter.FILE -> kind(receipt) == MapReceiptKind.FILE
        MapReceiptFilter.OTHER -> kind(receipt) == MapReceiptKind.OTHER
        MapReceiptFilter.ERROR -> outcome(receipt) == MapReceiptOutcome.ERROR
        MapReceiptFilter.DENIED -> outcome(receipt) == MapReceiptOutcome.DENIED
    }

    fun cyclic(task: ProjectTask, tasks: List<ProjectTask>): Boolean {
        val byId = tasks.associateBy { it.id }
        val visited = mutableSetOf<String>()
        val queue = java.util.ArrayDeque(task.dependencyIds)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (id == task.id) return true
            if (visited.add(id)) byId[id]?.dependencyIds?.forEach(queue::addLast)
        }
        return false
    }
}

internal enum class MapReceiptKind { WEB, MEMORY, FILE, OTHER }
internal enum class MapReceiptOutcome { SUCCESS, PARTIAL, ERROR, DENIED, UNKNOWN }
internal enum class MapReceiptFilter { ALL, WEB, MEMORY, FILE, OTHER, ERROR, DENIED }
