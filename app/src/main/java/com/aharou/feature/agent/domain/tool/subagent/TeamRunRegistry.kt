package com.aharou.feature.agent.domain.tool.subagent

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.*

/** Process-local run membership; no database schema or write-lease authority is changed. */
@Singleton
class TeamRunRegistry @Inject constructor() {
    private data class Member(val id: String, val title: String, var session: String? = null,
        var state: String = "pending", val messages: MutableList<String> = mutableListOf())
    private data class Run(val parent: String, val members: LinkedHashMap<String, Member>)
    data class Delivery(val state: String, val sessionId: String? = null)
    private val runs = mutableMapOf<String, Run>()
    private val sessions = mutableMapOf<String, Pair<String, String>>()
    private val stopped = mutableSetOf<String>()
    private val standalone = mutableMapOf<String, String>()

    @Synchronized fun begin(parent: String, pipeline: JsonArray): String {
        val id = UUID.randomUUID().toString()
        val members = linkedMapOf<String, Member>()
        pipeline.forEach { raw ->
            val task = raw.jsonObject
            val logical = task.getValue("id").jsonPrimitive.content
            members[logical] = Member(logical, task["description"]?.jsonPrimitive?.content ?: logical)
        }
        runs[id] = Run(parent, members)
        return id
    }

    @Synchronized fun bind(run: String, logical: String, session: String): String {
        val member = runs[run]?.members?.get(logical) ?: error("Unknown run task")
        member.session?.takeIf { it != session }?.let { sessions.remove(it) }
        member.session = session
        member.state = "running"
        sessions[session] = run to logical
        return member.messages.joinToString("\n\n").also { member.messages.clear() }
    }

    @Synchronized fun bindStandalone(parent: String, session: String, title: String, active: Set<String>) {
        val previous = standalone[parent]
        val run = previous?.takeIf { id -> runs[id]?.members?.values?.any { it.session in active } == true }
            ?: begin(parent, JsonArray(emptyList())).also { standalone[parent] = it }
        runs.getValue(run).members[session] = Member(session, title)
        bind(run, session, session)
    }

    @Synchronized fun reset(run: String, logicalIds: Set<String>) {
        logicalIds.forEach { id -> runs[run]?.members?.get(id)?.let { member ->
            member.session?.let { sessions.remove(it) }
            member.session = null
            member.state = "pending"
        } }
    }

    @Synchronized fun terminal(session: String, state: String) {
        if (state == "stopped" || state == "deleted") stopped += session
        val (run, logical) = sessions[session] ?: return
        runs[run]?.members?.get(logical)?.state = state
    }

    @Synchronized fun canMessage(session: String): Boolean = session !in stopped
    @Synchronized fun runOf(session: String): String? = sessions[session]?.first
    @Synchronized fun belongs(run: String, parent: String, logical: String): Boolean =
        runs[run]?.let { it.parent == parent && logical in it.members } == true

    @Synchronized fun route(from: String, target: String, message: String): Delivery {
        val (run, self) = sessions[from] ?: return Delivery("blocked")
        val members = runs[run]?.members ?: return Delivery("blocked")
        val member = members[target] ?: sessions[target]?.takeIf { it.first == run }?.second?.let { members[it] }
            ?: return Delivery("blocked")
        if (member.id == self || !canMessage(from) || member.state in setOf("stopped", "deleted")) return Delivery("blocked")
        val session = member.session
        if (session == null) {
            member.messages += message
            return Delivery("queued")
        }
        if (!canMessage(session)) return Delivery("blocked")
        return Delivery("delivered", session)
    }

    @Synchronized fun roster(session: String): List<JsonObject> {
        val run = sessions[session]?.first ?: return emptyList()
        return runs[run]?.members?.values.orEmpty().map { member -> buildJsonObject {
            put("id", member.id)
            put("runId", run)
            put("title", member.title)
            put("state", member.state)
            member.session?.let { put("sessionId", it) }
        } }
    }

    @Synchronized fun briefing(session: String): String = buildString {
        val run = sessions[session]?.first ?: return@buildString
        append("## 团队运行批次：").append(run).append("\n任务身份与全流程（pending 也可 tell 留言）：\n")
        roster(session).forEach { append(it).append('\n') }
        append("task(action=team) 只列本批次；tell 的 id 用逻辑任务 id 或本批次 sessionId。\n")
    }
}
