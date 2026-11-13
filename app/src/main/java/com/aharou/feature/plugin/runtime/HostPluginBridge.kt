package com.aharou.feature.plugin.runtime

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.vault.PrivacyRedaction
import com.aharou.feature.vault.VaultRedactor
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.permission.PermissionChoice
import com.aharou.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aharou.feature.agent.domain.receipt.ToolCallReceiptRepository
import com.aharou.feature.agent.domain.tool.PendingPermissionBatch
import com.aharou.feature.agent.domain.tool.ToolPermissionManager
import com.aharou.feature.agent.domain.tool.ToolRegistry
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.agent.domain.tool.toTransportString
import com.aharou.feature.agent.domain.workflow.PermissionGate
import com.aharou.feature.agent.domain.workflow.PermissionVerdict
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.plugin.api.PluginHost
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

@Singleton
class HostPluginBridge @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val registry: Lazy<ToolRegistry>,
    private val policy: ToolPermissionPolicyEngine,
    private val permissions: ToolPermissionManager,
    private val fileAccess: FileAccessProvider,
    private val receipts: ToolCallReceiptRepository,
) {
    fun createHost(root: File, invocationContext: () -> AgentContext?): PluginHost = object : PluginHost {
        override fun apiVersion() = 1
        override fun pluginDirectory() = root.absolutePath
        override fun dataDirectory() = File(root, "data").apply { mkdirs() }.absolutePath
        override fun applicationContext(): Any = context.applicationContext
        override fun call(operation: String, argumentsJson: String): String {
            val args = Json.parseToJsonElement(argumentsJson).jsonObject
            return when (operation) {
                "file.read" -> JsonPrimitive(resolve(args).readText(Charsets.UTF_8)).toString()
                "file.write" -> {
                    val file = resolve(args)
                    file.parentFile?.mkdirs()
                    file.writeText(args.getValue("content").jsonPrimitive.content, Charsets.UTF_8)
                    "null"
                }
                "log" -> {
                    val message = args.getValue("message").jsonPrimitive.content
                    // FileLogger's platform sink runs before its disk redactor, so redact before either sink.
                    val redacted = PrivacyRedaction.redact(VaultRedactor.redact(message), personalData = true)
                        .take(4096).replace('\r', ' ').replace('\n', ' ')
                    FileLogger.i("HostPlugin", "${root.name}: $redacted")
                    "null"
                }
                "tool.invoke" -> invokeTool(args.getValue("name").jsonPrimitive.content,
                    (args["arguments"] as? JsonObject).orEmpty(), requireNotNull(invocationContext()) { "tool.invoke requires an active tool invocation context" })
                "config.get", "config.set" -> {
                    val configArgs = args.toMutableMap()
                    configArgs["action"] = JsonPrimitive(operation.substringAfter('.'))
                    if (operation == "config.set") {
                        configArgs["value"] = JsonPrimitive(args.getValue("value").toString())
                    }
                    invokeTool("config", configArgs, requireNotNull(invocationContext()) { "config operations require an active tool invocation context" })
                }
                else -> error("Unknown host operation: $operation")
            }
        }
        private fun resolve(args: JsonObject): File {
            val path = args.getValue("path").jsonPrimitive.content
            require(path.isNotBlank()) { "File path is empty" }
            // Trusted plugins retain native filesystem capability; relative paths use persistent data/.
            return if (File(path).isAbsolute) File(path) else File(dataDirectory(), path)
        }
    }

    private fun invokeTool(name: String, args: Map<String, JsonElement>, context: AgentContext): String = runBlocking {
        val tool = registry.get().getDeferredTools(context).firstOrNull { it.name == name }
            ?: return@runBlocking ToolResult.Error("Tool is not available to this invocation", "TOOL_NOT_ALLOWED").toTransportString()
        val gate = PermissionGate(policy, permissions, fileAccess, receipts)
        val id = UUID.randomUUID().toString()
        val verdict = gate.evaluate(tool, id, args, JsonObject(args).toString(), context.mode,
            context.sessionId, context.projectRoot, context)
        when (verdict) {
            is PermissionVerdict.Deny -> ToolResult.Error(verdict.reason, verdict.code).toTransportString()
            PermissionVerdict.Allow -> tool.executeWithContext(args, context).toTransportString()
            is PermissionVerdict.Ask -> {
                val batch = PendingPermissionBatch(UUID.randomUUID().toString(), context.sessionId.orEmpty(), listOf(verdict.request))
                val choices = gate.awaitBatch(batch, listOf(verdict), context.projectRoot)
                if (choices[verdict.request.id] == null || choices[verdict.request.id] == PermissionChoice.REJECT) {
                    ToolResult.Error("Tool invocation was not approved", "PERMISSION_DENIED").toTransportString()
                } else tool.executeWithContext(args, context).toTransportString()
            }
        }
    }
}
