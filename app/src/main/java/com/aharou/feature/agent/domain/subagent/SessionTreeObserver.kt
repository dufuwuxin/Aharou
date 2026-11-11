package com.aharou.feature.agent.domain.subagent

import com.aharou.feature.agent.data.local.entity.AgentMessageEntity
import com.aharou.feature.agent.domain.tool.ToolCall
import com.aharou.feature.agent.presentation.MessageRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** 观察方向：向上看祖先链（父、祖父…），或向下看自己派生的子树。 */
enum class ObserveDirection { UP, DOWN }

/** 观察内容的粒度。 */
enum class ObserveDetail {
    /** 正文 + 工具名 + 参数摘要（默认，够诊断又省 token）。 */
    SUMMARY,

    /** 只看工具调用序列：判断它是不是在某个工具上反复打转。 */
    TOOLS,

    /** 只看对话正文：判断它的理解有没有跑偏。 */
    TEXT
}

/** 会话树上的一个节点，只保留观察需要的字段（不把整个 Room 实体透出来）。 */
data class TreeSession(
    val id: String,
    val parentId: String?,
    val title: String,
    /** 子代理角色名（探索 / 评审…），主会话为 null。 */
    val subagentType: String? = null
)

/** 会话树只读入口：按 parentId 取节点与直接子节点。 */
interface SessionTreeSource {
    suspend fun find(sessionId: String): TreeSession?
    suspend fun childrenOf(parentId: String): List<TreeSession>
}

/** 会话最近消息的只读入口（运行中的会话也能读到已落库的部分）。 */
interface SessionTranscriptSource {
    suspend fun messages(sessionId: String): List<AgentMessageEntity>
}

/** 被观察的成员；[depth] 上行取负、下行取正，2 表示「隔两层」。 */
data class ObservedMember(val session: TreeSession, val depth: Int)

/**
 * 只读的会话树观察者：为「诊断某个 AI 的做法是否有问题」提供数据面。
 *
 * 两条通道都只读、都不改任何状态：
 * - [upward]：沿 `parentId` 向上取祖先（父、祖父…），成员据此理解「上级为什么这么派活」；
 * - [downward]：向下取子树（自己派生的成员，含隔层），上级据此诊断「下级这步偏在哪」。
 *
 * 越权边界在 [inSameTree]：只允许观察与自己同一棵树的成员，别的一律拒绝——观察通道不该
 * 变成跨会话的内容读取入口。渲染见 [render]，只输出摘要（正文截断 + 工具名 + 参数摘要），
 * 长参数一律折叠成「N 字」，避免一次观察把调用方的上下文塞爆。
 */
@Singleton
class SessionTreeObserver @Inject constructor(
    private val tree: SessionTreeSource,
    private val transcript: SessionTranscriptSource,
) {

    private companion object {
        /** 上行/下行最多解析的层数（含参数上限保护，防止恶意大的 depth 把树走穿）。 */
        const val MAX_DEPTH = 5

        /** 单个成员最多渲染的消息条数上限。 */
        const val MAX_RECENT = 30

        /** 正文单行截断长度。 */
        const val TEXT_MAX = 200

        /** 工具结果单行截断长度（比正文更短：结果常常是整段日志）。 */
        const val RESULT_MAX = 120

        /** 单个参数值的截断长度。 */
        const val ARG_MAX = 60

        /** 汇报里单个成员的标题行里标题截断长度。 */
        const val TITLE_MAX = 40

        /** 防环：上行最多走这么多跳。会话树正常深不过几层，超过必是脏数据。 */
        const val MAX_HOPS = 64

        /** 工具行「执行中」前缀，与 SessionUseCase.PENDING_TOOL_MARKER 同源语义（这里只读判断用）。 */
        const val PENDING_MARKER = "[running]"

        val json = Json { ignoreUnknownKeys = true }
    }

    /** 当前会话在树上的节点。 */
    suspend fun self(sessionId: String): TreeSession? = tree.find(sessionId)

    /**
     * 会话所在树的根 id（一路沿 [TreeSession.parentId] 走到顶层）。
     * 走链时带 [MAX_HOPS] 与已访问集合，脏数据造成的环不会把这里挂死。
     */
    suspend fun rootOf(sessionId: String): String? {
        var cursor = tree.find(sessionId) ?: return null
        val visited = mutableSetOf(cursor.id)
        var hops = 0
        while (hops++ < MAX_HOPS) {
            val parentId = cursor.parentId ?: return cursor.id
            if (!visited.add(parentId)) return cursor.id
            cursor = tree.find(parentId) ?: return parentId
        }
        return cursor.id
    }

    /** 两个会话是否同属一棵树（跨树观察一律拒绝）。 */
    suspend fun inSameTree(a: String, b: String): Boolean {
        if (a == b) return true
        val rootA = rootOf(a) ?: return false
        val rootB = rootOf(b) ?: return false
        return rootA == rootB
    }

    /** 向上取祖先链，由近及远；depth 为层数（1 = 只看父）。 */
    suspend fun upward(sessionId: String, depth: Int): List<ObservedMember> {
        val result = mutableListOf<ObservedMember>()
        var cursor = tree.find(sessionId) ?: return emptyList()
        val visited = mutableSetOf(sessionId)
        for (level in 1..depth.coerceIn(1, MAX_DEPTH)) {
            val parentId = cursor.parentId ?: break
            if (!visited.add(parentId)) break
            val parent = tree.find(parentId) ?: break
            result += ObservedMember(parent, -level)
            cursor = parent
        }
        return result
    }

    /** 向下取子树（广度优先，按层展开）；depth 为层数（1 = 只看直接下级）。 */
    suspend fun downward(sessionId: String, depth: Int): List<ObservedMember> {
        val result = mutableListOf<ObservedMember>()
        val visited = mutableSetOf(sessionId)
        var frontier = listOf(sessionId)
        for (level in 1..depth.coerceIn(1, MAX_DEPTH)) {
            val next = mutableListOf<String>()
            for (parentId in frontier) {
                for (child in tree.childrenOf(parentId)) {
                    if (!visited.add(child.id)) continue
                    result += ObservedMember(child, level)
                    next += child.id
                }
            }
            if (next.isEmpty()) break
            frontier = next
        }
        return result
    }

    /**
     * 按 id 或标题片段在同一棵树内解析观察目标。[keyword] 命中多个成员时返回 [TargetResolution.Ambiguous]，
     * 由调用方把候选列给模型自己收敛，不替它猜。
     */
    suspend fun resolveTarget(currentId: String, keyword: String): TargetResolution {
        val key = keyword.trim()
        if (key.isEmpty()) return TargetResolution.NotFound(emptyList())
        tree.find(key)?.let { exact ->
            return if (inSameTree(currentId, exact.id)) {
                TargetResolution.Found(exact)
            } else {
                TargetResolution.OutOfTree
            }
        }
        val rootId = rootOf(currentId) ?: return TargetResolution.NotFound(emptyList())
        val all = mutableListOf<TreeSession>()
        if (rootId != currentId) tree.find(rootId)?.let { all += it }
        all += downward(rootId, MAX_DEPTH).map { it.session }
        val hits = all.distinctBy { it.id }
            .filter { it.id != currentId && it.title.contains(key, ignoreCase = true) }
        return when (hits.size) {
            0 -> TargetResolution.NotFound(all.filter { it.id != currentId }.map { it.title }.take(10))
            1 -> TargetResolution.Found(hits.first())
            else -> TargetResolution.Ambiguous(hits)
        }
    }

    /**
     * 把单个成员渲染成诊断摘要。
     *
     * @param label 方向标注（如「上行 1 层」「下行 2 层」），直接出现在标题行里。
     * @param recent 最多取最近多少条消息。
     * @param detail 内容粒度。粒度只影响「展示什么」：工具调用与正文都来自同一条消息流。
     */
    suspend fun render(
        member: ObservedMember,
        label: String,
        recent: Int,
        detail: ObserveDetail,
    ): String {
        val session = member.session
        val builder = StringBuilder()
        builder.append("[").append(label).append("] ")
            .append(session.title.take(TITLE_MAX).ifBlank { "（未命名）" })
            .append("　角色=").append(session.subagentType ?: "主会话")
        builder.append("　id=").append(shortId(session.id))
        builder.append('\n')

        val messages = transcript.messages(session.id)
            .filter { !it.isCompacted && !it.isContextSummary && !it.isCompactionMarker }
        if (messages.isEmpty()) {
            builder.append("（还没有内容）\n")
            return builder.toString()
        }

        // 下行观察时先给「派给它的任务」，否则光看半截过程判断不了它对不对。
        messages.firstOrNull { it.role == MessageRole.USER.name }?.content?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { builder.append("任务：").append(oneLine(it, TEXT_MAX)).append('\n') }

        val window = messages.takeLast(recent.coerceIn(1, MAX_RECENT))
        if (window.size < messages.size) {
            builder.append("（以下为最近 ").append(window.size).append(" 条，共 ").append(messages.size).append(" 条）\n")
        }
        window.forEach { builder.append(line(it, detail)) }

        val last = messages.last()
        if (last.role == MessageRole.TOOL.name && last.content.startsWith(PENDING_MARKER)) {
            builder.append("⚠ 最后一步工具还没有返回，可能卡在这里\n")
        }
        return builder.toString()
    }

    /** 单条消息 → 摘要行。 */
    private fun line(message: AgentMessageEntity, detail: ObserveDetail): String = when (message.role) {
        MessageRole.USER.name -> if (detail == ObserveDetail.TOOLS) "" else
            "用户: " + oneLine(message.content, TEXT_MAX) + "\n"

        MessageRole.ASSISTANT.name -> buildString {
            if (detail != ObserveDetail.TOOLS) {
                val text = message.content.trim()
                if (text.isNotEmpty()) append("助手: ").append(oneLine(text, TEXT_MAX)).append('\n')
            }
            if (detail != ObserveDetail.TEXT) {
                toolCallsOf(message).forEach { call ->
                    append("   · ").append(call.name).append('(').append(argsSummary(call)).append(")\n")
                }
            }
        }

        MessageRole.TOOL.name -> if (detail == ObserveDetail.TEXT) "" else {
            val name = message.toolName ?: "工具"
            val state = if (message.isError) "失败" else "成功"
            val body = oneLine(message.content, RESULT_MAX)
            "   ↳ $name $state: $body\n"
        }

        else -> ""
    }

    /** 解析 ASSISTANT 行里的 `tool_calls`；脏 JSON 一律当作没有工具调用，不让渲染炸掉。 */
    private fun toolCallsOf(message: AgentMessageEntity): List<ToolCall> {
        val raw = message.toolCallsJson ?: return emptyList()
        return runCatching { json.decodeFromString<List<ToolCall>>(raw) }.getOrDefault(emptyList())
    }

    /** 参数压成 `k=v, k2=…` 形式；长字符串折叠成「前 N 字…（共 M 字）」，避免整段文件内容灌进来。 */
    private fun argsSummary(call: ToolCall): String {
        if (call.arguments.isEmpty()) return ""
        return call.arguments.entries.joinToString(", ") { (key, value) -> "$key=${argValue(value)}" }
    }

    private fun argValue(value: kotlinx.serialization.json.JsonElement): String = when (value) {
        is JsonPrimitive -> {
            val text = value.contentOrNull.orEmpty()
            when {
                !value.isString -> text
                text.length <= ARG_MAX -> "\"" + oneLine(text, ARG_MAX) + "\""
                else -> "\"" + oneLine(text, 30) + "…（共 " + text.length + " 字）\""
            }
        }

        is JsonArray -> "[${value.size} 项]"
        is JsonObject -> "{${value.keys.size} 个字段}"
    }

    /** 压成单行并截断，保留「被截了」的信号。 */
    private fun oneLine(text: String, max: Int): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= max) flat else flat.take(max) + "…"
    }

    private fun shortId(id: String): String = id.take(8)
}

/** [SessionTreeObserver.resolveTarget] 的结果。 */
sealed interface TargetResolution {
    data class Found(val session: TreeSession) : TargetResolution

    /** 命中多个，需要模型自己收敛（附候选）。 */
    data class Ambiguous(val candidates: List<TreeSession>) : TargetResolution

    /** 树里找不到；[nearby] 给出同树成员标题，便于模型改用正确的名字。 */
    data class NotFound(val nearby: List<String>) : TargetResolution

    /** 命中的会话不在同一棵树里（跨树观察一律拒绝）。 */
    data object OutOfTree : TargetResolution
}
