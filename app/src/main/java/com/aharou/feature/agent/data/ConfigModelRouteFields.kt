package com.aharou.feature.agent.data

import com.aharou.core.config.ConfigCollection
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.feature.settings.data.repository.ModelRouteGroupRepository
import com.aharou.feature.settings.domain.model.ModelRouteGroup
import com.aharou.feature.settings.domain.model.ModelRouteGroupCodec
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/** Config-channel CRUD for ordered public provider/model route groups. */
@Singleton
class ConfigModelRouteFields @Inject constructor(
    private val repository: ModelRouteGroupRepository,
    private val defaults: com.aharou.feature.settings.data.repository.DefaultModelSettingsRepository,
) {
    fun registerInto(registry: ConfigRegistry) {
        registry.register(ModelRoutes())
    }

    private inner class ModelRoutes : ConfigCollection {
        override val basePath = "model_routes"
        override val displayName = "模型路由组"
        override val description = "按勾选顺序保存 providerId + model；只保存公开引用，不接受 API key、token 或密码。"
        override fun childIds(): List<String> = runBlocking { repository.snapshot().map { it.id } }

        override fun fields(forId: String): List<com.aharou.core.config.ConfigField> {
            if (runBlocking { repository.get(forId) } == null) return emptyList()
            return listOf(ClosureField(
                path = "$basePath.$forId.form",
                displayName = "路由组完整表单",
                description = "完整替换名称和有序模型列表；只影响之后启动的成员。",
                valueSchema = ConfigSchema.Json,
                reader = { ConfigValue.decode(ModelRouteGroupCodec.form(runBlocking { repository.get(forId) } ?: throw ConfigError.InvalidValue("模型路由组不存在")).toString())!! },
                writer = { value ->
                    val form = parse(value)
                    runBlocking { repository.update(forId, form.name, form.models) }
                },
            ))
        }

        override fun add(payload: ConfigValue): String {
            val form = parse(payload)
            return runBlocking { repository.create(form.name, form.models).id }
        }

        override fun remove(id: String) {
            runBlocking {
                if (!repository.delete(id)) throw ConfigError.InvalidValue("模型路由组不存在：$id")
                defaults.clearRoute(id)
            }
        }

        private fun parse(value: ConfigValue): com.aharou.feature.settings.domain.model.ModelRouteGroupForm =
            try {
                ModelRouteGroupCodec.parseForm(value.jsonString())
            } catch (error: IllegalArgumentException) {
                throw ConfigError.InvalidValue("模型路由组格式无效：需要名称及有序模型引用")
            } catch (error: Exception) {
                throw ConfigError.InvalidValue("模型路由组格式无效：需要名称及有序模型引用")
            }
    }
}
