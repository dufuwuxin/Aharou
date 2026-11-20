package com.aharou.feature.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aharou.core.config.FullAccessCapability
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.tool.ToolRegistry
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.plugin.runtime.AppControlTool
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.LEGACY)
class AppControlToolTest {
    private val application = ApplicationProvider.getApplicationContext<Context>()
    private val tool = AppControlTool(application)
    private val context = AgentContext(null, null, application.filesDir.absolutePath, null, sessionId = "main")
    private fun args(action: String, vararg values: Pair<String, JsonElement>) = mapOf("action" to JsonPrimitive(action), *values)
    private fun value(text: String) = JsonPrimitive(text)
    @After fun revoke() { FullAccessCapability.setEnabled(false) }

    @Test fun disabledToolIsNotAdvertisedAndCannotExecuteEvenWithAnOldReference() = runBlocking {
        val registry = ToolRegistry().apply { register(tool.name, tool) }
        FullAccessCapability.setEnabled(false)
        assertFalse(registry.getAvailableTools().any { it.name == tool.name })
        assertEquals("FULL_ACCESS_DISABLED", (tool.executeWithContext(args("status"), context) as ToolResult.Error).code)
        FullAccessCapability.setEnabled(true)
        assertTrue(registry.getAvailableTools().any { it.name == tool.name })
        registry.bindDiscoveryTools(listOf(tool), context)
        registry.activate(listOf(tool.name), context)
        assertEquals(listOf(tool), registry.requestTools(listOf(tool), context))
        assertTrue(application.getSharedPreferences("host_full_access", Context.MODE_PRIVATE).getBoolean("enabled", false))
        FullAccessCapability.setEnabled(false)
        assertFalse(registry.getAvailableTools().any { it.name == tool.name })
        assertTrue(registry.getDeferredTools(context).isEmpty())
        assertTrue(registry.requestTools(listOf(tool), context).isEmpty())
    }

    @Test fun writesPersistAndStaleWritesLeaveTheOriginalFileIntact() = runBlocking {
        FullAccessCapability.setEnabled(true)
        val file = File(application.filesDir, "app-control-test.txt")
        if (file.exists()) file.delete()
        val written = tool.executeWithContext(args("write", "path" to value(file.path), "content" to value("before")), context) as ToolResult.Success
        val digest = written.data.jsonObject.getValue("sha256").jsonPrimitive.content
        assertEquals("before", file.readText())
        assertTrue(tool.executeWithContext(args("write", "path" to value(file.path), "content" to value("stale")), context) is ToolResult.Error)
        assertEquals("before", file.readText())
        assertTrue(tool.executeWithContext(args("write", "path" to value(file.path), "content" to value("after"), "expected_sha256" to value(digest)), context) is ToolResult.Success)
        val read = tool.executeWithContext(args("read", "path" to value(file.path)), context) as ToolResult.Success
        assertEquals("after", read.data.jsonObject.getValue("content").jsonPrimitive.content)
        assertNotEquals(digest, read.data.jsonObject.getValue("sha256").jsonPrimitive.content)
    }

    @Test fun reflectionHandlesAreSessionIsolatedAndRevokedImmediately() = runBlocking {
        FullAccessCapability.setEnabled(true)
        val host = (tool.executeWithContext(args("reflect", "operation" to value("reflect.context")), context) as ToolResult.Success).data
        val getPackage = args("reflect", "operation" to value("reflect.invoke"), "arguments" to buildJsonObject {
            put("target", host); put("name", "getPackageName")
        })
        assertTrue(tool.executeWithContext(getPackage, context) is ToolResult.Success)
        assertTrue(tool.executeWithContext(getPackage, context.copy(sessionId = "other")) is ToolResult.Error)
        FullAccessCapability.setEnabled(false)
        FullAccessCapability.setEnabled(true)
        assertTrue(tool.executeWithContext(getPackage, context) is ToolResult.Error)
    }
}
