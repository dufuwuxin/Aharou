package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.feature.agent.domain.subagent.SubAgentEventBus
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.*

/** Process-local run membership; no database schema or write-lease authority is changed. */
@Singleton
class TeamRunRegistry @Inject constructor() {
    private data class Member(val id: String, val title: String, var session: String? = null,
        var state: String = "pending", val messages: MutableList<String> = mutableListOf(),
        val outgoing: MutableMap<String, MutableSet<String>> = mutableMapOf())
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
        member.outgoing.clear()
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

    @Synchronized fun started(session: String) {
        val (run, logical) = sessions[session] ?: return
        if (!canMessage(session)) return
        runs[run]?.members?.get(logical)?.let { member ->
            member.state = "running"
            member.outgoing.clear()
        }
    }

    @Synchronized fun canMessage(session: String): Boolean = session !in stopped
    @Synchronized fun runOf(session: String): String? = sessions[session]?.first
    @Synchronized fun belongs(run: String, parent: String, logical: String): Boolean =
        runs[run]?.let { it.parent == parent && logical in it.members } == true

    @Synchronized fun canCommunicate(from: String, to: String): Boolean {
        val fromRun = runOf(from)
        val toRun = runOf(to)
        return canMessage(from) && canMessage(to) && fromRun == toRun
    }

    @Synchronized fun route(from: String, target: String, message: String,
        deliver: ((String) -> Boolean)? = null): Delivery {
        val (run, self) = sessions[from] ?: return Delivery("blocked")
        val members = runs[run]?.members ?: return Delivery("blocked")
        val sender = members[self] ?: return Delivery("blocked")
        val member = members[target] ?: sessions[target]?.takeIf { it.first == run }?.second?.let { members[it] }
            ?: return Delivery("blocked")
        if (member.id == self || !canMessage(from) || sender.state != "running" ||
            member.state !in setOf("pending", "running")) return Delivery("blocked")
        val text = message.trim()
        if (text.isBlank() || text.codePointCount(0, text.length) > SubAgentEventBus.SIBLING_MESSAGE_MAX) {
            return Delivery("invalid_message")
        }
        val sent = sender.outgoing.getOrPut(member.id) { mutableSetOf() }
        if (text in sent) return Delivery("duplicate")
        if (sent.size >= 2) return Delivery("pair_limit")
        if (sender.outgoing.values.sumOf { it.size } >= 4) return Delivery("total_limit")
        val session = member.session
        if (session == null) {
            member.messages += text
            sent.add(text)
            return Delivery("queued")
        }
        if (!canMessage(session)) return Delivery("blocked")
        // Registry -> active-state lock is the shared order; failed delivery never spends budget.
        if (deliver != null && !deliver(session)) return Delivery("unavailable")
        sent.add(text)
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
