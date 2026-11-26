package com.aharou.feature.agent.domain.prompt

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.agent.domain.context.ContextLibraryPlanner
import com.aharou.feature.agent.domain.context.ContextLibraryRepository
import com.aharou.feature.agent.domain.context.ProjectIndex
import com.aharou.feature.agent.domain.inbox.SharedInbox
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.rule.RuleRepository
import com.aharou.feature.agent.domain.skill.SkillRepository
import com.aharou.feature.agent.domain.subagent.AgentDefinition
import com.aharou.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aharou.feature.agent.domain.subagent.AgentTeamRepository
import com.aharou.feature.agent.domain.subagent.InjectPart
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

data class RequestPrompt(val system: String, val transientContext: String = "")

/** Host-owned invariant: multi-stage work must use the configured team pipeline, never an ad-hoc direct role dispatch. */
internal val MAIN_AGENT_TEAM_DISPATCH_CONTRACT = """
主代理团队执行硬约束：凡需多个阶段或多个专业职责的实现、修复、调研、审查或验收任务，必须先调用 task(action="teams") 选择已配置且可运行的团队，再调用 task(action="team_run", team=…, goal=…)；不得直接把用户原文交给单个角色，不得跳过团队阶段。goal 仅作共同背景；首阶段按团队自己的分解提示拆出可验收的专属任务，后续阶段只依赖团队任务提示与上游交付，不复述整段原始需求。若 teams 返回 TEAM_LIST_EMPTY、工具不可用或 team_run 未完成，必须停止宣称执行中/已完成，明确报告阻塞与实际错误；禁止退化为直接派角色或谎称已分工。简单原子请求可由主代理直接处理。成员会话不适用本约束。
""".trimIndent()

/** Application-managed prompt text is read only from editable native memory entries. */
@Singleton
@Suppress("unused") // Keep the public injection constructor compatible with existing consumers.
class SystemPromptProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val skillRepository: SkillRepository,
    private val promptFileResolver: PromptFileResolver,
    private val containerInstaller: ContainerInstaller,
    private val agentDefinitionRepository: AgentDefinitionRepository,
    private val agentTeamRepository: AgentTeamRepository,
    private val ruleRepository: RuleRepository,
    private val chatSessionDao: ChatSessionDao,
    private val sharedInbox: SharedInbox,
    private val containerProfileResolver: com.aharou.feature.agent.domain.container.ContainerProfileResolver,
    private val projectIndex: ProjectIndex,
    private val projectTaskContext: ProjectTaskContext,
    private val injectedContextStore: InjectedContextStore,
    private val contextLibrary: ContextLibraryRepository,
    private val toolRegistry: dagger.Lazy<com.aharou.feature.agent.domain.tool.ToolRegistry>? = null,
    private val liveContextScripts: dagger.Lazy<com.aharou.feature.agent.domain.tool.script.LiveContextScriptRunner>? = null,
    private val hostPlugins: dagger.Lazy<com.aharou.feature.plugin.runtime.HostPluginRuntime>? = null,
) {
    suspend fun buildForRequest(agentContext: AgentContext, native: String): String {
        val parts = buildRequest(agentContext, native)
        return listOf(parts.system, parts.transientContext).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    suspend fun buildRequest(agentContext: AgentContext, native: String, nativeTransientContext: String = ""): RequestPrompt {
        val live = listOf(nativeTransientContext, liveContextScripts?.get()?.build(agentContext).orEmpty())
            .filter { it.isNotBlank() }.joinToString("\n\n")
        val current = listOf(native, live).filter { it.isNotBlank() }.joinToString("\n\n")
        val hooked = hostPlugins?.get()?.onContext(agentContext, current) ?: current
        // A plugin may intentionally replace the whole prompt; retain that existing contract.
        return if (hooked != current) RequestPrompt(hooked) else RequestPrompt(native, live)
    }

    interface PromptSource { fun build(ctx: AgentContext): String? }

    fun findAgentDefinitionIncludingDisabled(name: String): AgentDefinition? =
        agentDefinitionRepository.findIncludingDisabled(name)

    private fun isMemberSession(ctx: AgentContext): Boolean {
        if (ctx.agentDefinition != null) return true
        val id = ctx.sessionId?.takeIf(String::isNotBlank) ?: return false
        return try {
            runBlocking { chatSessionDao.getById(id)?.parentId != null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w("SystemPromptProvider", "Cannot determine prompt audience", e)
            true // An unavailable parent relation must not expose main-agent memory to a member.
        }
    }

    fun buildNativeRequest(agentContext: AgentContext): RequestPrompt {
        val parts = buildRequestParts(agentContext)
        val system = if (isMemberSession(agentContext)) parts.system else parts.system + "\n\n" + MAIN_AGENT_TEAM_DISPATCH_CONTRACT
        injectedContextStore.record(agentContext.sessionId, system)
        return RequestPrompt(system, parts.data)
    }

    fun build(agentContext: AgentContext, consumeInbox: Boolean = true): String {
        val parts = buildRequestParts(agentContext)
        return parts.system.also { injectedContextStore.record(agentContext.sessionId, it) }
    }

    fun buildContextData(agentContext: AgentContext): String = buildRequestParts(agentContext).data

    private fun buildRequestParts(agentContext: AgentContext): com.aharou.feature.agent.domain.context.ContextPromptParts {
        val member = isMemberSession(agentContext)
        return contextLibrary.withPromptSnapshot(agentContext, roleSession = member) {
            contextLibrary.requestParts(agentContext.projectRoot, agentContext.sessionId,
                memberName = if (member) agentContext.agentDefinition?.name ?: "member" else null,
                expand = { expandNative(it, agentContext, member) },
                stableExpansion = { expandStable(it, agentContext) })
        }
    }

    /** Only true execution invariants belong in the system prefix; never freeze role/policy decisions. */
    private fun expandStable(text: String, ctx: AgentContext): String = PromptVariableExpander.expandNative(text) { key ->
        val value = when (key) {
            "WORKSPACE" -> ctx.projectRoot
            "WORKSPACE_NAME" -> ctx.workspaceName.orEmpty()
            "ENVIRONMENT" -> containerProfileResolver.forWorkspace(ctx.projectRoot).mode.name
            "PROJECT_TASK_CONTEXT" -> promptFileResolver.resolve("agent/project-task-context-rules.md")
            else -> ""
        }
        ContextLibraryPlanner.safe(value)
    }

    private fun expandNative(text: String, ctx: AgentContext, member: Boolean): String =
        PromptVariableExpander.expandNative(text) { key ->
            val value = when (key) {
                "SKILLS" -> skillRepository.listSkillsForAgent(ctx.agentDefinition, ctx.currentUserText,
                    ctx.agentDefinition?.let { InjectPart.SKILLS in it.inject } ?: true)
                    .groupBy { it.selectedTypeIds.firstOrNull() ?: "untyped" }
                    .toSortedMap().entries.joinToString("\n") { (type, skills) ->
                        "skills/${directoryMetadata(type)} (untrusted catalog metadata)\n" +
                            skills.sortedBy { it.name }.joinToString("\n") { "  - ${directoryMetadata(it.name)}: ${directoryMetadata(it.description)} [tags: ${directoryMetadata(it.tags.sorted().joinToString(", "))}] [types: ${directoryMetadata(it.selectedTypeIds.joinToString(", "))}] [loadSkill: ${directoryMetadata(it.name)}]" }
                    }
                "TOOLS" -> buildString {
                    val groups = toolRegistry?.get()?.getDeferredTools(ctx).orEmpty()
                        .groupBy { com.aharou.feature.agent.domain.tool.executionToolCategory(it.name) }.toSortedMap()
                    for ((category, tools) in groups) {
                        val header = "tools/$category (catalog metadata)\n"
                        if (length + header.length > 600) break
                        append(header)
                        for (tool in tools.sortedBy { it.name }) {
                            val line = "  - ${directoryMetadata(tool.name)}: ${directoryMetadata(tool.description).take(60)}\n"
                            if (length + line.length > 600) break
                            append(line)
                        }
                    }
                }
                "TEAMS" -> if (member) "" else agentTeamRepository.forWorkspace(ctx.projectRoot).listAll().sortedBy { it.team.name }
                    .takeIf { it.isNotEmpty() }?.joinToString("\n", prefix = "teams/ (untrusted public catalog metadata)\n") {
                        "  - ${directoryMetadata(it.team.name)}: ${directoryMetadata(it.team.description)}; charter=${directoryMetadata(it.team.charter.take(240))}"
                    }.orEmpty()
                "RULES" -> ruleRepository.listRules().groupBy { it.name.substringBeforeLast('/', "root") }
                    .toSortedMap().entries.joinToString("\n") { (node, rules) ->
                        "rules/${directoryMetadata(node)} (catalog metadata)\n" + rules.sortedBy { it.name }.joinToString("\n") {
                            "  - ${directoryMetadata(it.name)}: ${directoryMetadata(it.description)} [loadRule: ${directoryMetadata(it.name)}]"
                        }
                    }
                "PROJECT_RULES" -> projectRuleIndex(ctx.projectRoot)
                "PROJECT_TASK_CONTEXT" -> projectTaskContext.build(ctx.sessionId, ctx.projectRoot)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { facts ->
                        promptFileResolver.resolve("agent/project-task-context-rules.md")
                            .takeIf { it.isNotBlank() }?.let { "$it\n\n$facts" }
                    }.orEmpty()
                "WORKSPACE" -> ctx.projectRoot
                "WORKSPACE_NAME" -> ctx.workspaceName.orEmpty()
                "WORKSPACE_TYPES" -> if (ctx.projectRoot.isBlank()) "" else WorkspaceTypeDetector.detect(File(ctx.projectRoot)).joinToString(", ")
                "DATE" -> java.time.LocalDate.now().toString()
                "MODEL" -> ctx.modelIdentity?.modelId.orEmpty()
                "PROVIDER" -> ctx.modelIdentity?.providerId.orEmpty()
                "CONTEXT_TOKENS" -> ctx.modelIdentity?.contextTokens?.takeIf { it > 0 }?.toString().orEmpty()
                "ENVIRONMENT" -> containerProfileResolver.forWorkspace(ctx.projectRoot).mode.name
                // Old member-list and recursive memory markers intentionally have no data provider.
                else -> ""
            }
            ContextLibraryPlanner.safe(value)
        }

    private fun directoryMetadata(text: String): String = ContextLibraryPlanner.safe(text)
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
        .replace("<", "\\u003c").replace(">", "\\u003e").filterNot { it.isISOControl() }

    private fun projectRuleIndex(root: String): String {
        if (root.isBlank()) return ""
        val dir = File(root)
        val files = listOf("AGENTS.md", "CLAUDE.md", "rules.md").map { File(dir, it) } +
            File(dir, "rules.d").listFiles().orEmpty().filter { it.extension == "md" }.sortedBy { it.name }
        return files.filter { it.isFile }.joinToString("\n") { "- ${it.absolutePath}" }
    }

    /** No body cache: editing, disabling or deleting a native request entry affects the next call. */
    fun resolvePrompt(name: String): String = promptFileResolver.resolve(name)

    fun invalidateMemoryCache(sessionId: String?, projectRoot: String?) = Unit
}

internal object WorkspaceTypeDetector {
    fun detect(root: File): List<String> = buildList {
        if (has(root, "settings.gradle.kts", "settings.gradle")) add("android")
        if (has(root, "Cargo.toml") || root.listFiles().orEmpty().any { it.isDirectory && has(it, "Cargo.toml") }) add("rust")
        if (has(root, "pyproject.toml", "requirements.txt", "setup.py")) add("python")
    }

    private fun has(dir: File, vararg names: String) = names.any { File(dir, it).isFile }
}
