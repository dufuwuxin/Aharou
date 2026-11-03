package com.aharou.feature.agent.data

import com.aharou.core.config.ConfigCollection
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.domain.steward.StewardController
import com.aharou.feature.agent.domain.steward.StewardSettings
import com.aharou.feature.agent.domain.steward.StewardSettingsStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking

/** Keep the config channel, but require an explicit session instead of a global runtime switch. */
@Singleton
class ConfigStewardFields @Inject constructor(
    private val steward: StewardSettingsStore,
    private val controller: StewardController,
    private val sessions: ChatSessionDao,
    private val messages: com.aharou.feature.agent.domain.session.MessagePersistenceUseCase,
) {
    fun registerInto(registry: ConfigRegistry) {
        registry.register(object : ConfigCollection {
            override val basePath = "steward.sessions"
            override val displayName = "会话智能托管"
            override val description = "按会话配置 enabled / max_loops / unlimited；不会影响其他会话。"
            override val addable = false
            override val removable = false
            override fun childIds(): List<String> = runBlocking { sessions.getAllOnce() }.map { it.id }
            override fun fields(forId: String): List<ConfigField> {
                if (runBlocking { sessions.getById(forId) } == null) return emptyList()
                fun verifySession() {
                    if (runBlocking { sessions.getById(forId) } == null) {
                        throw ConfigError.InvalidValue("Session no longer exists")
                    }
                }
                return listOf(
                    ClosureField(
                        path = "$basePath.$forId.enabled",
                        displayName = "智能托管",
                        description = "仅开启此会话；空会话等待下一条用户指令。进程重启后关闭。",
                        valueSchema = ConfigSchema.Bool,
                        reader = { ConfigValue.Bool(controller.isEnabled(forId)) },
                        writer = { value ->
                            verifySession()
                            if ((value as ConfigValue.Bool).value) {
                                val anchor = runBlocking { messages.getMessagesOnce(forId) }.lastOrNull {
                                    it.role == "USER" && it.content.isNotBlank() &&
                                        !it.content.startsWith(com.aharou.feature.agent.presentation.BACKGROUND_NOTIFICATION_PREFIX)
                                }?.content.orEmpty()
                                controller.enable(forId, anchor)
                            } else controller.disable(forId)
                        },
                    ),
                    ClosureField(
                        path = "$basePath.$forId.max_loops",
                        displayName = "托管轮数上限",
                        description = "仅此会话，默认 ${StewardSettings.DEFAULT_MAX_LOOPS} 轮。",
                        valueSchema = ConfigSchema.Int(StewardSettings.MIN_LOOPS, StewardSettings.MAX_LOOPS),
                        reader = { ConfigValue.Int(steward.maxLoops(forId)) },
                        writer = { verifySession(); controller.setMaxLoops(forId, (it as ConfigValue.Int).value) },
                    ),
                    ClosureField(
                        path = "$basePath.$forId.unlimited",
                        displayName = "不限轮数",
                        description = "仅此会话，仍受硬上限 ${StewardController.HARD_MAX_LOOPS} 轮约束。",
                        valueSchema = ConfigSchema.Bool,
                        reader = { ConfigValue.Bool(steward.unlimited(forId)) },
                        writer = { verifySession(); controller.setUnlimited(forId, (it as ConfigValue.Bool).value) },
                    ),
                )
            }
            override fun add(payload: ConfigValue): String = throw ConfigError.PermissionDenied("Create a chat session first")
            override fun remove(id: String) { throw ConfigError.PermissionDenied("Delete the chat session instead") }
        })
    }
}
