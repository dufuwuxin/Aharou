package com.aharou.feature.agent.domain.skill

import com.aharou.core.util.FileLogger
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.WorkspacePathMapper
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The type is stored once per scope, not duplicated in each skill. */
data class SkillType(
    val id: String,
    val name: String,
    val semantics: String,
    val functions: String,
    val tags: List<String> = emptyList()
)

@Singleton
class SkillTypeRepository @Inject constructor(
    private val fileAccess: FileAccessProvider,
    private val workspace: WorkspaceRepository? = null
) {
    val workspaceChanges: kotlinx.coroutines.flow.StateFlow<com.aharou.feature.workspace.domain.model.Workspace?>?
        get() = workspace?.current
    fun workspaceKey(): String? = workspace?.currentPath()

    private fun access(scope: SkillScope, root: String? = workspaceKey()): FileAccessProvider =
        if (scope == SkillScope.PROJECT && workspace != null) {
            require(!root.isNullOrBlank()) { "No project selected" }
            fileAccess.forWorkspace(root).checked()
        } else fileAccess.checked()

    private fun path(scope: SkillScope): String = when (scope) {
        SkillScope.PROJECT -> "${WorkspacePathMapper.CONTAINER_ROOT}/.aharou/skill-types.json"
        else -> "~/.aharou/skill-types.json"
    }

    /** Project callers may inherit global types; global callers never inspect a project. */
    fun list(scope: SkillScope): List<SkillType> {
        val global = listLocal(SkillScope.GLOBAL)
        return if (scope == SkillScope.PROJECT) {
            (global.associateBy { it.id } + listLocal(scope).associateBy { it.id }).values.toList()
        } else global
    }

    fun get(id: String, scope: SkillScope): SkillType? = list(scope).firstOrNull { it.id == id }

    fun listLocal(scope: SkillScope): List<SkillType> = try {
        read(scope)
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        FileLogger.w("SkillTypeRepository", "Cannot read skill types", failure)
        emptyList()
    }

    @Synchronized
    fun save(type: SkillType, scope: SkillScope, expectedWorkspace: String? = workspaceKey()) {
        if (scope == SkillScope.PROJECT) require(expectedWorkspace == workspaceKey()) { "Workspace changed; reopen the type editor" }
        require(scope != SkillScope.BUILTIN) { "Builtin types are read-only" }
        require(type.id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid skill type id" }
        require(type.name.isNotBlank()) { "Type name is required" }
        require(type.semantics.isNotBlank() || type.functions.isNotBlank()) { "Type guidance is required" }
        val provider = access(scope, expectedWorkspace)
        val target = path(scope)
        val original = if (provider.isFile(target)) provider.readFile(target) else null
        // Strict parsing before mutation prevents overwriting an unreadable user configuration.
        val existing = if (original == null) emptyList() else decode(original)
        val normalized = type.copy(name = type.name.trim(), tags = type.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct())
        val types = existing.filterNot { it.id == type.id } + normalized
        try {
            provider.writeFile(target, encode(types), overwrite = true)
            check(read(scope, provider) == types) { "Skill type write did not persist" }
        } catch (failure: Exception) {
            try {
                if (original != null) provider.writeFile(target, original, overwrite = true)
                else if (provider.isFile(target)) provider.delete(target)
            } catch (restore: Exception) { failure.addSuppressed(restore) }
            throw failure
        }
    }

    private fun read(scope: SkillScope, provider: FileAccessProvider = access(scope)): List<SkillType> =
        if (provider.isFile(path(scope))) decode(provider.readFile(path(scope))) else emptyList()

    private fun decode(text: String): List<SkillType> = Json.parseToJsonElement(text).jsonArray.map { element ->
        val fields = element.jsonObject
        fun string(key: String): String = (fields[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: error("Invalid skill type field: $key")
        val id = string("id")
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid skill type id" }
        SkillType(id, string("name"), string("semantics"), string("functions"),
            fields["tags"]?.jsonArray?.map { it.jsonPrimitive.also { item -> require(item.isString) }.content }.orEmpty())
            .also { require(it.name.isNotBlank() && (it.semantics.isNotBlank() || it.functions.isNotBlank())) }
    }.also { types -> require(types.map { it.id }.distinct().size == types.size) { "Duplicate skill type id" } }

    private fun encode(types: List<SkillType>): String = JsonArray(types.map { type ->
        buildJsonObject {
            put("id", type.id)
            put("name", type.name)
            put("semantics", type.semantics)
            put("functions", type.functions)
            put("tags", JsonArray(type.tags.map { JsonPrimitive(it) }))
        }
    }).toString()
}
