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
    private val toolSettings: com.aharou.feature.agent.domain.tool.custom.ToolSettingsStore,
    private val marketplace: com.aharou.feature.plugin.domain.market.PluginMarketplaceActions? = null,
) {
    private var operationResults = java.util.concurrent.ConcurrentHashMap<String, LinkedHashMap<String, ConfigValue>>()
    private var sessionWorkspace: String? = null
    private var sessionId: String? = null
    private var authorizeWrite: (String) -> Unit = {}
    fun forWorkspace(root: String, sessionId: String? = null, authorizeWrite: (String) -> Unit = {}): ConfigPluginFields =
        ConfigPluginFields(context, manager, records, toolSettings, marketplace).also {
            it.sessionWorkspace = root; it.sessionId = sessionId; it.authorizeWrite = authorizeWrite
            it.operationResults = operationResults
        }
    private fun checkWorkspace() {
        sessionWorkspace?.let { if (!toolSettings.matchesCurrentProject(it)) throw com.aharou.core.config.ConfigError.InvalidValue("Workspace changed; reopen this session's project") }
    }
    fun registerInto(registry: ConfigRegistry) {
        if (marketplace != null) {
            registry.register(MarketCollection(false))
            registry.register(MarketCollection(true))
        }
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
                        path = "plugins.$forId.injection", displayName = context.getString(R.string.plugin_injection_edit),
                        description = context.getString(R.string.plugin_injection_help), valueSchema = ConfigSchema.Str(maxLength = 40000), risk = ConfigRisk.SENSITIVE,
                        reader = { ConfigValue.Str(toolSettings.snapshotOverrides()[com.aharou.feature.agent.domain.tool.custom.ToolPromptMetadata.pluginKey(pluginId)]?.description.orEmpty()) },
                        writer = { value ->
                            checkWorkspace()
                            val text = (value as? ConfigValue.Str)?.value ?: throw com.aharou.core.config.ConfigError.TypeMismatch("string")
                            val key = com.aharou.feature.agent.domain.tool.custom.ToolPromptMetadata.pluginKey(pluginId)
                            check(runBlocking { toolSettings.updateOverrideForProject(key, sessionWorkspace) {
                                it.copy(description = text, promptReset = text.isBlank())
                            } })
                        },
                    ),
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
    /** Query records are transient operation results, not user settings. Mutations retain the existing config confirmation/audit gate. */
    private inner class MarketCollection(private val mutation: Boolean) : ConfigCollection {
        override val basePath = if (mutation) "plugin_market.actions" else "plugin_market.queries"
        override val displayName = "Plugin marketplace"
        override val description = if (mutation)
            "Sensitive operation. Payload: action=download|install, source=operit|dsh, id, page (default 1), type (Operit, default all), asset_id (when multiple assets), scope=global|project, destination (container file path required for download). Only native txplugin and skill formats install; DSH CLI packages report unsupported/missing runtime, never false success."
            else "Public read-only catalog query. Payload: source=operit|dsh, query (filters current page only), page (default 1), type (Operit: all|skill|script|package|mcp). Read returned record.result for exact source metadata and asset ids. DSH directory metadata does not prove runtime compatibility."
        override val risk = if (mutation) ConfigRisk.SENSITIVE else ConfigRisk.NORMAL
        override val removable = false
        private val results get() = operationResults.getOrPut("$basePath:${sessionWorkspace.orEmpty()}:${sessionId.orEmpty()}") { linkedMapOf() }
        override fun childIds(): List<String> = synchronized(results) { results.keys.toList() }
        override fun fields(forId: String): List<ConfigField> = synchronized(results) {
            if (forId !in results) emptyList() else listOf(ReadOnlyField(
                path = "$basePath.$forId.result", displayName = "result", description = "Structured operation result", valueSchema = ConfigSchema.Json,
                reader = { synchronized(results) { results[forId] ?: throw ConfigError.InvalidValue("Expired result; query again") } }))
        }
        override fun add(payload: ConfigValue): String {
            if (mutation) checkWorkspace()
            val market = marketplace ?: throw ConfigError.InvalidValue("Marketplace unavailable")
            val values = (payload as? ConfigValue.Obj)?.value ?: throw ConfigError.TypeMismatch("object")
            fun text(key: String, default: String? = null): String = (values[key] as? ConfigValue.Str)?.value
                ?: default ?: throw ConfigError.InvalidValue("Missing $key")
            val page = (values["page"] as? ConfigValue.Int)?.value ?: 1
            val source = text("source")
            val result = runBlocking {
                if (!mutation) market.query(source, text("query", ""), page, text("type", "all"))
                else {
                    val scope = when (text("scope", "global")) {
                        "global" -> com.aharou.feature.agent.domain.skill.SkillScope.GLOBAL
                        "project" -> com.aharou.feature.agent.domain.skill.SkillScope.PROJECT
                        else -> throw ConfigError.InvalidValue("scope must be global or project")
                    }
                    if (scope == com.aharou.feature.agent.domain.skill.SkillScope.PROJECT && sessionWorkspace.isNullOrBlank())
                        throw ConfigError.InvalidValue("Project installation requires a session workspace")
                    market.perform(text("action"), source, text("id"), page, text("type", "all"),
                        (values["asset_id"] as? ConfigValue.Str)?.value, scope,
                        destination = (values["destination"] as? ConfigValue.Str)?.value,
                        workspaceRoot = sessionWorkspace, beforeMutation = { checkWorkspace() }, authorizeWrite = authorizeWrite)
                }
            }
            val id = java.util.UUID.randomUUID().toString()
            synchronized(results) {
                while (results.size >= 20) results.remove(results.keys.first())
                results[id] = requireNotNull(ConfigValue.decode(result.toString()))
            }
            return id
        }
        override fun remove(id: String) { throw ConfigError.InvalidValue("Operation records expire automatically") }
    }

}
