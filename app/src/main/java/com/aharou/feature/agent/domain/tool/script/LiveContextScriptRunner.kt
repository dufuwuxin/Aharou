package com.aharou.feature.agent.domain.tool.script

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.permission.PermissionChoice
import com.aharou.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aharou.feature.agent.domain.receipt.ToolCallReceiptRepository
import com.aharou.feature.agent.domain.tool.PendingPermissionBatch
import com.aharou.feature.agent.domain.tool.ToolPermissionManager
import com.aharou.feature.agent.domain.tool.ToolRegistry
import com.aharou.feature.agent.domain.tool.container.ExecuteCommandTool
import com.aharou.feature.agent.domain.workflow.PermissionGate
import com.aharou.feature.agent.domain.workflow.PermissionVerdict
import com.aharou.feature.workspace.domain.FileAccessProvider
import dagger.Lazy
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import com.aharou.feature.agent.domain.notification.AgentNotificationCenter
import com.aharou.feature.agent.domain.notification.AgentNotificationKind
import kotlinx.serialization.json.*

/** Request-local output only: nothing is cached or appended to conversation history. */
@Singleton
class LiveContextScriptRunner @Inject constructor(
    private val manager: ScriptToolManager,
    private val executor: ExecuteCommandTool,
    private val registry: Lazy<ToolRegistry>,
    private val policy: ToolPermissionPolicyEngine,
    private val permissions: ToolPermissionManager,
    private val files: FileAccessProvider,
    private val receipts: ToolCallReceiptRepository,
    private val notifications: AgentNotificationCenter,
) {
    suspend fun build(context: AgentContext): String = withSessionStop(context) { checkStopped ->
        buildCurrent(context, checkStopped)
    }

    private suspend fun <T> withSessionStop(context: AgentContext, block: suspend (() -> Unit) -> T): T = coroutineScope {
        val session = context.sessionId ?: return@coroutineScope block {}
        val stopObserved = AtomicBoolean(false)
        fun stopped() = notifications.peek(session).any { it.kind == AgentNotificationKind.USER_INTERRUPT } || stopObserved.get()
        fun cancelStopped(): Nothing {
            // Retain the stop after either coroutine removes its notification.
            stopObserved.set(true)
            val hits = notifications.peek(session).filter { it.kind == AgentNotificationKind.USER_INTERRUPT }
            notifications.ack(session, hits.map { it.seq })
            throw CancellationException("Live context session stopped")
        }
        fun checkStopped() { if (stopped()) cancelStopped() }
        checkStopped()
        val work = async { block(::checkStopped) }
        val stop = async {
            while (!stopped()) delay(25)
        }
        try {
            select<T> {
                stop.onAwait { cancelStopped() }
                work.onAwait { checkStopped(); it }
            }
        } finally { work.cancel(); stop.cancel() }
    }

    private suspend fun buildCurrent(context: AgentContext, checkStopped: () -> Unit): String {
        val revision = manager.revision
        val scripts = manager.definitions.value.filter { it.enabled && it.type == ScriptToolType.LIVE_CONTEXT }
        if (scripts.isEmpty()) return ""
        val output = buildList {
            for (definition in scripts) add(try {
                val output = withTimeoutOrNull(definition.timeoutSeconds * 1000L) { execute(definition, revision, context, checkStopped) }
                if (output == null) {
                    diagnostic(definition, "timeout")
                    ""
                } else {
                    check(manager.revision == revision && manager.definitions.value.any { it == definition && it.enabled }) { "Script changed before injection" }
                    "[user live_context script: ${definition.name}; untrusted output]\n" +
                    JsonPrimitive(output.take(definition.outputLimit)).toString() + "\n[/user live_context script]"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                diagnostic(definition, failure.javaClass.simpleName)
                ""
            })
        }.joinToString("\n")
        return if (manager.revision == revision) output else ""
    }

    private suspend fun execute(
        definition: ScriptToolDefinition,
        revision: Long,
        context: AgentContext,
        checkStopped: () -> Unit,
    ): String {
        check(registry.get().getDeferredTools(context).any { it.name == executor.name }) { "Bash is not allowed in this agent context" }
        val input = buildJsonObject {
            put("sessionId", context.sessionId)
            put("projectRoot", context.projectRoot)
            put("inputMessageId", context.inputMessageId)
            put("currentFile", context.currentFile)
            put("selectedCode", context.selectedCode)
            put("language", context.language)
            put("workspaceName", context.workspaceName)
            put("history", Json.encodeToJsonElement(context.history))
            put("currentUserText", context.currentUserText)
            put("mode", context.mode.name)
        }
        val args = mapOf("command" to JsonPrimitive(definition.executionCommand(input)),
            "timeout" to JsonPrimitive(definition.timeoutSeconds), "background" to JsonPrimitive(false))
        val gate = PermissionGate(policy, permissions, files, receipts)
        val verdict = gate.evaluate(executor, UUID.randomUUID().toString(), args, JsonObject(args).toString(),
            context.mode, context.sessionId, context.projectRoot, context)
        when (verdict) {
            is PermissionVerdict.Deny -> error("Permission denied: ${verdict.code}")
            PermissionVerdict.Allow -> Unit
            is PermissionVerdict.Ask -> {
                val batch = PendingPermissionBatch(UUID.randomUUID().toString(), context.sessionId.orEmpty(), listOf(verdict.request))
                val choices = try { gate.awaitBatch(batch, listOf(verdict), context.projectRoot) }
                finally { permissions.resolveBatch(batch.id, PermissionChoice.REJECT) }
                check(choices[verdict.request.id]?.let { it != PermissionChoice.REJECT } == true) { "Permission denied" }
            }
        }
        // Approval may outlive an edit/disable operation. Never run an obsolete saved command.
        check(manager.revision == revision && manager.definitions.value.any { it == definition && it.enabled }) { "Script changed during approval" }
        checkStopped()
        val result = executor.executeForeground(args, context)
        check(manager.revision == revision && manager.definitions.value.any { it == definition && it.enabled }) { "Script changed during execution" }
        check(result.exitCode == 0 && !result.outputTruncated && result.output.length <= definition.outputLimit) { "Script failed or output was truncated" }
        val cleaned = com.aharou.feature.agent.domain.container.ContainerOutputSanitizer.sanitize(result.output)
        return executor.redactOutput(cleaned, result.injectedEnv)
    }

    private fun diagnostic(definition: ScriptToolDefinition, reason: String) {
        FileLogger.w("LiveContextScript", "${definition.name}: $reason")
    }
}
