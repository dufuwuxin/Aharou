package com.aharou.feature.agent.domain.subagent

/**
 * 团队里的一个成员：只是「名单」上的一条引用，指向 `agents/<name>.md`。
 *
 * 关键约束：**没被任何团队引用的成员不进主提示词**（见 SystemPromptProvider 的成员索引），
 * 所以「建了成员但没加进团队」＝主代理根本不知道它存在，名单只用来挑人。
 *
 * @param level 成员层级：1=一级成员组（靠上游，做拆解与设计），2=二级，依次类推。层级决定
 *   一个成员在团队流程里默认被排在哪个高度，而不是硬限制——同一个成员可以被不同阶段引用。
 */
data class TeamMemberRef(
    val name: String,
    val level: Int = 1,
    /** 该成员专属绑定的技能名列表：运行时子代理只能 loadSkill 这些技能，其他成员不可见。 */
    val skills: List<String> = emptyList()
)

/**
 * 团队关系链：成员之间、阶段之间只有两种动作。
 *
 * - [EXECUTE] 执行：接下上游分配下来的任务并完成，产出交给下一个环节。
 * - [REJECT] 驳回：把上游刚交下来的产出打回，附上必改点，让上游重做后重新提交。
 *
 * 一个团队流程 = 若干「执行」节点串起来 + 关卡上的「驳回」回边。没有驳回边的流程是一根直线，
 * 有驳回边的流程才可能是闭环（审核不过关 → 回到拆解/设计/执行重做）。
 */
enum class TeamChain {
    EXECUTE, REJECT;

    companion object {
        fun fromToken(token: String?): TeamChain? = when (token?.trim()?.lowercase()) {
            "reject", "驳回", "deny" -> REJECT
            "execute", "执行", "run" -> EXECUTE
            else -> null
        }
    }
}

/**
 * 任务级接口边：声明「我要和谁对齐什么」。
 *
 * 它不是调度依赖——等谁跑完由阶段顺序决定；它的唯一作用是在展开时把协作对象注进 prompt，
 * 让成员不必从全员名册里自己猜该找谁。[with] 认三种写法：成员名、部门/关键词标签、逻辑 id。
 *
 * @param about 要对齐的内容，写具体（字段契约、错误码、状态机），空着就只剩一个名字。
 */
data class TeamInterface(
    val with: String,
    val about: String = ""
)

/**
 * 团队流程里的一个任务：交给某个成员去做。
 *
 * 选角支持两种写法：[agent] 精确点名成员，或 [department] + [agentQuery] 交给
 * [SubAgentProfileMatcher] 选角；两者都没有的任务在解析阶段就被丢弃。
 */
data class TeamTask(
    val agent: String? = null,
    val department: String? = null,
    val agentQuery: String? = null,
    val prompt: String,
    val writePaths: List<String> = emptyList(),
    val interfaces: List<TeamInterface> = emptyList()
)

/**
 * 编辑界面的接口边：从哪个成员（[from]）指向谁、对齐什么。
 *
 * 它是 [TeamInterface] 加了一个来源成员：[TeamInterface] 挂在任务上，文件里没有重复存储来源；
 * 编辑页按阶段展示「A → B」，所以表单层显式携带 [from]，保存时按 from 把边写回该成员的任务。
 */
data class TeamInterfaceEdgeForm(
    val from: String = "",
    val with: String = "",
    val about: String = ""
)

/**
 * 团队流程的一个阶段：阶段内任务并行，阶段之间串行（上一阶段全部完成后才开始下一阶段）。
 *
 * @param level 执行本阶段的成员组层级（1=一级成员组…），只用于展示与选人辅助
 * @param isReview 本阶段是不是关卡：审核/验收。是关卡时拒收就等于驳回，走 [rejectTo]
 * @param rejectTo 驳回目标阶段标题：本关不通过就打回那一段重做，null 表示终审（只能整体重来）
 */
enum class TeamCommunication(val token: String) {
    SAME_STAGE("same_stage"), PARENT_ONLY("parent_only"), RUN("run");

    companion object {
        fun fromToken(value: String?): TeamCommunication? = entries.firstOrNull { it.token == value }
    }
}

data class TeamStage(
    val title: String,
    val tasks: List<TeamTask>,
    val level: Int = 1,
    val isReview: Boolean = false,
    val rejectTo: String? = null,
    val communication: TeamCommunication = TeamCommunication.RUN,
    val messageLimit: Int = 8,
    val communicationGuide: String = ""
)

/**
 * 团队，来自 `teams/<name>.md`：frontmatter 是名单（成员 / 管理员）+ 流程，正文是团队公约。
 *
 * [charter] 既是团队的用途介绍，也是拼在每条成员指令前的共同约束——写一次，全队生效。
 * [admins] 是管理员组：多个管理员时同一阶段并行思考，最后由主代理汇总成策略建议。
 */
data class AgentTeam(
    val name: String,
    val description: String,
    val stages: List<TeamStage>,
    val charter: String,
    val admins: List<String> = emptyList(),
    val members: List<TeamMemberRef> = emptyList(),
    val filePath: String? = null
) {
    val taskCount: Int get() = stages.sumOf { it.tasks.size }

    /** 团队实际引用到的全部成员名（名单 + 管理员 + 阶段里点名的成员）。 */
    val referencedMembers: List<String>
        get() = buildList {
            addAll(members.map { it.name })
            addAll(admins)
            stages.forEach { stage -> stage.tasks.forEach { task -> task.agent?.let(::add) } }
        }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** 团队用到哪些层级（升序）；没有写层级时按一级看待。 */
    val levels: List<Int>
        get() = members.map { it.level }.distinct().ifEmpty { listOf(1) }.sorted()

    /** 有驳回边的阶段（标题 → 打回目标），空表示这个团队是一根直线。 */
    val rejectEdges: List<Pair<String, String>>
        get() = stages.mapNotNull { stage -> stage.rejectTo?.let { stage.title to it } }
}

/** 一个生效的团队：定义本体 + 来源作用域，供设置页标注「全局/项目」。 */
data class AgentTeamEntry(
    val team: AgentTeam,
    val scope: AgentDefinitionScope
)

/** 编辑器里的任务表单；[writePaths] 保留用户输入的逗号分隔形式，保存时才切分。 */
data class AgentTeamTaskForm(
    val agent: String = "",
    val department: String = "",
    val agentQuery: String = "",
    val prompt: String = "",
    val writePaths: String = "",
    /** 接口边：编辑界面还没有专门的输入控件，但回填与保存都要带着它，否则改一次团队就丢一次声明。 */
    val interfaces: List<TeamInterface> = emptyList()
)

/**
 * 编辑器里的阶段表单。
 *
 * [members] 是这个阶段出人干活的成员名单，[prompt] 是发给这些成员的阶段指令：保存时若一条任务
 * 都没填，就按「一个成员一条任务」把 [members] + [prompt] 展开成 tasks，界面不必逐条手写任务。
 */
data class AgentTeamStageForm(
    val title: String = "",
    val level: Int = 1,
    val members: List<String> = emptyList(),
    val prompt: String = "",
    val isReview: Boolean = false,
    val rejectTo: String = "",
    val tasks: List<AgentTeamTaskForm> = listOf(AgentTeamTaskForm()),
    /** 阶段级接口边：本阶段成员「和谁对齐什么」。保存时按 from 落到对应成员的任务上。 */
    val interfaces: List<TeamInterfaceEdgeForm> = emptyList(),
    val communication: TeamCommunication = TeamCommunication.SAME_STAGE,
    val messageLimit: Int = 8,
    val communicationGuide: String = ""
)

/** 团队的编辑表单；[charter] 是正文里的团队公约（也是用途介绍）。 */
data class AgentTeamForm(
    val name: String = "",
    val description: String = "",
    val scope: AgentDefinitionScope = AgentDefinitionScope.GLOBAL,
    val charter: String = "",
    val admins: List<String> = emptyList(),
    val members: List<TeamMemberRef> = emptyList(),
    val stages: List<AgentTeamStageForm> = listOf(AgentTeamStageForm())
)

/** 保存失败的原因；界面按枚举映射成对应文案。 */
enum class AgentTeamSaveError {
    /** 名称为空、过长或含不能做文件名的字符。 */
    INVALID_NAME,

    /** 一个有效阶段都没有。 */
    EMPTY_STAGES,

    /** 有任务缺任务说明，或者没写成员名也没写部门/关键词。 */
    INCOMPLETE_TASK,

    /** 同作用域已有同名团队。 */
    NAME_CONFLICT,

    /** 阶段引用了名单里没有的成员：名单是唯一真源，写错名字不能悄悄放过。 */
    UNKNOWN_MEMBER,

    /** 管理层里有人不在成员名单里。 */
    ADMIN_NOT_MEMBER,

    /** 驳回目标不存在，或指向自己之后的阶段（回边只能往回指）。 */
    BAD_REJECT_TARGET,

    /** 写盘失败。 */
    IO_FAILED
}

/**
 * 把已保存的团队回填成编辑表单。
 *
 * [admins] 字段来自 frontmatter，可能包含不在成员名单里的人名（历史遗留），直接透传给界面显示与编辑。
 */
fun AgentTeam.toForm(scope: AgentDefinitionScope): AgentTeamForm = AgentTeamForm(
    name = name,
    description = description,
    scope = scope,
    charter = charter,
    admins = admins,
    members = members,
    stages = stages.map { stage ->
        AgentTeamStageForm(
            title = stage.title,
            level = stage.level,
            members = stage.tasks.mapNotNull { it.agent },
            prompt = stage.tasks.map { it.prompt }.distinct().singleOrNull().orEmpty(),
            isReview = stage.isReview,
            rejectTo = stage.rejectTo.orEmpty(),
            communication = stage.communication,
            messageLimit = stage.messageLimit,
            communicationGuide = stage.communicationGuide,
            // 只还原点名成员任务的边；department/agentQuery 选角任务的 interfaces 不进表单，
            // 保存时这类任务随归一化重建，手写在上面的边会丢——要保留选角任务的边请直接改 .md 文件。
            interfaces = stage.tasks.flatMap { task ->
                task.agent?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { from -> task.interfaces.map { edge -> TeamInterfaceEdgeForm(from, edge.with, edge.about) } }
                    .orEmpty()
            },
            tasks = stage.tasks.map { task ->
                AgentTeamTaskForm(
                    agent = task.agent.orEmpty(),
                    department = task.department.orEmpty(),
                    agentQuery = task.agentQuery.orEmpty(),
                    prompt = task.prompt,
                    writePaths = task.writePaths.joinToString(", "),
                    interfaces = task.interfaces
                )
            }
        )
    }
)
