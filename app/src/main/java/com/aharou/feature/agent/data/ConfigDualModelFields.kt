package com.aharou.feature.agent.data

import com.aharou.core.config.ConfigCollection
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.data.repository.DualModelSettingsRepository
import com.aharou.feature.agent.domain.session.DualModelSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking

@Singleton
class ConfigDualModelFields @Inject constructor(
    private val settings: DualModelSettingsRepository,
    private val sessions: ChatSessionDao
) {
    fun registerInto(registry: ConfigRegistry) {
        registry.register(object : ConfigCollection {
            override val basePath = "dual_model.sessions"
            override val displayName = "双模型规划"
            override val description = "仅显式会话；PLAN 使用规划模型，其余模式使用执行模型。选择在下一轮生效。"
            override val addable = false
            override val removable = false
            override fun childIds(): List<String> = runBlocking { sessions.getAllOnce() }.filter { it.parentId == null }.map { it.id }
            override fun fields(forId: String): List<ConfigField> {
                fun verifySession() {
                    val session = runBlocking { sessions.getById(forId) }
                    if (session == null || session.parentId != null) throw ConfigError.InvalidValue("An explicit root session is required")
                }
                val session = runBlocking { sessions.getById(forId) }
                if (session == null || session.parentId != null) return emptyList()
                fun read() = runBlocking { verifySession(); settings.get(forId) }
                fun update(transform: (DualModelSettings) -> DualModelSettings) {
                    verifySession()
                    try { runBlocking { settings.update(forId, transform) } }
                    catch (e: IllegalArgumentException) { throw ConfigError.InvalidValue(e.message ?: "Invalid dual model settings") }
                }
                return listOf(
                    ClosureField(
                        path = "$basePath.$forId.enabled", displayName = "启用双模型规划",
                        description = "启用前须完整配置两个模型；关闭保留选择。", valueSchema = ConfigSchema.Bool,
                        reader = { ConfigValue.Bool(read().enabled) },
                        writer = { value -> update { it.copy(enabled = (value as ConfigValue.Bool).value) } }
                    ),
                    modelField("planning", forId, { read().planning }, { target -> update { it.copy(planning = target) } }),
                    modelField("execution", forId, { read().execution }, { target -> update { it.copy(execution = target) } })
                )
            }
            override fun add(payload: ConfigValue): String = throw ConfigError.PermissionDenied("Create a chat session first")
            override fun remove(id: String) { throw ConfigError.PermissionDenied("Delete the chat session instead") }
        })
    }

    private fun modelField(
        role: String, sessionId: String,
        read: () -> com.aharou.feature.settings.domain.model.FallbackTarget?,
        write: (com.aharou.feature.settings.domain.model.FallbackTarget?) -> Unit
    ): ConfigField = ClosureField(
        path = "dual_model.sessions.$sessionId.$role",
        displayName = if (role == "planning") "规划模型" else "执行模型",
        description = "providerId:model，按第一个冒号分隔；空值清除。", valueSchema = ConfigSchema.Str(),
        reader = { ConfigValue.Str(DualModelSettings.encodeTarget(read())) },
        writer = { value ->
            val target = try { DualModelSettings.decodeTarget((value as ConfigValue.Str).value) }
                catch (e: IllegalArgumentException) { throw ConfigError.InvalidValue(e.message ?: "Expected providerId:model") }
            write(target)
        }
    )
}
