package com.aharou.feature.agent.domain.subagent

import com.aharou.core.util.FileLogger
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.WorkspacePathMapper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 团队预设仓库，聚合全局与项目级两级目录；同名预设项目级覆盖全局（与子代理定义、技能一致）。
 *
 * 全局 `~/.aharou/teams`、项目级 `<workspace>/.aharou/teams`，都以容器路径经 [FileAccessProvider] 访问，
 * 跟随当前执行环境（本地宿主目录或远程 SSH 工作区）。
 */
@Singleton
class AgentTeamRepository @Inject constructor(
    private val fileAccess: FileAccessProvider
) {
    val globalRoot: String = "~/.aharou/teams"
    val projectRoot: String = "${WorkspacePathMapper.CONTAINER_ROOT}/.aharou/teams"

    /** 全部预设（含来源作用域），按名称排序。 */
    fun listAll(): List<AgentTeamEntry> = mergeAll(scan(globalRoot), scan(projectRoot))

    /** 按名称查找（忽略大小写）。 */
    fun find(name: String): AgentTeam? {
        val target = name.trim()
        if (target.isEmpty()) return null
        return listAll().firstOrNull { it.team.name.equals(target, ignoreCase = true) }?.team
    }

    /** 删除指定作用域的预设文件，不可恢复。返回是否成功。 */
    fun delete(name: String, scope: AgentDefinitionScope): Boolean {
        val entry = listAll().firstOrNull {
            it.scope == scope && it.team.name.equals(name.trim(), ignoreCase = true)
        } ?: return false
        val filePath = entry.team.filePath ?: return false
        if (!fileAccess.isFile(filePath)) return false
        return runCatching { fileAccess.delete(filePath); true }.getOrDefault(false)
    }

    /** 指定作用域的预设目录（容器路径）。 */
    fun teamsRoot(scope: AgentDefinitionScope): String =
        if (scope == AgentDefinitionScope.GLOBAL) globalRoot else projectRoot

    /**
     * 写入预设文件（新建或编辑）。[originalName] 为编辑前的名称，新建时传 null；
     * 改了名就写新文件再删旧文件，等价于重命名。返回 null 表示成功。
     */
    fun save(form: AgentTeamForm, originalName: String? = null): AgentTeamSaveError? {
        val name = form.name.trim()
        if (!AgentDefinitionRepository.isValidName(name)) return AgentTeamSaveError.INVALID_NAME

        if (form.admins.size != 1) return AgentTeamSaveError.INVALID_FLOW

        // 名单是唯一真源：阶段点名的成员必须在名单里，否则主代理看到的名单和实际调用会对不上。
        val roster = form.members.map { it.name.trim() }.filter { it.isNotEmpty() }
        val rosterLower = roster.map { it.lowercase() }.toSet()
        form.admins.map { it.trim() }.filter { it.isNotEmpty() }.forEach { admin ->
            if (admin.lowercase() !in rosterLower) return AgentTeamSaveError.ADMIN_NOT_MEMBER
        }
        form.stages.forEach { stage ->
            stage.members.map { it.trim() }.filter { it.isNotEmpty() }.forEach { member ->
                if (member.lowercase() !in rosterLower) return AgentTeamSaveError.UNKNOWN_MEMBER
            }
            stage.tasks.forEach { task ->
                val agent = task.agent.trim()
                if (agent.isNotEmpty() && agent.lowercase() !in rosterLower) {
                    return AgentTeamSaveError.UNKNOWN_MEMBER
                }
            }
        }
        // 驳回边只能往回指：指到不存在的阶段或自己之后的阶段，执行时就变成死循环或空等。
        val stageTitles = form.stages.map { it.title.trim() }.filter { it.isNotEmpty() }
        form.stages.forEachIndexed { index, stage ->
            val target = stage.rejectTo.trim()
            if (target.isEmpty()) return@forEachIndexed
            val targetIndex = stageTitles.indexOfFirst { it.equals(target, ignoreCase = true) }
            if (targetIndex < 0 || targetIndex >= index) return AgentTeamSaveError.BAD_REJECT_TARGET
        }

        // 成员名单 + 阶段指令 → 一人一条任务；界面只填名单与共享指令，不手写 tasks。
        // 两边都有时以名单为准（改名单就得真的改执行人），只在没写阶段指令时才回退到旧 tasks。
        // 接口边以阶段级声明为准，按 from 落到对应成员的任务；from 不在本阶段名单里的边随名单一起丢弃。
        // stageEdges 去重：手写脏数据（同一成员多条任务各带边）会回填出重复阶段边，不去重会固化落盘。
        val normalized = form.copy(stages = form.stages.map { stage ->
            val members = stage.members.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
            val prompt = stage.prompt.trim()
            val keepTasks = stage.tasks.filter { it.prompt.isNotBlank() }
            val stageEdges = stage.interfaces.filter { it.from.isNotBlank() && it.with.isNotBlank() }
                .distinctBy { it.from.lowercase() to it.with.lowercase() }
            when {
                prompt.isNotEmpty() && members.isNotEmpty() ->
                    // 同一成员多条任务只保留第一条：界面口径是一人一条任务，手写重名任务被归并
                    members.map { member ->
                        keepTasks.filter { it.agent.equals(member, ignoreCase = true) }
                            .firstOrNull()
                            ?.copy(prompt = prompt)
                            ?: AgentTeamTaskForm(agent = member, prompt = prompt)
                    } + keepTasks.filter { it.agent.isBlank() }
                else -> keepTasks
            }.let { tasks ->
                stage.copy(members = members, tasks = tasks.map { task ->
                    if (task.agent.isBlank()) task else task.copy(
                        interfaces = stageEdges.filter { it.from.equals(task.agent, ignoreCase = true) }
                            .map { TeamInterface(it.with.trim(), it.about.trim()) }
                    )
                })
            }
        })

        val validStages = normalized.stages.filter { it.tasks.isNotEmpty() }
        if (validStages.isEmpty() || normalized.stages.any { it.tasks.isEmpty() }) return AgentTeamSaveError.EMPTY_STAGES
        normalized.stages.forEach { stage ->
            stage.tasks.forEach { task ->
                val hasMember = task.agent.isNotBlank() || task.department.isNotBlank() || task.agentQuery.isNotBlank()
                if (task.prompt.isBlank() || !hasMember) return AgentTeamSaveError.INCOMPLETE_TASK
            }
        }

        val flowTeam = AgentTeam(name, normalized.description, normalized.stages.map { stage ->
            TeamStage(stage.title, stage.tasks.map { task -> TeamTask(agent = task.agent, prompt = task.prompt, id = task.id) },
                isReview = stage.isReview, id = stage.id)
        }, normalized.charter, normalized.admins, normalized.members, routes = normalized.routes)
        if (TeamFlowGraph.validate(flowTeam) != null) return AgentTeamSaveError.INVALID_FLOW

        val scope = normalized.scope
        // 作用域是文件所在目录，编辑时不允许换：改名但换了目录等于搬文件，界面已按新建时锁住。
        val overwritingSelf = originalName != null && originalName.equals(name, ignoreCase = true)
        if (!overwritingSelf) {
            val taken = listAll().any {
                it.scope == scope && it.team.name.equals(name, ignoreCase = true)
            }
            if (taken) return AgentTeamSaveError.NAME_CONFLICT
        }

        val existingFile = originalName?.let { old ->
            listAll().firstOrNull {
                it.scope == scope && it.team.name.equals(old, ignoreCase = true)
            }?.team?.filePath
        }

        return try {
            val root = teamsRoot(scope).trimEnd('/')
            // 名字未改时写回原文件，不能按 name 重拼：文件名可能与 frontmatter 的 name 大小写不同。
            val target = existingFile?.takeIf { overwritingSelf && fileAccess.isFile(it) }
                ?: "$root/$name.md"
            fileAccess.writeFile(target, AgentTeamParser.serialize(normalized), overwrite = true)
            if (!overwritingSelf && existingFile != null && existingFile != target && fileAccess.isFile(existingFile)) {
                runCatching { fileAccess.delete(existingFile) }.onFailure {
                    FileLogger.w(TAG, "重命名后删除旧团队预设失败: $existingFile")
                }
            }
            null
        } catch (e: Exception) {
            FileLogger.e(TAG, "保存团队预设失败: $name", e)
            AgentTeamSaveError.IO_FAILED
        }
    }

    /**
     * 目录扫描：只取顶层 `*.md`；目录不存在或不可读（远程断连）降级为「没有预设」，
     * 不让 IOException 冒泡成未捕获异常。
     */
    private fun scan(root: String): List<AgentTeam> = runCatching {
        if (!fileAccess.isDirectory(root)) {
            emptyList()
        } else {
            val base = root.trimEnd('/')
            fileAccess.listFiles(root)
                .filter { !it.isDirectory && it.name.endsWith(".md", ignoreCase = true) }
                .mapNotNull { entry -> AgentTeamParser.parse(fileAccess, "$base/${entry.name}") }
                .sortedBy { it.name.lowercase() }
        }
    }.getOrElse { e ->
        FileLogger.w(TAG, "扫描团队预设目录失败（$root）：${e.message}")
        emptyList()
    }

    companion object {
        private const val TAG = "AgentTeamRepository"

        /** 合并两级来源：同名项目级覆盖全局，按名称排序。 */
        internal fun mergeAll(
            global: List<AgentTeam>,
            project: List<AgentTeam>
        ): List<AgentTeamEntry> {
            val byName = LinkedHashMap<String, AgentTeamEntry>()
            global.forEach { byName[it.name.lowercase()] = AgentTeamEntry(it, AgentDefinitionScope.GLOBAL) }
            project.forEach { byName[it.name.lowercase()] = AgentTeamEntry(it, AgentDefinitionScope.PROJECT) }
            return byName.values.sortedBy { it.team.name.lowercase() }
        }
    }
}
