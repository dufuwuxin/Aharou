package com.aharou.feature.agent.data

import com.aharou.core.config.ConfigCollection
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import kotlinx.coroutines.runBlocking
import com.aharou.feature.agent.domain.context.ContextFolderStore
import com.aharou.feature.agent.domain.context.ContextCategory
import com.aharou.feature.agent.domain.context.ContextEntry
import com.aharou.feature.agent.domain.context.ContextKind
import com.aharou.feature.agent.domain.context.ContextLibraryRepository
import com.aharou.feature.agent.domain.context.ContextLoad
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfigContextLibraryFields @Inject constructor(
    private val library: ContextLibraryRepository,
    private val workspace: WorkspaceRepository,
    private val sessions: ChatSessionDao,
    private val folders: ContextFolderStore,
) {
    fun registerInto(registry: ConfigRegistry) {
        registry.register(Entries())
        registry.register(object : ConfigCollection {
            override val basePath = "context_library.folders"
            override val displayName = "记忆文件夹"
            override val description = "按category和name创建当前项目文件夹；未选择项目时为全局。"
            override val addable = true
            override val removable = false
            override fun childIds(): List<String> = folders.list(root()).flatMap { (category, names) -> names.map { "${category.name}:$it" } }
            override fun fields(forId: String): List<ConfigField> = emptyList()
            override fun add(payload: ConfigValue): String {
                val values = (payload as? ConfigValue.Obj)?.value ?: throw ConfigError.TypeMismatch("object")
                val rawCategory = (values["category"] as? ConfigValue.Str)?.value
                val category = ContextCategory.entries.firstOrNull { it.name.equals(rawCategory, true) }
                    ?: throw ConfigError.InvalidValue("Unknown context category")
                val name = (values["name"] as? ConfigValue.Str)?.value?.trim()
                    ?: throw ConfigError.InvalidValue("Folder name required")
                if (!folders.create(category, name, root())) throw ConfigError.InvalidValue("Folder could not be saved")
                return "${category.name}:$name"
            }
            override fun remove(id: String) { throw ConfigError.InvalidValue("Folder removal is not supported") }
        })
    }

    private fun root(): String? = workspace.currentPath().takeIf { it.isNotBlank() }
    private fun key(entry: ContextEntry): String = MessageDigest.getInstance("SHA-256")
        .digest(entry.id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun current(id: String): ContextEntry = library.list(root()).firstOrNull { key(it) == id }
        ?: throw ConfigError.InvalidValue("Context entry not found")
    private fun update(id: String, expectedRoot: String?, expectedVersion: String, transform: (ContextEntry) -> ContextEntry) {
        if (root() != expectedRoot) throw ConfigError.InvalidValue("Workspace changed; read the entry again")
        val entry = current(id)
        if (entry.version != expectedVersion) throw ConfigError.InvalidValue("Context version changed; read again")
        val updated = transform(entry)
        if (!updated.sessionId.isNullOrBlank()) {
            val session = runBlocking { sessions.getById(updated.sessionId!!) }
            if (session == null || session.workspacePath != root().orEmpty() || session.parentId != null || session.ghostMode) {
                throw ConfigError.InvalidValue("Target session is unavailable in this workspace")
            }
        }
        if (!library.save(updated, root())) throw ConfigError.InvalidValue("Context save failed or version changed")
    }

    private inner class Entries : ConfigCollection {
        override val basePath = "context_library.entries"
        override val displayName = "统一记忆库"
        override val description = "四类记忆的分类、文件夹与加载方式；新建和删除使用 memory 的 library 动作。"
        override val addable = false
        override val removable = false
        override fun childIds(): List<String> = library.list(root()).map(::key)
        override fun add(payload: ConfigValue): String = throw ConfigError.InvalidValue("Use memory save with version=new")
        override fun remove(id: String) { throw ConfigError.InvalidValue("Use memory library_delete") }
        override fun fields(forId: String): List<ConfigField> {
            if (forId !in childIds()) return emptyList()
            val expectedRoot = root()
            var expectedVersion = current(forId).version
            fun text(name: String, description: String, read: (ContextEntry) -> String, write: (ContextEntry, String) -> ContextEntry) = ClosureField(
                path = "$basePath.$forId.$name", displayName = name, description = description,
                valueSchema = ConfigSchema.Str(),
                reader = {
                    if (root() != expectedRoot) throw ConfigError.InvalidValue("Workspace changed; read again")
                    val entry = current(forId)
                    expectedVersion = entry.version
                    ConfigValue.Str(read(entry))
                },
                writer = { value ->
                    val string = (value as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                    update(forId, expectedRoot, expectedVersion) { write(it, string) }
                },
            )
            return listOf(
                text("category", "RULE / TEMPORARY / LONG_TERM / INJECTED；临时类必须有真实session_id", { it.category.name }, { e, v ->
                    val category = ContextCategory.entries.firstOrNull { it.name.equals(v, true) }
                        ?: throw ConfigError.InvalidValue("Unknown context category")
                    if (category == ContextCategory.TEMPORARY && e.sessionId.isNullOrBlank()) throw ConfigError.InvalidValue("Set session_id first")
                    e.copy(category = category)
                }),
                text("loading", "FULL / INDEX / SEARCH / OFF", { it.loading.name }, { e, v ->
                    e.copy(loading = ContextLoad.entries.firstOrNull { it.name.equals(v, true) }
                        ?: throw ConfigError.InvalidValue("Unknown context loading mode"))
                }),
                text("kind", "INSTRUCTION / REFERENCE", { it.kind.name }, { e, v ->
                    e.copy(kind = ContextKind.entries.firstOrNull { it.name.equals(v, true) }
                        ?: throw ConfigError.InvalidValue("Unknown context kind"))
                }),
                text("folder", "此类别中的文件夹路径，可用 / 分隔层级，空字符串表示根目录", { it.folder }, { e, v -> e.copy(folder = v.trim()) }),
                text("session_id", "临时内容只在这个对话中使用；不能用其他项目的对话", { it.sessionId.orEmpty() }, { e, v ->
                    e.copy(sessionId = v.trim().ifBlank { null })
                }),
            )
        }
    }
}
