package com.aharou.feature.agent.domain.tool.session

import com.aharou.feature.agent.domain.task.ProjectTask
import kotlinx.serialization.json.*

/** Pure projections: no message reads, no model calls, filter workspace before pagination. */
object ProjectTaskQueries {
    fun pageValid(version: Long, offset: Int, pageVersion: Long?) = offset == 0 || pageVersion == version
    val readableFields = setOf("projectId", "title", "requirement", "background", "constraints", "acceptance", "brief", "evidenceRefs", "members", "owner", "dependencyIds", "sessionIds")
    fun field(tasks: List<ProjectTask>, workspace: String, taskId: String, field: String,
        offset: Int, limit: Int, taskVersion: Long): JsonObject {
        val task = tasks.firstOrNull { it.workspace == workspace && it.id == taskId } ?: error("TASK_NOT_FOUND_IN_WORKSPACE")
        require(task.version == taskVersion) { "STALE_TASK_VERSION" }
        require(field in readableFields) { "INVALID_TASK_FIELD" }
        val source = when (field) {
            "constraints" -> JsonArray(task.constraints.map(::JsonPrimitive)).toString()
            "evidenceRefs" -> JsonArray(task.evidenceRefs.map(::JsonPrimitive)).toString()
            "members" -> JsonArray(task.members.map(::JsonPrimitive)).toString()
            "projectId" -> (task.projectId?.let(::JsonPrimitive) ?: JsonNull).toString()
            "dependencyIds" -> JsonArray(task.dependencyIds.map(::JsonPrimitive)).toString()
            "sessionIds" -> JsonArray(task.sessionIds.map(::JsonPrimitive)).toString()
            "title" -> task.title; "requirement" -> task.requirement; "background" -> task.background
            "acceptance" -> task.acceptance; "brief" -> task.brief; "owner" -> task.owner
            else -> error("INVALID_TASK_FIELD")
        }
        require(offset >= 0 && offset <= source.length) { "INVALID_FIELD_OFFSET" }
        val end = (offset.toLong() + limit.coerceIn(1, 4000)).coerceAtMost(source.length.toLong()).toInt()
        return buildJsonObject {
            put("task_id", task.id); put("field", field); put("version", task.version); put("total", source.length)
            put("encoding", if (field == "projectId") "json-value-string" else if (field in setOf("constraints", "evidenceRefs", "members", "dependencyIds", "sessionIds")) "json-array-string" else "text")
            put("content", source.substring(offset, end)); put("nextOffset", if (end < source.length) JsonPrimitive(end) else JsonNull)
        }
    }
    fun fieldCall(task: ProjectTask, field: String) = buildJsonObject {
        put("action", "field"); put("task_id", task.id); put("field", field); put("offset", 0); put("limit", 1000); put("task_version", task.version)
    }
    fun query(tasks: List<ProjectTask>, workspace: String, action: String, taskIds: Set<String>,
        sinceVersion: Long, offset: Int, limit: Int): JsonObject {
        require(action == "index" || action == "brief")
        val visible = tasks.filter { it.workspace == workspace && it.version > sinceVersion &&
            (if (action == "brief") it.id in taskIds else taskIds.isEmpty() || it.id in taskIds) }
            .sortedWith(compareBy<ProjectTask> { it.order }.thenBy { it.id })
        val start = offset.coerceAtLeast(0)
        val page = visible.drop(start).take(limit.coerceIn(1, if (action == "brief") 10 else 200))
        return buildJsonObject {
            put("workspace", workspace)
            put("count", page.size)
            put("nextOffset", if (start + page.size < visible.size) JsonPrimitive(start + page.size) else JsonNull)
            put("tasks", buildJsonArray { page.forEach { task -> add(buildJsonObject {
                put("id", task.id); put("title", task.title.take(160)); put("projectId", task.projectId?.let(::JsonPrimitive) ?: JsonNull)
                    put("parentId", task.parentId?.let(::JsonPrimitive) ?: JsonNull)
                put("status", task.status.name); put("version", task.version); put("owner", task.owner.take(80))
                put("dependencyIds", JsonArray(task.dependencyIds.take(12).map(::JsonPrimitive)))
                put("dependenciesTruncated", task.dependencyIds.size > 12)
                put("dependencyRead", fieldCall(task, "dependencyIds"))
                put("executionReason", task.executionReason.take(160))
                put("sessionCount", task.sessionIds.size)
                put("lastSessionId", task.sessionIds.lastOrNull()?.let(::JsonPrimitive) ?: JsonNull)
                if (action == "brief") {
                    put("sessionIds", buildJsonArray { task.sessionIds.take(12).forEach { add(JsonPrimitive(it)) } })
                    val omitted = mutableListOf<String>()
                    if (task.sessionIds.size > 12) omitted += "sessionIds"
                    if (task.dependencyIds.size > 12) omitted += "dependencyIds"
                    fun text(key: String, value: String, budget: Int) {
                        put(key, value.take(budget)); if (value.length > budget) omitted += key
                    }
                    text("requirement", task.requirement, 1600); text("background", task.background, 800)
                    text("acceptance", task.acceptance, 800); text("brief", task.brief, 1600)
                    put("requirementsVersion", task.requirementsVersion)
                    fun refs(key: String, values: List<String>) {
                        put(key, buildJsonArray { values.take(12).forEach { add(JsonPrimitive(it.take(200))) } })
                        if (values.size > 12 || values.any { it.length > 200 }) omitted += key
                    }
                    refs("constraints", task.constraints); refs("evidenceRefs", task.evidenceRefs)
                    put("truncatedFields", buildJsonArray { omitted.forEach { add(JsonPrimitive(it)) } })
                    put("fieldRead", buildJsonObject { readableFields.forEach { put(it, fieldCall(task, it)) } })
                    put("originalMessageLocator", task.sessionIds.lastOrNull()?.let { "sessions:read:$it" }?.let(::JsonPrimitive) ?: JsonNull)
                }
            }) } })
        }
    }
}
