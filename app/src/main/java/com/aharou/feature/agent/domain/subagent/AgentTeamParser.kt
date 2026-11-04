package com.aharou.feature.agent.domain.subagent

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.common.Frontmatter
import com.aharou.feature.workspace.domain.FileAccessProvider

/**
 * 解析团队文件（`teams/<name>.md`）：YAML frontmatter 是名单 + 流程，正文是团队公约。
 *
 * 名单两种写法等价（都指向 `agents/` 目录里的同名文件）：
 * ```
 * members:                       # 带层级：1=一级成员组，2=二级，依次类推
 *   - name: 需求分析师
 *     level: 1
 *   - 最小改动工程师              # 纯字符串＝一级成员
 * admins: [架构评审官, 安全评审官]  # 管理员组，可多个
 * ```
 * 阶段的两种写法：
 * ```
 * stages:
 *   - title: 问题拆解            # 单任务：agent/prompt 直接写在阶段上
 *     agent: 需求分析师
 *     prompt: 拆成可执行子任务
 *   - title: 并行实现            # 成员名单 + 共享指令：解析时展开成一人一条任务
 *     level: 2
 *     members: [Mobile App Builder, 最小改动工程师]
 *     prompt: 实现界面
 *   - title: 验收                # 关卡：不通过就打回「并行实现」
 *     agent: Reality Checker
 *     prompt: 逐条核对验收标准
 *     review: true
 *     rejectTo: 并行实现
 * ```
 * 写错的部分按「丢掉这一条并记日志」处理：单个任务缺 prompt 或缺成员只丢这个任务，整阶段没有
 * 有效任务就丢这个阶段；一个阶段都不剩时整份团队视为非法（返回 null），免得空团队跑起来什么都没干。
 */
object AgentTeamParser {
    private const val TAG = "AgentTeamParser"
    private const val MAX_DESC_CHARS = 500
    private const val DEFAULT_STAGE_TITLE = "未命名阶段"

    fun parse(provider: FileAccessProvider, filePath: String): AgentTeam? {
        val text = runCatching { provider.readFile(filePath) }.getOrElse {
            FileLogger.w(TAG, "读取团队失败: $filePath", it)
            return null
        }

        val (frontmatter, body) = Frontmatter.splitAndParse(text, TAG, "解析团队 YAML 失败")
        val stages = parseStages(frontmatter["stages"], filePath)
        if (stages.isEmpty()) {
            FileLogger.w(TAG, "团队没有有效阶段，已忽略: $filePath")
            return null
        }

        val fileName = filePath.substringAfterLast('/')
        val name = frontmatter["name"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
            ?: fileName.substringBeforeLast('.')
        val description = (frontmatter["description"]?.toString() ?: "").take(MAX_DESC_CHARS)

        return AgentTeam(
            name = name,
            description = description,
            stages = stages,
            charter = body.trim(),
            admins = parseNames(frontmatter["admins"]),
            members = parseMemberRefs(frontmatter["members"]),
            filePath = filePath,
            routes = parseRoutes(frontmatter["routes"])
        )
    }

    /**
     * 把编辑表单写回 Markdown（frontmatter + 团队公约），与 [parse] 成对。
     *
     * 阶段统一用 `members` + `tasks:` 双写：`members` 供界面按层级展示名单，`tasks` 是真执行单元
     * （同一个人在不同阶段可能拿到不同指令，只靠名单表达不出来）。回读以 `tasks` 为准。
     */
    fun serialize(form: AgentTeamForm): String = buildString {
        appendLine("---")
        appendLine("name: ${Frontmatter.quote(form.name.trim())}")
        appendLine("description: ${Frontmatter.quote(form.description.trim())}")
        appendLine("admins: [${form.admins.joinToString(", ") { Frontmatter.quote(it.trim()) }}]")
        appendLine("members:")
        form.members.forEach { member ->
            appendLine("  - name: ${Frontmatter.quote(member.name.trim())}")
            appendLine("    level: ${member.level.coerceAtLeast(1)}")
        }
        if (form.routes.isNotEmpty()) {
            appendLine("routes:")
            form.routes.forEach { route ->
                appendLine("  - fromStage: ${Frontmatter.quote(route.from.stageId)}")
                appendLine("    fromTask: ${Frontmatter.quote(route.from.taskId)}")
                appendLine("    toStage: ${Frontmatter.quote(route.to.stageId)}")
                appendLine("    toTask: ${Frontmatter.quote(route.to.taskId)}")
                appendLine("    action: ${route.action.name}")
            }
        }
        appendLine("stages:")
        form.stages.forEach { stage ->
            appendLine("  - title: ${Frontmatter.quote(stage.title.trim())}")
            appendLine("    id: ${Frontmatter.quote(stage.id)}")
            appendLine("    level: ${stage.level.coerceAtLeast(1)}")
            val stageMembers = stage.members.map { it.trim() }.filter { it.isNotEmpty() }
            if (stageMembers.isNotEmpty()) {
                appendLine("    members: [${stageMembers.joinToString(", ") { Frontmatter.quote(it) }}]")
            }
            appendLine("    communication: ${stage.communication.token}")
            appendLine("    messageLimit: ${stage.messageLimit.coerceIn(1, 100)}")
            if (stage.communicationGuide.isNotBlank()) appendLine("    communicationGuide: ${Frontmatter.quote(stage.communicationGuide.trim())}")
            if (stage.isReview) appendLine("    review: true")
            val rejectTo = stage.rejectTo.trim()
            if (rejectTo.isNotEmpty()) appendLine("    rejectTo: ${Frontmatter.quote(rejectTo)}")
            val sharedPrompt = stage.prompt.trim()
            if (sharedPrompt.isNotEmpty()) appendLine("    prompt: ${Frontmatter.quote(sharedPrompt)}")
            appendLine("    tasks:")
            stage.tasks.forEach { task ->
                val agent = task.agent.trim()
                if (agent.isNotEmpty()) {
                    appendLine("      - agent: ${Frontmatter.quote(agent)}")
                } else {
                    appendLine("      - department: ${Frontmatter.quote(task.department.trim())}")
                    val query = task.agentQuery.trim()
                    if (query.isNotEmpty()) appendLine("        agentQuery: ${Frontmatter.quote(query)}")
                }
                appendLine("        id: ${Frontmatter.quote(task.id)}")
                appendLine("        prompt: ${Frontmatter.quote(task.prompt.trim())}")
                if (task.interfaces.isNotEmpty()) {
                    appendLine("        interfaces:")
                    task.interfaces.forEach { edge ->
                        appendLine("          - with: ${Frontmatter.quote(edge.with.trim())}")
                        if (edge.about.isNotBlank()) {
                            appendLine("            about: ${Frontmatter.quote(edge.about.trim())}")
                        }
                    }
                }
                // 全角逗号也认：手机输入法默认就是它。
                val paths = task.writePaths.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
                if (paths.isNotEmpty()) {
                    appendLine("        writePaths: [${paths.joinToString(", ") { Frontmatter.quote(it) }}]")
                }
            }
        }
        appendLine("---")
        if (form.charter.isNotBlank()) appendLine(form.charter.trim())
    }

    private fun parseRoutes(raw: Any?): List<TeamRoute> {
        if (raw == null) return emptyList()
        // Keep malformed routes as invalid endpoints, never silently downgrade to legacy serial execution.
        val list = raw as? List<*> ?: listOf(raw)
        return list.map { item ->
            val map = item as? Map<*, *> ?: emptyMap<Any, Any>()
            val action = TeamRouteAction.entries.firstOrNull { it.name == map["action"]?.toString()?.uppercase() }
            TeamRoute(TeamFlowNode(map["fromStage"]?.toString().orEmpty(), map["fromTask"]?.toString().orEmpty()),
                TeamFlowNode(map["toStage"]?.toString().orEmpty(), map["toTask"]?.toString().orEmpty()),
                action ?: TeamRouteAction.EXECUTE).let { if (action == null) it.copy(from = TeamFlowNode("", "")) else it }
        }
    }

    /** `admins` 这类纯名字列表：字符串或 `{name: x}` 都认。 */
    private fun parseNames(raw: Any?): List<String> = when (raw) {
        is List<*> -> raw.mapNotNull { item ->
            when (item) {
                is Map<*, *> -> item["name"]?.toString()?.trim()
                else -> item?.toString()?.trim()
            }?.takeIf { it.isNotBlank() }
        }
        null -> emptyList()
        else -> listOfNotNull(raw.toString().trim().takeIf { it.isNotBlank() })
    }

    /** `members` 名单：纯字符串＝一级，`{name, level}` 带层级。 */
    private fun parseMemberRefs(raw: Any?): List<TeamMemberRef> = when (raw) {
        is List<*> -> raw.mapNotNull { item ->
            when (item) {
                is Map<*, *> -> {
                    val name = item["name"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
                    val level = item["level"]?.toString()?.trim()?.toIntOrNull() ?: 1
                    name?.let { TeamMemberRef(it, level.coerceAtLeast(1)) }
                }
                else -> item?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { TeamMemberRef(it) }
            }
        }
        else -> emptyList()
    }

    private fun parseStages(raw: Any?, filePath: String): List<TeamStage> =
        (raw as? List<*>).orEmpty().mapIndexedNotNull { index, item -> parseStage(item, index, filePath) }

    private fun parseStage(raw: Any?, index: Int, filePath: String): TeamStage? {
        val map = raw as? Map<*, *> ?: run {
            FileLogger.w(TAG, "团队第 ${index + 1} 阶段不是对象，已跳过: $filePath")
            return null
        }
        val title = (map["title"] ?: map["name"] ?: map["stage"])?.toString()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_STAGE_TITLE
        val tasks = when {
            map.containsKey("tasks") -> parseTaskList(map["tasks"], title, filePath)
            // 名单 + 共享指令：一人一条任务
            map.containsKey("members") -> expandMembers(map, title, filePath)
            else -> listOfNotNull(parseTask(map, title, filePath))
        }
        if (tasks.isEmpty()) {
            FileLogger.w(TAG, "团队的阶段「$title」没有有效任务，已跳过: $filePath")
            return null
        }
        val level = map["level"]?.toString()?.trim()?.toIntOrNull() ?: 1
        val isReview = map["review"]?.toString()?.trim()?.lowercase() in setOf("true", "yes", "1") ||
            map["gate"]?.toString()?.trim()?.lowercase() in setOf("true", "yes", "1")
        val rejectTo = (map["rejectTo"] ?: map["onReject"])
            ?.toString()?.trim()?.takeIf { it.isNotBlank() }
        return TeamStage(
            title = title,
            id = map["id"]?.toString()?.takeIf { it.isNotBlank() } ?: "stage-${index + 1}",
            tasks = tasks.mapIndexed { i, task -> task.copy(id = task.id.ifBlank { "task-${i + 1}" }) },
            level = level.coerceAtLeast(1),
            isReview = isReview,
            rejectTo = rejectTo,
            communication = if (map.containsKey("communication")) {
                TeamCommunication.fromToken(map["communication"]?.toString()) ?: TeamCommunication.PARENT_ONLY
            } else TeamCommunication.RUN,
            messageLimit = map["messageLimit"]?.toString()?.toIntOrNull()?.coerceIn(1, 100) ?: 8,
            communicationGuide = map["communicationGuide"]?.toString()?.trim().orEmpty()
        )
    }

    /** `members: [a, b]` + 阶段级 `prompt` 展开成两条任务；缺指令时整段作废。 */
    private fun expandMembers(map: Map<*, *>, stageTitle: String, filePath: String): List<TeamTask> {
        val prompt = map["prompt"]?.toString()?.trim().orEmpty()
        if (prompt.isEmpty()) {
            FileLogger.w(TAG, "阶段「$stageTitle」有成员名单但缺 prompt，已跳过: $filePath")
            return emptyList()
        }
        val writePaths = AgentDefinitionParser.stringList(map["writePaths"])
        return parseNames(map["members"]).mapIndexed { i, name -> TeamTask(agent = name, prompt = prompt, writePaths = writePaths, id = "task-${i + 1}") }
    }

    private fun parseTaskList(raw: Any?, stageTitle: String, filePath: String): List<TeamTask> =
        (raw as? List<*>).orEmpty().mapNotNull { parseTask(it, stageTitle, filePath) }

    private fun parseTask(raw: Any?, stageTitle: String, filePath: String): TeamTask? {
        val map = raw as? Map<*, *> ?: return null
        val prompt = map["prompt"]?.toString()?.trim().orEmpty()
        if (prompt.isEmpty()) {
            FileLogger.w(TAG, "阶段「$stageTitle」有任务缺 prompt，已跳过: $filePath")
            return null
        }
        val agent = map["agent"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
        val department = map["department"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
        val agentQuery = map["agentQuery"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
        if (agent == null && department == null && agentQuery == null) {
            FileLogger.w(TAG, "阶段「$stageTitle」有任务没写 agent/department，已跳过: $filePath")
            return null
        }
        return TeamTask(
            id = map["id"]?.toString().orEmpty(),
            agent = agent,
            department = department,
            agentQuery = agentQuery,
            prompt = prompt,
            writePaths = AgentDefinitionParser.stringList(map["writePaths"]),
            interfaces = parseInterfaces(map["interfaces"], stageTitle, filePath)
        )
    }

    /**
     * 任务级接口边：`- with: 后端工程师` + 可选 `about:`；纯字符串条目等价于只写 with。
     * 条目缺 with 只丢这一条，不连带丢任务。
     */
    private fun parseInterfaces(raw: Any?, stageTitle: String, filePath: String): List<TeamInterface> =
        (raw as? List<*>).orEmpty().mapNotNull { item ->
            when (item) {
                is Map<*, *> -> {
                    val with = (item["with"] ?: item["member"] ?: item["target"])
                        ?.toString()?.trim()?.takeIf { it.isNotBlank() }
                    if (with == null) {
                        FileLogger.w(TAG, "阶段「$stageTitle」有接口边缺 with，已跳过: $filePath")
                        null
                    } else {
                        TeamInterface(
                            with = with,
                            about = (item["about"] ?: item["align"])?.toString()?.trim().orEmpty()
                        )
                    }
                }
                else -> item?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let { TeamInterface(it) }
            }
        }
}
