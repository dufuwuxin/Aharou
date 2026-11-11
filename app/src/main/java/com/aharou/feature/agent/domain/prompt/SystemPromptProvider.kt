package com.aharou.feature.agent.domain.prompt

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.agent.domain.context.ContextLibraryPlanner
import com.aharou.feature.agent.domain.context.ContextLibraryRepository
import com.aharou.feature.agent.domain.context.ContextOrigin
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
) {
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

    fun build(agentContext: AgentContext, consumeInbox: Boolean = true): String {
        val member = isMemberSession(agentContext)
        return contextLibrary.withPromptSnapshot(agentContext, roleSession = member) {
            val native = contextLibrary.render(agentContext.projectRoot, agentContext.sessionId,
                ContextOrigin.MEMORY, memberName = if (member) agentContext.agentDefinition?.name ?: "member" else null,
                expand = { expandNative(it, agentContext, member) })
            native.also { injectedContextStore.record(agentContext.sessionId, it) }
        }
    }

    private fun expandNative(text: String, ctx: AgentContext, member: Boolean): String =
        PromptVariableExpander.expandNative(text) { key ->
            val value = when (key) {
                "SKILLS" -> skillRepository.listSkillsForAgent(ctx.agentDefinition, ctx.currentUserText,
                    ctx.agentDefinition?.let { InjectPart.SKILLS in it.inject } ?: true)
                    .joinToString("\n") { "- ${it.name}: ${it.description} [loadSkill: ${it.name}]" }
                "TOOLS" -> buildString {
                    append("完整目录：tool_search(query=\"\", offset=0)，只列名称与描述；选中后以工具名查询展开参数。\n")
                    for (tool in toolRegistry?.get()?.getDeferredTools(ctx).orEmpty()) {
                        val line = "- ${tool.name}: ${tool.description.lineSequence().joinToString(" ").take(60)}\n"
                        if (length + line.length > 600) break
                        append(line)
                    }
                }
                "TEAMS" -> if (member) "" else agentTeamRepository.listAll().joinToString("\n") {
                    "- ${it.team.name}: ${it.team.description}"
                }
                "RULES" -> ruleRepository.listRules().joinToString("\n") {
                    "- ${it.name}: ${it.description} [loadRule: ${it.name}]"
                }
                "PROJECT_RULES" -> projectRuleIndex(ctx.projectRoot)
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
