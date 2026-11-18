package com.aharou.feature.agent.domain.tool.script

import android.content.Context
import com.aharou.core.config.*
import com.aharou.feature.agent.data.ConfigScriptToolFields
import com.aharou.feature.agent.domain.tool.ToolRegistry
import io.mockk.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScriptToolManagerTest {
    @get:Rule val temp = TemporaryFolder()
    private fun newManager(registry: ToolRegistry): ScriptToolManager {
        val context = mockk<Context>()
        every { context.filesDir } returns temp.root
        return ScriptToolManager(context, mockk(relaxed = true), registry)
    }

    @Test fun liveContextNeverRegistersAsFunctionToolAndReloadPreservesType() {
        val registry = ToolRegistry()
        val manager = newManager(registry)
        val ordinary = ScriptToolDefinition("ordinary", "Ordinary", "cat")
        val live = ordinary.copy(name = "live", type = ScriptToolType.LIVE_CONTEXT)
        manager.add(ordinary)
        manager.add(live)
        assertTrue(registry.hasTool(ordinary.toolName))
        assertFalse(registry.hasTool(live.toolName))
        val restored = newManager(ToolRegistry())
        restored.reload()
        assertEquals(listOf(ordinary, live), restored.definitions.value)
        val revision = manager.revision
        manager.update(ordinary.name, ordinary.copy(enabled = false))
        manager.update(ordinary.name, ordinary)
        assertTrue(manager.revision > revision)
        manager.update(ordinary.name, ordinary.copy(type = ScriptToolType.LIVE_CONTEXT))
        assertFalse(registry.hasTool(ordinary.toolName))
        manager.setEnabled(live.name, false)
        assertFalse(registry.hasTool(live.toolName))
    }

    @Test fun configTypeAndBoundsRoundTripThroughRealManager() {
        val manager = newManager(ToolRegistry())
        val fields = ConfigScriptToolFields(manager)
        val collection = slot<ConfigCollection>()
        val config = mockk<ConfigRegistry>()
        every { config.register(capture(collection)) } just Runs
        fields.registerInto(config)
        collection.captured.add(ConfigValue.Obj(mapOf(
            "name" to ConfigValue.Str("live"), "description" to ConfigValue.Str("Current"),
            "command" to ConfigValue.Str("cat"), "type" to ConfigValue.Str("live_context"),
            "timeout_seconds" to ConfigValue.Int(2), "output_limit" to ConfigValue.Int(32),
        )))
        val byPath = collection.captured.fields("live").associateBy { it.path.substringAfterLast('.') }
        assertEquals(ConfigValue.Str("live_context"), byPath.getValue("type").read())
        byPath.getValue("output_limit").write(ConfigValue.Int(64))
        byPath.getValue("timeout_seconds").write(ConfigValue.Int(3))
        val restored = newManager(ToolRegistry()).also { it.reload() }.definitions.value.single()
        assertEquals(64, restored.outputLimit)
        assertEquals(3, restored.timeoutSeconds)
        assertEquals(ScriptToolType.LIVE_CONTEXT, restored.type)
        byPath.getValue("type").write(ConfigValue.Str("tool"))
        assertEquals(ScriptToolType.TOOL, manager.definitions.value.single().type)
        assertTrue(runCatching { byPath.getValue("type").write(ConfigValue.Str("unknown")) }.isFailure)
        assertTrue(runCatching { byPath.getValue("output_limit").write(ConfigValue.Int(0)) }.isFailure)
    }
}
