package com.aharou.feature.agent.data

import com.aharou.core.config.ConfigCollection
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ReadOnlyField
import com.aharou.feature.agent.domain.knowledge.KnowledgeRepository
import com.aharou.feature.agent.domain.knowledge.KnowledgeSourceRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Local knowledge creation shares the UI repository and never invokes a Git service. */
@Singleton
class ConfigKnowledgeFields @Inject constructor(
    private val repository: KnowledgeRepository,
    private val sources: KnowledgeSourceRepository
) {
    fun registerInto(registry: ConfigRegistry) {
        registry.register(LocalCollection())
        registry.register(DocumentCollection())
    }

    private fun localIds(): List<String> = sources.all().filterValues { it.isLocal }.keys.toList()

    private fun objectOf(payload: ConfigValue): Map<String, ConfigValue> =
        (payload as? ConfigValue.Obj)?.value
            ?: throw ConfigError.TypeMismatch("object")

    private fun Map<String, ConfigValue>.text(key: String, required: Boolean = true): String {
        val raw = get(key)
        if (raw != null && raw !is ConfigValue.Str) throw ConfigError.TypeMismatch("string")
        val value = raw?.value
        if (required && value.isNullOrBlank()) throw ConfigError.InvalidValue("$key is required")
        return value.orEmpty()
    }

    private inner class LocalCollection : ConfigCollection {
        override val basePath = "knowledge.local"
        override val displayName = "本地知识库"
        override val description = "本地 Markdown 知识库。add: {name, description, markdown}；创建后立即可检索，不需要 Git。"
        override fun childIds(): List<String> = localIds()
        override fun fields(forId: String): List<ConfigField> {
            val source = sources.all()[forId]?.takeIf { it.isLocal } ?: return emptyList()
            return listOf(
                ReadOnlyField(
                    path = "$basePath.$forId.name", displayName = "名称", description = "知识库名称",
                    valueSchema = ConfigSchema.Str(), reader = { ConfigValue.Str(source.displayName("en")) }
                ),
                ReadOnlyField(
                    path = "$basePath.$forId.description", displayName = "描述", description = "知识库描述",
                    valueSchema = ConfigSchema.Str(), reader = { ConfigValue.Str(source.displayDescription("en")) }
                )
            )
        }
        override fun add(payload: ConfigValue): String {
            val obj = objectOf(payload)
            return repository.createLocal(obj.text("name"), obj.text("description", false), obj.text("markdown"))
        }
        override fun remove(id: String) {
            if (id !in localIds()) throw ConfigError.InvalidValue("Unknown local knowledge source")
            if (repository.sourceDir(id).exists() && !repository.uninstall(id)) {
                throw ConfigError.InvalidValue("Cannot remove local knowledge contents")
            }
            sources.remove(id)
        }
    }

    private inner class DocumentCollection : ConfigCollection {
        override val basePath = "knowledge.documents"
        override val displayName = "本地知识文档"
        override val description = "add: {sourceId, path, markdown}。只向本地知识库添加 Markdown，不覆盖重名文件。"
        override val removable = false
        override fun childIds(): List<String> = localIds().flatMap { id ->
            repository.documents(id).map { "$id/${it.path}" }
        }
        override fun fields(forId: String): List<ConfigField> = emptyList()
        override fun add(payload: ConfigValue): String {
            val obj = objectOf(payload)
            val id = obj.text("sourceId")
            val path = obj.text("path")
            repository.addLocalDocument(id, path, obj.text("markdown"))
            return "$id/$path"
        }
        override fun remove(id: String) {
            throw ConfigError.InvalidValue("Remove the local knowledge source to delete its documents")
        }
    }
}
