package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.feature.agent.domain.subagent.AgentDefinition
import com.aharou.feature.agent.domain.subagent.AgentTeam
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.subagent.SubAgentEvent
import com.aharou.feature.agent.domain.subagent.SubAgentEventBus
import com.aharou.feature.agent.domain.subagent.SubAgentEventType
import com.aharou.feature.agent.domain.subagent.WriteLease
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Executes stages through the existing pipeline callback, never through a second agent executor. */
class TeamStageExecutor @Inject constructor(private val registry: TeamRunRegistry) {
    enum class Review { PASS, REJECT, INVALID }

    suspend fun run(team: AgentTeam, goal: String, enabled: List<AgentDefinition>, maxRunning: Int,
        parent: String, exec: suspend (JsonArray) -> ToolResult): ToolResult {
        if (parent.isBlank()) return ToolResult.Error("缺少会话上下文", "NO_SESSION")
        val plan = TeamRunner.build(team, goal, enabled, maxRunning)
        if (plan is TeamRunner.Plan.Rejected) return ToolResult.Error(plan.message, plan.code)
        plan as TeamRunner.Plan.Ready
        if (team.stages.isEmpty() || team.stages.any { it.tasks.isEmpty() }) {
            return ToolResult.Error("团队阶段不能为空", "INVALID_TEAM")
        }
        team.stages.forEachIndexed { index, stage ->
            if (stage.rejectTo != null && team.stages.take(index).count { it.title == stage.rejectTo } != 1) {
                return ToolResult.Error("驳回目标必须是唯一的早先阶段：${stage.title}", "INVALID_REJECT_TARGET")
            }
        }
        val run = registry.begin(parent, plan.pipeline)
        val outputs = linkedMapOf<String, String>()
        val history = mutableListOf<JsonObject>()
        var stageIndex = 0
        var rounds = 0
        var feedback = ""
        fun finish(state: String, reason: String) = ToolResult.Success(buildJsonObject {
            put("state", state); put("runId", run); put("reason", reason); put("reworkRounds", rounds)
            put("outputs", buildJsonObject { outputs.forEach { (id, text) -> put(id, text) } })
            put("history", JsonArray(history))
        })
        while (stageIndex < team.stages.size) {
            val stage = team.stages[stageIndex]
            val members = plan.pipeline.map { it.jsonObject }.filter { it.getValue("id").jsonPrimitive.content.startsWith("s${stageIndex + 1}-") }
            val ids = members.map { it.getValue("id").jsonPrimitive.content }.toSet()
            val handoff = buildString {
                append("## 完整上游交接包（原文证据，不作 AI 摘要）\n")
                outputs.forEach { (id, text) -> append("### ").append(id).append('\n').append(text).append("\n\n") }
                if (feedback.isNotBlank()) append("## 审核返工理由与原始证据\n").append(feedback).append('\n')
            }
            val pipeline = JsonArray(members.map { task -> JsonObject(task.toMutableMap().apply {
                val deps = (task["dependsOn"] as? JsonArray).orEmpty().filter { it.jsonPrimitive.content in ids }
                put("dependsOn", JsonArray(deps))
                put("prompt", JsonPrimitive(handoff + "\n" + task.getValue("prompt").jsonPrimitive.content))
                put("_teamRunId", JsonPrimitive(run))
            }) })
            val result = exec(pipeline)
            val data = (result as? ToolResult.Success)?.data as? JsonObject
            val produced = data?.get("outputs") as? JsonObject
            history += buildJsonObject {
                put("stage", stage.title); put("round", rounds)
                if (data != null) put("result", data)
                if (result is ToolResult.Error) { put("error", result.code); put("message", result.message) }
            }
            produced?.forEach { (id, value) -> outputs[id] = value.jsonPrimitive.content }
            if (data?.get("state")?.jsonPrimitive?.content != "done" || ids.any { outputs[it].isNullOrBlank() }) {
                return finish("blocked", "阶段「${stage.title}」存在失败、超时、未接受或缺失交付")
            }
            if (stage.isReview) {
                val decisions = ids.map { review(outputs.getValue(it)) }
                if (Review.INVALID in decisions) return finish("blocked", "审核结论缺失或不合法：${stage.title}")
                if (Review.REJECT in decisions) {
                    val target = team.stages.indexOfFirst { it.title == stage.rejectTo }
                    if (target !in 0 until stageIndex || rounds >= MAX_REWORK_ROUNDS) {
                        return finish("blocked", "审核驳回且无可用回退目标或超过两轮返工：${stage.title}")
                    }
                    feedback = ids.joinToString("\n\n") { "$it\n${outputs.getValue(it)}" }
                    val affected = plan.pipeline.map { it.jsonObject.getValue("id").jsonPrimitive.content }
                        .filter { id -> (target until team.stages.size).any { id.startsWith("s${it + 1}-") } }.toSet()
                    feedback += "\n\n## 原任务交付（返工保留原始证据）\n" + affected.mapNotNull { id ->
                        outputs[id]?.let { "$id\n$it" }
                    }.joinToString("\n\n")
                    registry.reset(run, affected)
                    affected.forEach { outputs.remove(it) }
                    rounds++
                    stageIndex = target
                    continue
                }
            }
            stageIndex++
        }
        return finish("done", "所有必需执行和审核阶段均已通过")
    }

    suspend fun pipeline(args: Map<String, JsonElement>, context: AgentContext, eventBus: SubAgentEventBus,
        create: suspend (Map<String, JsonElement>) -> ToolResult, read: suspend (String) -> String): ToolResult {
        context.sessionId ?: return ToolResult.Error("缺少会话上下文", "NO_SESSION")

        val raw = args["pipeline"] as? JsonArray
            ?: return ToolResult.Error("pipeline 必须是数组（每项至少要有 id 与 prompt）", "INVALID_ARGS")

        val tasks = raw.mapNotNull { it as? JsonObject }.mapNotNull { obj ->
            val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val prompt = (obj["prompt"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (id.isEmpty() || prompt.isEmpty()) {
                null
            } else {
                PipelineTask(
                    id = id,
                    prompt = prompt,
                    description = (obj["description"] as? JsonPrimitive)?.contentOrNull?.trim()
                        ?.replace(Regex("\\s+"), " ")?.take(30)?.takeIf { it.isNotBlank() } ?: id,
                    dependsOn = stringSetOf(obj["dependsOn"]).orEmpty(),
                    writePaths = stringSetOf(obj["writePaths"]),
                    agent = (obj["agent"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() },
                    department = (obj["department"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() },
                    agentQuery = (obj["agentQuery"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() },
                    model = (obj["model"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() },
                    provider = (obj["provider"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
                )
            }
        }
        if (tasks.size != raw.size || tasks.isEmpty()) {
            return ToolResult.Error("pipeline 里没有有效任务：每项至少要给 id 与 prompt", "INVALID_ARGS")
        }
        val knownIds = tasks.map { it.id }.toSet()
        if (knownIds.size != tasks.size) {
            return ToolResult.Error("pipeline 里存在重复的 id", "INVALID_ARGS")
        }
        tasks.forEach { task ->
            val missing = task.dependsOn - knownIds
            if (missing.isNotEmpty()) {
                return ToolResult.Error("任务「${task.id}」依赖了不存在的 id：${missing.joinToString("、")}", "INVALID_ARGS")
            }
        }

        val layers = try {
            pipelineLayers(tasks)
        } catch (e: IllegalStateException) {
            return ToolResult.Error("pipeline 的依赖关系无法排序：${e.message}", "INVALID_ARGS")
        }

        val parent = context.sessionId ?: return ToolResult.Error("缺少会话上下文", "NO_SESSION")
        val declaredRun = (raw.firstOrNull() as? JsonObject)?.get("_teamRunId")?.jsonPrimitive?.contentOrNull
        val run = declaredRun ?: registry.begin(parent, raw)
        if (tasks.any { !registry.belongs(run, parent, it.id) }) return ToolResult.Error("无效团队批次", "INVALID_TEAM_RUN")
        val outputs = mutableMapOf<String, String>()
        val states = mutableMapOf<String, String>()
        val sessions = mutableMapOf<String, String>()
        val report = mutableListOf<String>()

        for ((layerIndex, layer) in layers.withIndex()) {
            overlappingPair(layer)?.let { (a, b) ->
                return ToolResult.Error(
                    WriteLease.layerConflictMessage(a.id, a.writePaths, b.id, b.writePaths, context.projectRoot),
                    "WRITE_CONFLICT"
                )
            }
            if (layer.size > eventBus.maxRunning) {
                return ToolResult.Error(
                    "第 ${layerIndex + 1} 层有 ${layer.size} 个任务，超过并发上限 ${eventBus.maxRunning}：" +
                        "拆成多层（用 dependsOn 串起来）或减少同层任务数",
                    "TOO_MANY_IN_LAYER"
                )
            }

            val spawned = mutableMapOf<String, String>()
            for (task in layer) {
                if (!dependenciesReady(task.dependsOn, states)) {
                    states[task.id] = "blocked"
                    report += "- ${task.id}：必需依赖未成功，未启动"
                    continue
                }
                val upstream = task.dependsOn.mapNotNull { dep ->
                    outputs[dep]?.takeIf { it.isNotBlank() }?.let { "## 上游任务「$dep」的产出\n$it" }
                }.joinToString("\n\n")
                val fullPrompt = if (upstream.isEmpty()) task.prompt else "$upstream\n\n---\n\n${task.prompt}"

                val createArgs = buildJsonObject {
                    put("action", "create")
                    put("prompt", fullPrompt)
                    put("description", task.description)
                    put("_teamRunId", run)
                    put("_teamLogicalId", task.id)
                    task.agent?.let { put("agent", it) }
                    task.department?.let { put("department", it) }
                    task.agentQuery?.let { put("agentQuery", it) }
                    task.model?.let { put("model", it) }
                    task.provider?.let { put("provider", it) }
                    task.writePaths?.let { paths ->
                        put("writePaths", buildJsonArray { paths.forEach { add(JsonPrimitive(it)) } })
                    }
                }
                val created = create(createArgs)
                if (created is ToolResult.Error) {
                    states[task.id] = "failed"
                    outputs[task.id] = ""
                    report += "- ${task.id}：派发失败（${created.code}）${created.message}"
                    continue
                }
                val subId = ((created as? ToolResult.Success)?.data as? JsonObject)
                    ?.get("id")?.let { (it as? JsonPrimitive)?.contentOrNull }
                if (subId.isNullOrBlank()) {
                    states[task.id] = "failed"
                    outputs[task.id] = ""
                    report += "- ${task.id}：派发后没拿到子会话 id"
                } else {
                    spawned[task.id] = subId
                    sessions[task.id] = subId
                }
            }
            if (spawned.isEmpty()) continue

            val finished = awaitSubAgents(spawned.values.toSet(), eventBus)
            for ((taskId, subId) in spawned) {
                val event = finished[subId]
                outputs[taskId] = read(subId)
                states[taskId] = outcome(event?.type?.name, event?.accepted)
                registry.terminal(subId, states.getValue(taskId))
                val verdict = states.getValue(taskId)
                report += "- $taskId：$verdict（子会话 $subId）"
            }
        }

        val summary = report.joinToString("\n")
        return ToolResult.Success(
            buildJsonObject {
                put("state", if (states.values.all { it == "succeeded" } && states.size == tasks.size) "done" else "blocked")
                put("runId", run)
                put("taskStates", buildJsonObject { states.forEach { (id, state) -> put(id, state) } })
                put("sessions", buildJsonObject { sessions.forEach { (id, session) -> put(id, session) } })
                put("layers", layers.size)
                put("report", summary)
                // 工作流要拿某层协调者的判定，必须能按任务 id 取回产出；只回 report 文本的话，
                // 上层只能去解析正则或重新 read 一遍，两条路都脆。
                put(
                    "outputs",
                    buildJsonObject {
                        outputs.forEach { (taskId, text) ->
                            put(taskId, text)
                        }
                    }
                )
                put(
                    "message",
                    "流水线已结束（状态以 state/taskStates 为准，共 ${tasks.size} 个任务、${layers.size} 层）：\n$summary\n" +
                        "各任务产出已按依赖自动传给下游；要看某个任务的完整输出用 task(action=\"read\", id=<子会话 id>)。"
                )
            }
        )
    }

    /** 等这些子代理全部跑到终态；超时则返回已收到的部分（缺失即「仍在运行」）。 */
    private suspend fun awaitSubAgents(ids: Set<String>, eventBus: SubAgentEventBus): Map<String, SubAgentEvent> {
        val done = mutableMapOf<String, SubAgentEvent>()
        withTimeoutOrNull(30L * 60L * 1000L) {
            while (done.size < ids.size) {
                ids.forEach { id -> eventBus.terminalEvent(id)?.let { done[id] = it } }
                if (done.size == ids.size || ids.none { it !in done && it in eventBus.activeSubSessionIds.value }) break
                kotlinx.coroutines.delay(100)
            }
        }
        return done
    }

    private fun SubAgentEventType.isTerminal(): Boolean =
        this == SubAgentEventType.COMPLETED || this == SubAgentEventType.FAILED || this == SubAgentEventType.STOPPED

    /** 拓扑分层：同层任务互不依赖（可并发）；依赖成环时抛 IllegalStateException。 */
    private fun pipelineLayers(tasks: List<PipelineTask>): List<List<PipelineTask>> {
        val byId = tasks.associateBy { it.id }
        val depth = mutableMapOf<String, Int>()
        val visiting = mutableSetOf<String>()
        fun depthOf(id: String): Int {
            depth[id]?.let { return it }
            if (!visiting.add(id)) throw IllegalStateException("任务「$id」形成依赖环")
            val task = byId.getValue(id)
            val value = if (task.dependsOn.isEmpty()) 0 else task.dependsOn.maxOf { depthOf(it) } + 1
            visiting.remove(id)
            depth[id] = value
            return value
        }
        return tasks.groupBy { depthOf(it.id) }.toSortedMap().values.toList()
    }

    /** 同层里第一对写范围相交的任务；没有则返回 null。 */
    private fun overlappingPair(layer: List<PipelineTask>): Pair<PipelineTask, PipelineTask>? {
        for (i in layer.indices) {
            val a = layer[i].writePaths ?: continue
            for (j in i + 1 until layer.size) {
                val b = layer[j].writePaths ?: continue
                if (WriteLease.overlaps(a, b)) return layer[i] to layer[j]
            }
        }
        return null
    }

    /** 把 JSON 数组参数读成字符串集合；非数组返回 null（表示「未声明」，与 create 的语义一致）。 */
    private fun stringSetOf(element: JsonElement?): Set<String>? {
        val array = element as? JsonArray ?: return null
        return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private data class PipelineTask(
        val id: String,
        val prompt: String,
        val description: String,
        val dependsOn: Set<String>,
        val writePaths: Set<String>?,
        val agent: String?,
        val department: String?,
        val agentQuery: String?,
        val model: String?,
        val provider: String?
    )


    companion object {
        private const val MAX_REWORK_ROUNDS = 2
        fun outcome(type: String?, accepted: Boolean?): String = when {
            type == "FAILED" -> "failed"
            type == "STOPPED" -> "stopped"
            type != "COMPLETED" -> "timeout"
            accepted != true -> "unaccepted"
            else -> "succeeded"
        }
        fun dependenciesReady(dependencies: Set<String>, states: Map<String, String>): Boolean =
            dependencies.all { states[it] == "succeeded" }

        fun review(output: String): Review {
            val lines = output.lineSequence().map { it.trim() }.filter { it.startsWith("TEAM_REVIEW:") }.toList()
            if (lines.size != 1) return Review.INVALID
            val line = lines.single()
            return when {
                line == "TEAM_REVIEW: PASS" -> Review.PASS
                line.startsWith("TEAM_REVIEW: REJECT ") && line.removePrefix("TEAM_REVIEW: REJECT ").isNotBlank() -> Review.REJECT
                else -> Review.INVALID
            }
        }
    }
}
