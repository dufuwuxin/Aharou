package com.aharou.feature.agent.domain.subagent

import com.aharou.feature.agent.domain.model.AgentMode

/** 子代理定义的来源作用域：全局（跨项目共享）或项目级（仅当前工作区生效，可 git 追踪）。 */
enum class AgentDefinitionScope { GLOBAL, PROJECT }

/** 成员可继承的记忆视图选项；所有正文仍由原生记忆条目承载。 */
enum class InjectPart(val token: String) {
    SKILLS("skills"),
    MEMORY("memory");

    companion object {
        /** frontmatter 里的宽松写法映射：忽略大小写、连字符与下划线差异。 */
        fun fromToken(token: String): InjectPart? {
            val normalized = token.trim().lowercase().replace("-", "").replace("_", "")
            return when (normalized) {
                "skills", "skill" -> SKILLS
                "memory", "memories" -> MEMORY
                else -> null
            }
        }
    }
}

/**
 * 关键词注入条目：由用户定义一套「触发词→注入正文」的映射，运行时按当前任务的用户文本
 * 做 substring 匹配。命中的正文只在命中的会话/角色里出现，主代理（非匹配会话）与未命中
 * 的成员都看不到——节省 token，减少上下文污染。
 */
data class KeywordInjection(
    val keywords: List<String>,
    val injectContent: String
)

/**
 * 一个自定义子代理定义，来自 `agents/<name>.md` 的 frontmatter + 正文。
 *
 * @param name 唯一标识，`task(agent="<name>")` 用它派发
 * @param description 何时该派这个子代理，注入主代理提示词供其选择
 * @param providerId 绑定的 provider id；null 表示继承父会话
 * @param model 绑定的模型名；null 表示继承父会话
 * @param reasoningEffort 思考强度（low/medium/high）；null 表示继承父会话
 * @param mode 运行模式（build/plan/auto）；null 表示继承父会话
 * @param allowedTools 工具白名单；空表示继承全量工具
 * @param disallowedTools 工具黑名单，先于白名单生效
 * @param inject 要注入的提示词片段
 * @param keywordInjections 关键词注入列表：运行时仅当本次用户输入含任意一条 keyword 时把对应正文追加到提示词
 * @param prompt agent 自身的系统提示词（正文）
 * @param filePath 定义文件的容器路径，供设置页展示与删除
 */
data class AgentDefinition(
    val name: String,
    val description: String,
    val providerId: String? = null,
    val model: String? = null,
    val reasoningEffort: String? = null,
    val mode: AgentMode? = null,
    val allowedTools: List<String> = emptyList(),
    val disallowedTools: List<String> = emptyList(),
    /** 该角色专属绑定的技能名：运行时系统提示词只注入公共技能 + 这些专属技能，避免给别的角色看。 */
    val skills: List<String> = emptyList(),
    val inject: Set<InjectPart> = DEFAULT_INJECT,
    val keywordInjections: List<KeywordInjection> = emptyList(),
    val prompt: String,
    val filePath: String? = null,
    val tags: List<String> = emptyList()
) {
    /**
     * 按白名单/黑名单裁剪工具名集合。
     *
     * 黑名单先生效，再与白名单取交集；支持 `mcp__server__*` 形式的通配，
     * 以便一次禁掉某个 MCP server 的全部工具。
     *
     * 不再无条件剔除 `task`：子代理现已允许再派子代理，递归改由 TaskTool 依据
     * [MAX_SUBAGENT_DEPTH] 做深度上限兜底。要禁掉某个角色的派发能力，把 `task`
     * 写进 [disallowedTools] 即可。
     */
    fun filterToolNames(all: List<String>): List<String> {
        val afterDeny = all.filterNot { name -> disallowedTools.any { matches(it, name) } }
        return if (allowedTools.isEmpty()) {
            afterDeny
        } else {
            // Discovery and workspace-scoped process reads are baseline read-only capabilities; explicit deny still wins.
            afterDeny.filter { name -> name in setOf("tool_search", "sessions") || allowedTools.any { matches(it, name) } }
        }
    }

    private fun matches(pattern: String, toolName: String): Boolean {
        val p = canonicalToolName(pattern.trim())
        if (p.isEmpty()) return false
        if (p.endsWith("*")) return toolName.startsWith(p.dropLast(1), ignoreCase = true)
        return toolName.equals(p, ignoreCase = true)
    }

    companion object {
        const val MAX_ROLE_TAGS = 24
        const val MAX_ROLE_TAG_LENGTH = 64

        fun normalizeTags(tags: List<String>): List<String> = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        fun validTags(tags: List<String>): Boolean = normalizeTags(tags).let { normalized ->
            normalized.size <= MAX_ROLE_TAGS && normalized.all {
                it.length <= MAX_ROLE_TAG_LENGTH && it.none { c -> c == ',' || c.isISOControl() }
            }
        }

        /**
         * 从 Claude Code 生态导入的角色文件用 `Read` / `Write` / `Edit` / `Grep` / `Glob` 这套
         * 工具名，与本应用的 `readFile` / `writeFile` / … 对不上。白名单非空时只保留命中的工具，
         * 对不上就等于把其余工具全挡掉：角色声明了读写文件，却只剩下 `WebFetch` / `WebSearch` 可用。
         * 这里做一次等价映射，让旧名字按本应用的工具名参与匹配。
         */
        private val TOOL_ALIASES = mapOf(
            "read" to "readFile",
            "write" to "writeFile",
            "edit" to "editFile",
            "multiedit" to "editFile",
            "notebookedit" to "editFile",
            "grep" to "search",
            "glob" to "list",
            "ls" to "list",
        )

        private fun canonicalToolName(name: String): String =
            TOOL_ALIASES[name.lowercase()] ?: name

        /** 省略 `inject` 时在记忆请求模板里展开技能发现和普通记忆。 */
        val DEFAULT_INJECT: Set<InjectPart> = setOf(InjectPart.SKILLS, InjectPart.MEMORY)

        /**
         * 子代理递归派发的最大深度：主代理（0）→ 子代理（1）→ 孙代理（2）为上限，
         * 再往下由 TaskTool 直接拒绝，避免递归派发无限膨胀、耗光并发额度。
         */
        const val MAX_SUBAGENT_DEPTH = 2

        /**
         * `task` 工具的标识名，也是子代理派发所用的工具名。子代理现已允许再派子代理，
         * 嵌套递归由 [MAX_SUBAGENT_DEPTH] 深度上限兜底，工具集不再按此名剔除；要禁掉某个
         * 角色的派发能力，把 `task` 写进其 [AgentDefinition.disallowedTools] 即可。
         */
        const val NESTED_TOOL = "task"

        /** 仅子代理可用的工具（子→主发消息）；主会话工具集剔除它。 */
        const val PARENT_MESSAGE_TOOL = "messageParent"
    }
}

/** 一个生效的子代理定义条目：定义本体 + 其来源作用域，供 UI 标注「全局/项目」。 */
data class AgentDefinitionEntry(
    val definition: AgentDefinition,
    val scope: AgentDefinitionScope
)

/** 设置页新建/编辑子代理时提交的表单快照。 */
data class AgentDefinitionForm(
    val name: String,
    val description: String,
    val providerId: String? = null,
    val model: String? = null,
    val reasoningEffort: String? = null,
    val mode: AgentMode? = null,
    val allowedTools: List<String> = emptyList(),
    val disallowedTools: List<String> = emptyList(),
    val skills: List<String> = emptyList(),
    val inject: Set<InjectPart> = AgentDefinition.DEFAULT_INJECT,
    val keywordInjections: List<KeywordInjection> = emptyList(),
    val prompt: String = "",
    val tags: List<String> = emptyList()
)

/** 保存子代理定义失败的原因。 */
enum class AgentSaveError {
    /** 名称为空、过长或含不能做文件名的字符。 */
    INVALID_NAME,

    INVALID_TAGS,

    /** 提示词正文为空：解析器会直接丢掉这种定义，存下来也用不了。 */
    EMPTY_PROMPT,

    /** 同作用域已有同名子代理。 */
    NAME_CONFLICT,

    /** 写盘失败。 */
    IO_FAILED
}
