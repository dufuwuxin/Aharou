package com.aharou.feature.plugin.runtime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.aharou.core.config.FullAccessCapability
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.permission.TaintTracker
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolPermissionPolicy
import com.aharou.feature.agent.domain.tool.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

@Singleton
class AppControlTool @Inject constructor(@param:ApplicationContext private val application: Context) : AgentTool() {
    override val name = "app_control"
    override val description = "全部权限宿主接口，仅用户在特殊权限中启用后可用。同UID文件读取/乐观锁写入、宿主反射、重启；持久代码扩展沿现有插件SDK编译安装，重启重新加载。不能改只读签名APK或任意已加载DEX方法体，不获得root。read返回sha256，覆盖write必须带expected_sha256。反射句柄按会话和工作区隔离。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val deferredLoading = true
    override val capabilities = setOf(ToolCapability.EXTERNAL_TOOL)
    override val parameters = mapOf(
        "action" to ToolParameter("action", ParameterType.STRING, "操作", enum = listOf("status", "list", "read", "write", "reflect", "restart")),
        "path" to ToolParameter("path", ParameterType.STRING, "宿主绝对路径；不能使用容器路径别名", required = false),
        "content" to ToolParameter("content", ParameterType.STRING, "写入的UTF-8文本", required = false),
        "expected_sha256" to ToolParameter("expected_sha256", ParameterType.STRING, "覆盖现有文件前read返回的sha256；新文件省略", required = false),
        "operation" to ToolParameter("operation", ParameterType.STRING, "现有宿主桥reflect.context/class/new/invoke/get/set/release", required = false),
        "arguments" to ToolParameter("arguments", ParameterType.OBJECT, "宿主反射参数，沿用插件SDK协议", required = false),
    )
    private val reflections = linkedMapOf<Pair<String, String>, HostPluginReflection>()

    init {
        FullAccessCapability.initialize(application)
        FullAccessCapability.onRevoke { synchronized(reflections) { reflections.values.forEach { it.close() }; reflections.clear() } }
    }

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> =
        if (args["action"]?.jsonPrimitive?.content in setOf("status", "list", "read")) emptySet() else capabilities

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult = withContext(Dispatchers.IO) {
        if (!FullAccessCapability.enabled.value) return@withContext ToolResult.Error("Enable full access in Special permissions first", "FULL_ACCESS_DISABLED")
        fun text(key: String) = (args[key] as? JsonPrimitive)?.contentOrNull
        try {
            val revision = FullAccessCapability.currentRevision()
            check(FullAccessCapability.isGranted(revision)) { "Full access was revoked" }
            val result: JsonElement = when (text("action")) {
                    "status" -> buildJsonObject {
                        put("files", application.filesDir.absolutePath)
                        put("cache", application.cacheDir.absolutePath)
                        put("apk", application.applicationInfo.sourceDir)
                        put("uid", android.os.Process.myUid())
                        put("restart_required_after_persistent_code_changes", true)
                        put("extension", "Use the existing trusted host plugin SDK and installed-plugin lifecycle; signed APK is read-only")
                    }
                    "reflect" -> {
                        val session = requireNotNull(context.sessionId?.takeIf { it.isNotBlank() }) { "A session is required" }
                        val operation = requireNotNull(text("operation"))
                        require(operation.startsWith("reflect.")) { "Invalid reflection operation" }
                        val bridge = synchronized(reflections) {
                            check(FullAccessCapability.isGranted(revision)) { "Full access was revoked before acquiring the host" }
                            reflections.getOrPut(session to context.projectRoot) {
                                if (reflections.size >= 64) reflections.remove(reflections.keys.first())?.close()
                                HostPluginReflection { application.applicationContext }
                            }
                        }
                        Json.parseToJsonElement(bridge.call(operation, (args["arguments"] as? JsonObject) ?: JsonObject(emptyMap())))
                    }
                    "restart" -> {
                        val launch = requireNotNull(application.packageManager.getLaunchIntentForPackage(application.packageName))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        val pending = PendingIntent.getActivity(application, 7109, launch, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                        val alarms = application.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                        alarms.set(AlarmManager.RTC, System.currentTimeMillis() + 1500, pending)
                        Handler(Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 500)
                        buildJsonObject { put("restart_scheduled", true) }
                    }
                    "read", "write", "list" -> {
                        val file = File(requireNotNull(text("path"))).also { require(it.isAbsolute) { "Host path must be absolute" } }
                        when (text("action")) {
                            "list" -> buildJsonArray {
                                val files = requireNotNull(file.listFiles()) { "Directory is not readable" }
                                files.sortedBy { it.name }.take(500).forEach { entry -> add(buildJsonObject {
                                    put("path", entry.absolutePath); put("directory", entry.isDirectory); put("bytes", entry.length())
                                }) }
                            }
                            "read" -> {
                                val snapshot = HostFileEdits.read(file)
                                buildJsonObject { put("path", file.absolutePath); put("content", snapshot.text); put("sha256", snapshot.sha256); put("bytes", snapshot.bytes.size) }
                            }
                            else -> {
                                val content = requireNotNull(text("content"))
                                val snapshot = HostFileEdits.write(file, content, text("expected_sha256"))
                                buildJsonObject { put("path", file.absolutePath); put("sha256", snapshot.sha256); put("restart_required", true) }
                            }
                        }
                    }
                    else -> error("Unknown action")
            }
            check(FullAccessCapability.isGranted(revision)) { "Full access was revoked during execution" }
            if (text("action") in setOf("read", "list") ||
                text("action") == "reflect" && text("operation") !in setOf("reflect.set", "reflect.release")) {
                TaintTracker.taint(context.sessionId.orEmpty())
            }
            ToolResult.Success(result)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { ToolResult.Error(failure.message ?: failure.javaClass.simpleName, "APP_CONTROL_FAILED") }
    }

}
