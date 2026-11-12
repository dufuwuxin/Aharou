package com.aharou.feature.agent.data

import com.aharou.core.config.ConfigCollection
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.feature.agent.domain.subagent.SubAgentEventBus
import com.aharou.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aharou.feature.agent.domain.subagent.SubAgentSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 子代理并发上限 → 配置通道。
 *
 * 上限存在 SharedPreferences（`aharou_subagent_prefs`）而不是 DataStore，所以用 [ClosureField]
 * 桥接；读写都同步，正好对得上。
 */
@Singleton
class ConfigSubAgentFields @Inject constructor(
    private val subAgentSettings: SubAgentSettings,
    private val definitions: AgentDefinitionRepository,
) {

    fun registerInto(registry: ConfigRegistry) = with(registry) {
        register(RoleTags())
        register(
            ClosureField(
                path = "subagent.max_running",
                displayName = "子代理并发上限",
                description = "同时运行的子代理上限（整个应用共享，不按会话分组），范围 " +
                    "${SubAgentSettings.MIN_RUNNING}..${SubAgentSettings.MAX_RUNNING}，默认 " +
                    "${SubAgentEventBus.DEFAULT_MAX_RUNNING}。超过范围会被自动收敛。",
                valueSchema = ConfigSchema.Int(SubAgentSettings.MIN_RUNNING, SubAgentSettings.MAX_RUNNING),
                reader = { ConfigValue.Int(subAgentSettings.maxRunning()) },
                writer = { v ->
                    // 缺值/类型不对时回落到默认上限，而不是保留旧值：写入方拿到的应当是可预期的结果。
                    subAgentSettings.setMaxRunning(
                        (v as? ConfigValue.Int)?.value ?: SubAgentEventBus.DEFAULT_MAX_RUNNING,
                    )
                },
            ),
        )
        register(
            ClosureField(
                path = "subagent.communication_enabled",
                displayName = "子代理通信开关",
                description = "是否允许同一批次内的子代理互相交流（task 工具的 team / tell 动作）。" +
                    "关闭后子代理只能向父代理汇报，不能互相通气；主代理对子代理的 send / read / stop 不受影响。默认开启。",
                valueSchema = ConfigSchema.Bool,
                revertable = true,
                reader = { ConfigValue.Bool(subAgentSettings.communicationEnabled()) },
                writer = { v ->
                    subAgentSettings.setCommunicationEnabled(
                        (v as? ConfigValue.Bool)?.value
                            ?: throw ConfigError.TypeMismatch("boolean"),
                    )
                },
            ),
        )
    }

    private inner class RoleTags : ConfigCollection {
        override val basePath = "subagent.roles"
        override val displayName = "Role tags"
        override val description = "Effective roles; project definitions override global ones. IDs escape % as %25 and dot as %2E; original role names stay unchanged. Tags never grant tools."
        override val addable = false
        override val removable = false
        private fun roleId(name: String) = name.replace("%", "%25").replace(".", "%2E")
        override fun childIds(): List<String> = definitions.listAll().map { roleId(it.definition.name) }
        private fun entry(id: String) = definitions.listAll().firstOrNull { roleId(it.definition.name) == id }
            ?: throw ConfigError.InvalidValue("Unknown role")

        override fun fields(forId: String): List<ConfigField> {
            if (forId !in childIds()) return emptyList()
            return listOf(ClosureField(
                path = "$basePath.$forId.tags",
                displayName = "Role tags",
                description = "Case-sensitive audience tags. Maximum 24 tags, 64 characters each; no commas or control characters. Empty clears. Does not rename or grant tools.",
                valueSchema = ConfigSchema.Array(ConfigSchema.Str(maxLength = 64)),
                reader = { ConfigValue.Arr(entry(forId).definition.tags.map { ConfigValue.Str(it) }) },
                writer = { value ->
                    val tags = (value as? ConfigValue.Arr)?.value?.map {
                        (it as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                    } ?: throw ConfigError.TypeMismatch("array")
                    val current = entry(forId)
                    val role = current.definition
                    val error = definitions.setTags(role.name, tags, current.scope, role.filePath)
                    if (error != null) throw ConfigError.InvalidValue("Role tag save failed: $error")
                }
            ))
        }
        override fun add(payload: ConfigValue): String = throw ConfigError.PermissionDenied("Edit existing role tags only")
        override fun remove(id: String) { throw ConfigError.PermissionDenied("Clear the tags array instead") }
    }
}
