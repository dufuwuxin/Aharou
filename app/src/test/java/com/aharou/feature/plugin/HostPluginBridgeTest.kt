package com.aharou.feature.plugin

import android.content.Context
import com.aharou.feature.plugin.runtime.HostPluginBridge
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.tool.*
import com.aharou.feature.agent.domain.permission.ToolPermissionPolicyEngine
import dagger.Lazy
import io.mockk.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.aharou.core.config.*
import com.aharou.core.util.FileLogger
import com.aharou.feature.plugin.data.ConfigPluginFields
import com.aharou.feature.plugin.data.PluginRecord
import com.aharou.feature.plugin.data.PluginRecordStore
import com.aharou.feature.plugin.domain.PluginManager
import com.aharou.feature.plugin.domain.model.HostRuntimeSpec
import com.aharou.feature.plugin.domain.model.PluginManifest
import com.aharou.feature.plugin.runtime.HostPluginCompiler
import com.aharou.feature.plugin.runtime.HostPluginLoader
import com.aharou.feature.plugin.runtime.HostPluginRuntime
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.agent.domain.container.LinuxContainerEngine
import com.aharou.plugin.api.AharouPlugin
import com.aharou.plugin.api.PluginHost
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HostPluginBridgeTest {
    @get:Rule val temp = TemporaryFolder()
    private val registry = ToolRegistry()
    private val policy = mockk<ToolPermissionPolicyEngine>()
    private val bridge = HostPluginBridge(mockk<Context>(), Lazy { registry }, policy, ToolPermissionManager(), mockk(), mockk())

    @Test fun hostFileOperationsUseRealPersistentDataDirectory() {
        val host = bridge.createHost(temp.root) { null }
        host.call("file.write", "{\"path\":\"hello.txt\",\"content\":\"fixture\"}")
        assertEquals("\"fixture\"", host.call("file.read", "{\"path\":\"hello.txt\"}"))
        assertTrue(java.io.File(host.dataDirectory(), "hello.txt").isFile)
    }

    @Test fun missingContextOrRoleSnapshotCannotInvokeGlobalTools() {
        val context = AgentContext(null, null, temp.root.path, null)
        val host = bridge.createHost(temp.root) { context }
        val response = host.call("tool.invoke", "{\"name\":\"Bash\",\"arguments\":{}}")
        assertTrue(response.contains("TOOL_NOT_ALLOWED"))
        val startupHost = bridge.createHost(temp.root) { null }
        assertTrue(runCatching { startupHost.call("tool.invoke", "{\"name\":\"Bash\"}") }.isFailure)
        coVerify(exactly = 0) { policy.evaluate(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun allowedSnapshotStillRunsPermissionPolicyAndContextualTool() {
        val context = AgentContext(null, null, temp.root.path, null)
        val tool = mockk<AgentTool>()
        every { tool.name } returns "fixture"
        every { tool.effectivePermissionPolicy(any()) } returns ToolPermissionPolicy.AUTO_APPROVE
        coEvery { policy.evaluate(any(), any(), any(), any(), any(), any()) } returns
            ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.ALLOW, emptyList())
        coEvery { tool.executeWithContext(any(), context) } returns ToolResult.Success(JsonPrimitive("called"))
        registry.bindDiscoveryTools(listOf(tool), context)
        val host = bridge.createHost(temp.root) { context }
        assertTrue(host.call("tool.invoke", "{\"name\":\"fixture\",\"arguments\":{}}").contains("called"))
        coVerify(exactly = 1) { tool.executeWithContext(any(), context) }
        coVerify(exactly = 1) { policy.evaluate(tool, "fixture", any(), context.mode, context.projectRoot, context.sessionId) }
    }
    @Test fun selfConfigDisableReloadAndCompileRevokeWithoutWaitingForOwnCallback() = runBlocking {
        mockkObject(FileLogger)
        every { FileLogger.i(any(), any()) } just Runs
        every { FileLogger.d(any(), any()) } just Runs
        every { FileLogger.w(any(), any(), any()) } just Runs
        every { FileLogger.e(any(), any(), any()) } just Runs
        val executor = Executors.newCachedThreadPool { r -> Thread(r, "self-config-fixture").apply { isDaemon = true } }
        try {
            for (action in listOf("disable", "reload", "compile")) {
                val app = mockk<Context>()
                every { app.getString(any()) } returns "fixture field"
                val installer = mockk<ContainerInstaller>()
                val home = temp.newFolder(action)
                every { installer.aharouDir } returns home
                val root = File(home, "plugins/fixture").apply { mkdirs() }
                File(root, "payload").mkdirs()
                File(root, "payload/plugin.js").writeText("fixture")
                val manifest = PluginManifest(id = "fixture", name = "Fixture", version = "1",
                    hostRuntime = HostRuntimeSpec("js", "plugin.js", compileCommand = "fake-compiler"))
                File(root, "manifest.json").writeText(Json.encodeToString(manifest))
                val records = PluginRecordStore(installer)
                records.save(PluginRecord("fixture", "Fixture", "1", 1L, enabled = true))
                val tools = ToolRegistry()
                val loader = mockk<HostPluginLoader>(relaxed = true)
                val compiler = mockk<HostPluginCompiler>()
                lateinit var hostBridge: HostPluginBridge
                val runtime = HostPluginRuntime(loader, Lazy { hostBridge }, Lazy { tools })
                val manager = PluginManager(mockk<LinuxContainerEngine>(), installer, records, mockk(), runtime, compiler)
                val configFields = ConfigPluginFields(app, manager, records)
                val collection = slot<ConfigCollection>()
                val configRegistry = mockk<ConfigRegistry>()
                every { configRegistry.register(capture(collection)) } just Runs
                configFields.registerInto(configRegistry)
                val leaf = if (action == "compile") "compile" else "enabled"
                val field = collection.captured.fields("fixture").first { it.path == "plugins.fixture.$leaf" }
                val context = AgentContext(null, null, home.path, null)
                val configTool = mockk<AgentTool>()
                every { configTool.name } returns "config"
                every { configTool.effectivePermissionPolicy(any()) } returns ToolPermissionPolicy.AUTO_APPROVE
                coEvery { policy.evaluate(any(), any(), any(), any(), any(), any()) } returns
                    ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.ALLOW, emptyList())
                coEvery { configTool.executeWithContext(any(), context) } coAnswers {
                    field.write(ConfigValue.Bool(action != "disable"))
                    ToolResult.Success(JsonNull)
                }
                tools.bindDiscoveryTools(listOf(configTool), context)
                hostBridge = HostPluginBridge(app, Lazy { tools }, policy, ToolPermissionManager(), mockk(), mockk())
                val cleanups = AtomicInteger()
                val cleaned = CompletableDeferred<Unit>()
                every { loader.load(any(), any()) } answers {
                    object : AharouPlugin {
                        private lateinit var host: PluginHost
                        override fun onLoad(host: PluginHost) { this.host = host }
                        override fun toolsJson() = """[{"name":"self","parameters":{"type":"object","properties":{}}}]"""
                        override fun executeTool(name: String, argumentsJson: String, contextJson: String): String {
                            val before = runtime.generations.value["fixture"]
                            // Bounded off-thread wait also makes a regressed monitor implementation fail without hanging the suite.
                            val response = executor.submit<String> {
                                host.call("config.set", """{"path":"plugins.fixture.$leaf","value":${action != "disable"}}""")
                            }.get(2, TimeUnit.SECONDS)
                            check(cleanups.get() == 0)
                            check(runtime.generations.value["fixture"] != before)
                            return response
                        }
                        override fun onUnload() { cleanups.incrementAndGet(); cleaned.complete(Unit) }
                    }
                }
                if (action == "compile") {
                    coEvery { compiler.compile(root, manifest) } answers {
                        val stage = File(home, "plugins/.compiled").apply { mkdirs() }
                        File(root, "manifest.json").copyTo(File(stage, "manifest.json"))
                        File(root, "payload").copyRecursively(File(stage, "payload"))
                        stage
                    }
                }
                runtime.load(manifest, root)
                val old = tools.getTool(runtime.toolName("fixture", "self"))!!
                try {
                    val result = withTimeout(4000) { old.executeWithContext(emptyMap(), context) }
                    assertTrue("$action must finish the current tool", result is ToolResult.Success)
                    withTimeout(2000) { cleaned.await() }
                    assertEquals(action != "disable", runtime.isLoaded("fixture"))
                    assertEquals(action != "disable", records.get("fixture")!!.enabled)
                    if (action == "compile") coVerify(exactly = 1) { compiler.compile(root, manifest) }
                } finally { runtime.unload("fixture") }
            }
        } finally { executor.shutdownNow(); unmockkObject(FileLogger) }
    }

    @Test fun pluginLogConsumesBoundedMessageOnlyAfterRedaction() {
        mockkObject(FileLogger)
        val logged = slot<String>()
        every { FileLogger.i("HostPlugin", capture(logged)) } just Runs
        val previous = com.aharou.feature.vault.VaultRedactor.delegate
        com.aharou.feature.vault.VaultRedactor.delegate = { it.replace("SENSITIVE_FIXTURE_VALUE", "[REDACTED:FIXTURE]") }
        try {
            val message = "plugin loaded SENSITIVE_FIXTURE_VALUE\nAuthorization: Bearer " + "z".repeat(30) + " " + "x".repeat(5000)
            val host = bridge.createHost(temp.root) { null }
            host.call("log", buildJsonObject { put("message", message) }.toString())
            assertTrue(logged.captured.contains("plugin loaded"))
            assertTrue(logged.captured.contains("[REDACTED:FIXTURE]"))
            assertFalse(logged.captured.contains("SENSITIVE_FIXTURE_VALUE"))
            assertFalse(logged.captured.contains("z".repeat(30)))
            assertFalse(logged.captured.contains('\n'))
            assertTrue(logged.captured.length <= temp.root.name.length + 2 + 4096)
        } finally {
            com.aharou.feature.vault.VaultRedactor.delegate = previous
            unmockkObject(FileLogger)
        }
    }

}
