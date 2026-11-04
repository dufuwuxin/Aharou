package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.feature.agent.domain.subagent.TeamCommunication
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.*

/** Process-local run membership; no database schema or write-lease authority is changed. */
@Singleton
class TeamRunRegistry @Inject constructor() {
    private data class Member(val id: String, val title: String, val stage: String? = null,
        val communication: TeamCommunication = TeamCommunication.RUN, val messageLimit: Int = 8,
        var session: String? = null, var state: String = "pending",
        val messages: MutableList<String> = mutableListOf(), val sent: MutableSet<Pair<String, String>> = mutableSetOf())
    private data class Run(val parent: String, val members: LinkedHashMap<String, Member>)
    data class Delivery(val state: String, val sessionId: String? = null, val reason: String? = null)
    private val runs = mutableMapOf<String, Run>()
    private val sessions = mutableMapOf<String, Pair<String, String>>()
    private val stopped = mutableSetOf<String>()
    private val managedSessions = mutableSetOf<String>()
    private val standalone = mutableMapOf<String, String>()

    @Synchronized fun begin(parent: String, pipeline: JsonArray): String {
        val id = UUID.randomUUID().toString()
        val members = linkedMapOf<String, Member>()
        pipeline.forEach { raw ->
            val task = raw.jsonObject
            val logical = task.getValue("id").jsonPrimitive.content
            val policy = task["_teamCommunication"]?.jsonPrimitive?.contentOrNull
            members[logical] = Member(logical, task["description"]?.jsonPrimitive?.content ?: logical,
                task["_teamStage"]?.jsonPrimitive?.contentOrNull,
                if (policy == null) TeamCommunication.RUN else TeamCommunication.fromToken(policy) ?: TeamCommunication.PARENT_ONLY,
                task["_teamMessageLimit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 100) ?: 8)
        }
        runs[id] = Run(parent, members)
        return id
    }

    @Synchronized fun bind(run: String, logical: String, session: String): String {
        val member = runs[run]?.members?.get(logical) ?: error("Unknown run task")
        member.session?.takeIf { it != session }?.let { sessions.remove(it) }
        member.session = session
        if (member.stage != null) managedSessions += session
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
            member.messages.clear()
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
        val (run, self) = sessions[from] ?: return Delivery("blocked", reason = "NOT_SIBLING")
        val members = runs[run]?.members ?: return Delivery("blocked", reason = "NOT_SIBLING")
        val sender = members[self] ?: return Delivery("blocked", reason = "NOT_SIBLING")
        val member = members[target] ?: sessions[target]?.takeIf { it.first == run }?.second?.let { members[it] }
            ?: return Delivery("blocked", reason = "NOT_SIBLING")
        if (member.id == self || !canMessage(from) || member.state in setOf("stopped", "deleted")) {
            return Delivery("blocked", reason = "NOT_SIBLING")
        }
        if (sender.communication == TeamCommunication.PARENT_ONLY || member.communication == TeamCommunication.PARENT_ONLY ||
            (sender.communication == TeamCommunication.SAME_STAGE || member.communication == TeamCommunication.SAME_STAGE) && sender.stage != member.stage) {
            return Delivery("blocked", reason = "TEAM_COMMUNICATION_SCOPE")
        }
        if ((sender.stage != null && sender.state != "running") ||
            (member.stage != null && member.state !in setOf("pending", "running"))) {
            return Delivery("blocked", reason = "TEAM_MEMBER_FINISHED")
        }
        val text = message.trim()
        if (text.isEmpty() || text.length > 4000) return Delivery("blocked", reason = "INVALID_ARGS")
        val key = member.id to text
        if (key in sender.sent) return Delivery("blocked", reason = "TEAM_DUPLICATE_MESSAGE")
        if (sender.sent.size >= sender.messageLimit) return Delivery("blocked", reason = "TEAM_MESSAGE_LIMIT")
        val session = member.session
        if (session != null && !canMessage(session)) return Delivery("blocked", reason = "NOT_SIBLING")
        sender.sent += key
        if (session == null) {
            member.messages += if (sender.stage != null) "[team=$run sender=$self target=${member.id}]\n$text" else text
            return Delivery("queued")
        }
        return Delivery("delivered", session)
    }

    @Synchronized fun siblingMayWake(from: String, target: String): Boolean =
        from !in managedSessions && target !in managedSessions

    @Synchronized fun acceptsSiblingNotification(from: String, target: String): Boolean {
        if (siblingMayWake(from, target)) return true
        val origin = sessions[from] ?: return false
        val destination = sessions[target] ?: return false
        return origin.first == destination.first && canMessage(from) && canMessage(target) &&
            runs[destination.first]?.members?.get(destination.second)?.state == "running"
    }

    @Synchronized fun roster(session: String): List<JsonObject> {
        val run = sessions[session]?.first ?: return emptyList()
        return runs[run]?.members?.values.orEmpty().map { member -> buildJsonObject {
            put("id", member.id)
            put("runId", run)
            put("title", member.title)
            put("state", member.state)
            member.stage?.let { put("stage", it) }
            put("communication", member.communication.token)
            put("messagesRemaining", (member.messageLimit - member.sent.size).coerceAtLeast(0))
            member.session?.let { put("sessionId", it) }
        } }
    }

    @Synchronized fun briefing(session: String): String = buildString {
        val (run, self) = sessions[session] ?: return@buildString
        val member = runs[run]?.members?.get(self) ?: return@buildString
        append("## 团队运行批次：").append(run).append("\n你的任务身份：").append(self).append('\n')
        append("通信策略：").append(member.communication.token).append("；剩余消息额度：")
            .append((member.messageLimit - member.sent.size).coerceAtLeast(0)).append("。\n")
        append("parent_only 禁止互发；same_stage 仅同阶段；run 仅本运行批次。完成成员不接受重唤醒。\n")
        roster(session).forEach { append(it).append('\n') }
        append("用 task(action=team) 查询，tell 使用逻辑 id 或本批次 sessionId。\n")
        append("只讨论接口、证据、冲突、阻塞；不循环确认、不空等消息、不扩大任务授权。跨阶段以交接包为准。\n")
    }
}
