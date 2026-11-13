package com.aharou.feature.plugin.data

import android.content.Context
import com.aharou.R
import com.aharou.core.config.*
import com.aharou.core.config.fields.ClosureField
import com.aharou.core.config.fields.ReadOnlyField
import com.aharou.feature.plugin.domain.PluginManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking

@Singleton
class ConfigPluginFields @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val manager: PluginManager,
    private val records: PluginRecordStore,
) {
    fun registerInto(registry: ConfigRegistry) {
        registry.register(object : ConfigCollection {
            override val basePath = "plugins"
            override val displayName = context.getString(R.string.plugin_host_title)
            override val description = context.getString(R.string.plugin_host_trust)
            override val addable = false
            override val removable = false
            override fun childIds() = records.all().keys.filter { manager.readManifest(it)?.hostRuntime != null }.map { it.replace(".", "~") }
            override fun fields(forId: String): List<ConfigField> {
                if (forId !in childIds()) return emptyList()
                val pluginId = forId.replace("~", ".")
                return listOf(
                    ClosureField(
                        path = "plugins.$forId.enabled", displayName = context.getString(R.string.plugin_host_enabled),
                        description = description, valueSchema = ConfigSchema.Bool, risk = ConfigRisk.SENSITIVE,
                        reader = { ConfigValue.Bool(records.get(pluginId)?.enabled == true) },
                        writer = { value -> runBlocking { manager.setEnabled(pluginId, (value as ConfigValue.Bool).value).getOrThrow() } },
                    ),
                    ClosureField(
                        path = "plugins.$forId.compile", displayName = context.getString(R.string.plugin_host_compile),
                        description = context.getString(R.string.plugin_host_compile_desc), valueSchema = ConfigSchema.Bool,
                        risk = ConfigRisk.SENSITIVE, revertable = false,
                        reader = { ConfigValue.Bool(false) },
                        writer = { value -> if ((value as ConfigValue.Bool).value) runBlocking { manager.compile(pluginId).getOrThrow() } },
                    ),
                    ReadOnlyField(
                        path = "plugins.$forId.compile_output", displayName = context.getString(R.string.plugin_host_compile_output),
                        description = context.getString(R.string.plugin_host_compile_desc), valueSchema = ConfigSchema.Str(),
                        reader = { ConfigValue.Str(manager.compileOutput(pluginId)) },
                    ),
                )
            }
            override fun add(payload: ConfigValue): String = error("Import a .txplugin package from the plugin screen")
            override fun remove(id: String) { error("Uninstall from the plugin screen") }
        })
    }
}
