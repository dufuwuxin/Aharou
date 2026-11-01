package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.subagent.ObserveDetail
import com.aharou.feature.agent.domain.subagent.ObserveDirection
import com.aharou.feature.agent.domain.subagent.ObservedMember
import com.aharou.feature.agent.domain.subagent.SessionTreeObserver
import com.aharou.feature.agent.domain.subagent.TargetResolution
import com.aharou.feature.agent.domain.tool.AbstractContextualTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * 只读观察工具 `observe`：看同一棵会话树里「上级在干什么」或「下级做到哪一步了」。
 *
 * 存在的理由：`task(action="read")` 只能拿直接子代理的**最终**输出，看不到中间步骤，
 * 也看不到自己的上级——而「这个 AI 的做法有没有问题」恰恰要靠中间步骤判断（是不是在同一个
 * 工具上反复打转、是不是把任务理解偏了、是不是卡在一个没返回的工具上）。
 *
 * 边界：
 * - 只读，不改任何内容，也不发消息（要发消息用 `tell` / `send` / `messageParent`）；
 * - 只允许同一棵树内的成员（祖先或后代），跨树一律拒绝；
 * - 输出是摘要：正文截断、工具参数折叠成「N 字」、单个成员最多若干条，总量还有上限。
 */
class ObserveTool @Inject constructor(
    private val observer: SessionTreeObserver
) : AbstractContextualTool() {

    private companion object {
        /** 一次汇报最多展开多少个成员（整层团队也不会把上下文塞爆）。 */
        const val MAX_MEMBERS = 8

        /** 一次汇报的总字符上限。 */
        const val MAX_TOTAL_CHARS = 8000

        const val DEFAULT_RECENT = 8
        const val MAX_RECENT = 30
        const val DEFAULT_DEPTH = 1
    }

    override val name = "observe"

    override val description = "只读观察同一棵会话树里其它成员（上级或下级）最近在做什么，" +
        "用来诊断它的做法有没有跑偏（反复调用同一工具、理解偏差、卡在未返回的工具上）。" +
        "不改动任何内容；跨树不可见。要看自己的下级用 direction=down，要理解上级的意图用 direction=up。"

    override val parameters: Map<String, ToolParameter> = mapOf(
        "direction" to ToolParameter(
            name = "direction",
            type = ParameterType.STRING,
            description = "观察方向：down=看自己派生的下级（默认），up=看自己的上级（父、祖父…）",
            required = false
        ),
        "target" to ToolParameter(
            name = "target",
            type = ParameterType.STRING,
            description = "可选：指定观察哪个成员，给会话 id 或标题片段。给了就不按 direction 展开，" +
                "直接看这一个（仍限同一棵树内）",
            required = false
        ),
        "depth" to ToolParameter(
            name = "depth",
            type = ParameterType.INTEGER,
            description = "展开层数，1=只看直接上/下一层（默认），最多 5",
            required = false
        ),
        "recent" to ToolParameter(
            name = "recent",
            type = ParameterType.INTEGER,
            description = "每个成员最多给出的最近消息条数，默认 $DEFAULT_RECENT，最多 $MAX_RECENT",
            required = false
        ),
        "detail" to ToolParameter(
            name = "detail",
            type = ParameterType.STRING,
            description = "内容粒度：summary=正文+工具名+参数摘要（默认）；tools=只看工具调用序列；text=只看对话正文",
            required = false
        )
    )

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val sessionId = context.sessionId ?: return ToolResult.Error("缺少会话上下文", "NO_SESSION")
        val self = observer.self(sessionId)
            ?: return ToolResult.Error("会话不存在: $sessionId", "SESSION_NOT_FOUND")

        val detail = when (string(args, "detail")?.lowercase()) {
            null, "", "summary" -> ObserveDetail.SUMMARY
            "tools" -> ObserveDetail.TOOLS
            "text" -> ObserveDetail.TEXT
            else -> return ToolResult.Error("参数无效：detail 只支持 summary / tools / text", "INVALID_ARGS")
        }
        val direction = when (string(args, "direction")?.lowercase()) {
            null, "", "down" -> ObserveDirection.DOWN
            "up" -> ObserveDirection.UP
            else -> return ToolResult.Error("参数无效：direction 只支持 down / up", "INVALID_ARGS")
        }
        val depth = int(args, "depth")?.coerceIn(1, 5) ?: DEFAULT_DEPTH
        val recent = int(args, "recent")?.coerceIn(1, MAX_RECENT) ?: DEFAULT_RECENT

        val target = string(args, "target")
        val members: List<ObservedMember> = if (target.isNullOrBlank()) {
            when (direction) {
                ObserveDirection.UP -> observer.upward(sessionId, depth)
                ObserveDirection.DOWN -> observer.downward(sessionId, depth)
            }
        } else {
            when (val resolution = observer.resolveTarget(sessionId, target)) {
                is TargetResolution.Found -> listOf(ObservedMember(resolution.session, 0))

                is TargetResolution.Ambiguous -> return ToolResult.Error(
                    "有多个成员匹配「$target」，请改用更精确的标题或会话 id：" +
                        resolution.candidates.joinToString("、") { "${it.title}(${it.id.take(8)})" },
                    "AMBIGUOUS_TARGET"
                )

                is TargetResolution.NotFound -> return ToolResult.Error(
                    "同一棵树里没有找到「$target」" +
                        if (resolution.nearby.isEmpty()) "" else "；同树成员有：" + resolution.nearby.joinToString("、"),
                    "TARGET_NOT_FOUND"
                )

                TargetResolution.OutOfTree -> return ToolResult.Error(
                    "「$target」不在当前会话所属的树里，观察通道只对同一棵树开放",
                    "OUT_OF_TREE"
                )
            }
        }

        if (members.isEmpty()) {
            val hint = when (direction) {
                ObserveDirection.UP -> "当前会话没有上级（它已经是这棵树的顶层），可以用 direction=down 看下级"
                ObserveDirection.DOWN -> "当前会话还没有派生过下级，可以用 task 工具创建，或 direction=up 看上级"
            }
            return ToolResult.Success(
                buildJsonObject {
                    put("members", 0)
                    put("report", hint)
                }
            )
        }

        val report = StringBuilder()
        val shown = mutableListOf<ObservedMember>()
        var truncated = false
        for (member in members) {
            if (shown.size >= MAX_MEMBERS || report.length >= MAX_TOTAL_CHARS) {
                truncated = true
                break
            }
            val chunk = observer.render(member, labelOf(member, sessionId), recent, detail)
            if (report.length + chunk.length > MAX_TOTAL_CHARS) {
                truncated = true
                break
            }
            report.append(chunk).append('\n')
            shown += member
        }
        if (truncated) {
            report.append("（已截断：共 ").append(members.size).append(" 个成员，本次展开 ")
                .append(shown.size).append(" 个；用 target 或更小的 depth/recent 精确观察）\n")
        }
        report.append("以上为只读快照，每个成员的进展会随它继续工作而变化；需要更细的某一步请用 target 指定它。")

        return ToolResult.Success(
            buildJsonObject {
                put("members", shown.size)
                put("direction", if (target.isNullOrBlank()) direction.name.lowercase() else "target")
                put("report", report.toString().trimEnd())
                put("ids", buildJsonArray { shown.forEach { add(JsonPrimitive(it.session.id)) } })
            }
        )
    }

    /** 标题行的方向标注：上行取「父 / 祖父…」，下行按层数。 */
    private fun labelOf(member: ObservedMember, selfId: String): String {
        if (member.session.id == selfId) return "自己"
        val level = kotlin.math.abs(member.depth)
        return when {
            member.depth < 0 -> when (level) {
                1 -> "上级·父"
                2 -> "上级·祖父"
                else -> "上级·第 ${level} 层"
            }

            member.depth > 0 -> "下级·第 $level 层"
            else -> "指定成员"
        }
    }

    private fun string(args: Map<String, JsonElement>, key: String): String? =
        (args[key] as? JsonPrimitive)?.contentOrNull?.trim()

    private fun int(args: Map<String, JsonElement>, key: String): Int? =
        (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()
}
