package com.aharou.feature.settings.data.repository

import android.app.Application
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ContextPreferencesTest {
    @Test fun exactPresetsAndCustomAreDerivedRatherThanStored() {
        assertEquals(ContextPreferences(0, 55, 90, true, 8), ContextPreset.BALANCED.preferences)
        assertEquals(ContextPreferences(8, 40, 75, true, 2), ContextPreset.ECONOMY.preferences)
        assertEquals(ContextPreferences(0, 70, 95, true, 16), ContextPreset.DETAILED.preferences)
        ContextPreset.entries.filter { it != ContextPreset.CUSTOM }.forEach {
            assertEquals(it, it.preferences!!.preset)
        }
        assertEquals(ContextPreset.CUSTOM, ContextPreferences(2, 50, 80, false, 0).preset)
    }

    @Test fun presetsPersistAllFiveFieldsAndCustomDoesNotOverwrite() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repository = GeneralSettingsRepository(context)
        val original = repository.contextPreferences()
        try {
            for (preset in listOf(ContextPreset.ECONOMY, ContextPreset.DETAILED, ContextPreset.BALANCED)) {
                repository.applyContextPreset(preset)
                val reloaded = GeneralSettingsRepository(context)
                assertEquals(preset.preferences, reloaded.contextPreferences())
                assertEquals(preset.preferences, reloaded.contextPreferencesFlow.first())
                assertEquals(preset.preferences!!.preserveSummary, reloaded.contextPreserveSummaryFlow.first())
                assertEquals(preset.preferences!!.keepTools, reloaded.contextToolResultKeepRecentFlow.first())
            }
            repository.setContextTrimRounds(17)
            val custom = repository.contextPreferences()
            assertEquals(ContextPreset.CUSTOM, custom.preset)
            repository.applyContextPreset(ContextPreset.CUSTOM)
            assertEquals(custom, repository.contextPreferences())
            repository.setContextToolResultKeepRecent(101)
            assertEquals(100, repository.contextToolResultKeepRecent())
            repository.setContextToolResultKeepRecent(-1)
            assertEquals(0, repository.contextToolResultKeepRecent())
            repository.setContextPreserveSummary(false)
            assertFalse(repository.contextPreserveSummary())
        } finally { repository.setContextPreferences(original) }
    }
    @Test fun configFieldsAndUiReadAndWriteTheSamePersistedValues() {
        val context = RuntimeEnvironment.getApplication()
        val repository = GeneralSettingsRepository(context)
        val registry = com.aharou.core.config.ConfigRegistry.isolated()
        val plugins = io.mockk.mockk<com.aharou.feature.plugin.data.ConfigPluginFields>(relaxed = true)
        com.aharou.feature.settings.data.ConfigAppFields(context, repository,
            io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true), plugins).registerInto(registry)
        val old = runBlocking { repository.contextPreferences() }
        try {
            val preset = registry.resolveField("app.context_preset")!!
            preset.write(com.aharou.core.config.ConfigValue.Str("economy"))
            assertEquals(ContextPreset.ECONOMY.preferences, runBlocking { repository.contextPreferences() })
            val previous = preset.read()
            preset.write(com.aharou.core.config.ConfigValue.Str("detailed"))
            preset.write(previous)
            assertEquals(ContextPreset.ECONOMY.preferences, runBlocking { repository.contextPreferences() })
            registry.resolveField("app.context_preserve_summary")!!.write(com.aharou.core.config.ConfigValue.Bool(false))
            registry.resolveField("app.context_tool_result_keep_recent")!!.write(com.aharou.core.config.ConfigValue.Int(0))
            assertEquals(com.aharou.core.config.ConfigValue.Str("custom"),
                (preset.read() as com.aharou.core.config.ConfigValue.Obj).value["preset"])
            assertFalse(runBlocking { repository.contextPreserveSummary() })
            assertEquals(0, runBlocking { repository.contextToolResultKeepRecent() })
            preset.write(com.aharou.core.config.ConfigValue.Str("custom"))
            val custom = runBlocking { repository.contextPreferences() }
            val auditJson = preset.read().jsonString()
            preset.write(com.aharou.core.config.ConfigValue.Str("balanced"))
            val restarted = com.aharou.core.config.ConfigRegistry.isolated()
            com.aharou.feature.settings.data.ConfigAppFields(context, GeneralSettingsRepository(context),
                io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true), plugins).registerInto(restarted)
            restarted.resolveField("app.context_preset")!!.write(com.aharou.core.config.ConfigValue.decode(auditJson)!!)
            assertEquals(custom, runBlocking { repository.contextPreferences() })
            assertEquals(ContextPreset.CUSTOM, custom.preset)
            val snapshot = (preset.read() as com.aharou.core.config.ConfigValue.Obj).value
            val invalid = listOf(
                snapshot - "rounds",
                snapshot + ("rounds" to com.aharou.core.config.ConfigValue.Int(101)),
                snapshot + ("soft" to com.aharou.core.config.ConfigValue.Int(0)),
                snapshot + ("preserveSummary" to com.aharou.core.config.ConfigValue.Str("true")),
                snapshot + ("preset" to com.aharou.core.config.ConfigValue.Str("balanced")),
                snapshot + ("unexpected" to com.aharou.core.config.ConfigValue.Bool(true)),
            )
            invalid.forEach { value ->
                try {
                    preset.write(com.aharou.core.config.ConfigValue.Obj(value))
                    fail("Invalid snapshots must not change any parameter")
                } catch (_: com.aharou.core.config.ConfigError) { }
                assertEquals(custom, runBlocking { repository.contextPreferences() })
            }
        } finally { runBlocking { repository.setContextPreferences(old) } }
    }
}
