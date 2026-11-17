package com.aharou.feature.agent.data

import com.aharou.core.config.ConfigCollection
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRisk
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.feature.agent.domain.skill.SkillConfigRepository
import com.aharou.feature.agent.domain.skill.SkillRepository
import com.aharou.feature.agent.domain.skill.SkillScope
import com.aharou.feature.agent.domain.skill.SkillForm
import com.aharou.feature.agent.domain.skill.SkillType
import com.aharou.feature.agent.domain.skill.SkillTypeRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 技能启停 → 配置通道（集合式，只读列表 + 单个可写开关）。
 *
 * 技能本体是磁盘目录里的 SKILL.md，config 通道不负责新建/删除技能（那是文件操作，
 * 该走文件工具），只暴露「启用/停用」。仓库只持久化禁用名单，生效集合是全局与项目
 * 的并集，读不出也写不回单个作用域，所以：
 *  - 读：该技能是否在生效禁用集合里
 *  - 写：改**全局**名单（项目级保持原样，避免覆盖掉随工作区走的那份配置）
 */
@Singleton
class ConfigSkillFields @Inject constructor(
    private val skillRepository: SkillRepository,
    private val skillConfig: SkillConfigRepository,
    private val skillTypes: SkillTypeRepository = skillRepository.skillTypes,
) {

    private var sessionWorkspace: String? = null
    fun forWorkspace(root: String): ConfigSkillFields = ConfigSkillFields(skillRepository, skillConfig, skillTypes)
        .also { it.sessionWorkspace = root }
    private fun checkWorkspace() {
        sessionWorkspace?.let { if (it != skillTypes.workspaceKey().orEmpty()) throw ConfigError.InvalidValue("Workspace changed; reopen this session's project") }
    }

    fun registerInto(registry: ConfigRegistry) {
        registry.register(com.aharou.core.config.fields.ReadOnlyField(
            path = "skill.builtin_edit_enabled", displayName = "Builtin skill editing permission",
            description = "User-only permission in Special Permissions; config cannot enable it.",
            valueSchema = ConfigSchema.Bool, risk = com.aharou.core.config.ConfigRisk.SENSITIVE,
            reader = { ConfigValue.Bool(com.aharou.core.config.BuiltinSkillEditCapability.enabled.value) }))
        registry.register(SkillCollection())
        registry.register(TypeCollection(SkillScope.GLOBAL, "skill.types"))
        registry.register(TypeCollection(SkillScope.PROJECT, "skill.project_types"))
    }

    private fun skillNames(): List<String> {
        checkWorkspace()
        return skillRepository.listAllSkills()
            .filter { it.scope != SkillScope.BUILTIN || com.aharou.core.config.BuiltinSkillEditCapability.enabled.value }
            .map { it.skill.name }
    }

    private fun skill(name: String) = skillRepository.listAllSkills().firstOrNull { it.skill.name == name }
        ?: throw ConfigError.InvalidValue("Unknown skill")

    private fun metadataFields(id: String, base: String): List<ConfigField> {
        fun update(transform: (SkillForm) -> SkillForm) {
            val entry = skill(id)
            val s = entry.skill
            val form = SkillForm(s.name, s.description, s.instructions, s.requiredTools, s.keywordEnabled, s.keywords, s.tags, s.typeId)
            skillRepository.save(transform(form), entry.scope, s.name)?.let {
                throw ConfigError.InvalidValue("Skill update rejected: $it")
            }
        }
        return listOf(
            ClosureField(path = "$base.$id.required_tools", displayName = "required_tools",
                description = "Tool binding metadata; does not grant permissions.", risk = ConfigRisk.SENSITIVE,
                valueSchema = ConfigSchema.Array(ConfigSchema.Str()),
                reader = { ConfigValue.Arr(skill(id).skill.requiredTools.map { ConfigValue.Str(it) }) },
                writer = { value -> checkWorkspace(); update { it.copy(requiredTools = strings(value)) } }),
            com.aharou.core.config.fields.ReadOnlyField(path = "$base.$id.resources", displayName = "resources",
                description = "Exact relative resource paths; read or execute with existing file and shell tools under their permission policy.",
                valueSchema = ConfigSchema.Array(ConfigSchema.Str()),
                reader = { ConfigValue.Arr(skillRepository.resourcePaths(skill(id).skill).map { ConfigValue.Str(it) }) }),
            ClosureField(path = "$base.$id.resource_edits", displayName = "resource_edits",
                description = "Object of relative path to UTF-8 text. Adds/updates only these files, never deletes other resources. Absolute/traversal/symlink escape paths are rejected.",
                valueSchema = ConfigSchema.Json, risk = ConfigRisk.SENSITIVE,
                reader = { ConfigValue.Obj(emptyMap()) }, writer = { value ->
                    checkWorkspace()
                    val entries = (value as? ConfigValue.Obj)?.value ?: throw ConfigError.TypeMismatch("object")
                    val edits = entries.mapValues { (_, v) -> (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string") }
                    update { it.copy(resourceEdits = edits) }
                }),
            ClosureField(path = "$base.$id.tags", displayName = "tags", description = "Exact-match discovery tags; independent from trigger keywords.",
                valueSchema = ConfigSchema.Array(ConfigSchema.Str()),
                reader = { ConfigValue.Arr(skill(id).skill.tags.map { ConfigValue.Str(it) }) },
                writer = { value -> checkWorkspace(); update { it.copy(tags = strings(value)) } }),
            ClosureField(path = "$base.$id.type_id", displayName = "type_id", description = "Existing type id; empty string removes classification.", risk = ConfigRisk.SENSITIVE,
                valueSchema = ConfigSchema.Str(),
                reader = { ConfigValue.Str(skill(id).skill.typeId.orEmpty()) },
                writer = { value ->
                    checkWorkspace()
                    val type = (value as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                    update { it.copy(typeId = type.takeIf { id -> id.isNotBlank() }) }
                })
        )
    }

    private fun strings(value: ConfigValue): List<String> = (value as? ConfigValue.Arr)?.value?.map {
        (it as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
    } ?: throw ConfigError.TypeMismatch("array")

    private inner class TypeCollection(private val scope: SkillScope, override val basePath: String) : ConfigCollection {
        override val displayName = "Skill types"
        override val description = "Reusable type guidance in this scope. Project types override matching global ids."
        override val removable = false
        override fun childIds(): List<String> { checkWorkspace(); return skillTypes.listLocal(scope).map { it.id } }
        override fun remove(id: String) { throw ConfigError.InvalidValue("Type removal is not supported; references must remain valid") }
        override fun add(payload: ConfigValue): String {
            checkWorkspace()
            val expectedWorkspace = sessionWorkspace ?: skillTypes.workspaceKey()
            val fields = (payload as? ConfigValue.Obj)?.value ?: throw ConfigError.TypeMismatch("object")
            fun text(key: String): String = (fields[key] as? ConfigValue.Str)?.value ?: throw ConfigError.InvalidValue("Missing $key")
            val id = text("id")
            if (id in childIds()) throw ConfigError.InvalidValue("Type already exists; edit its fields")
            skillTypes.save(SkillType(id, text("name"), text("semantics"), text("functions"), fields["tags"]?.let(::strings).orEmpty()), scope, expectedWorkspace)
            return id
        }
        override fun fields(forId: String): List<ConfigField> {
            if (forId !in childIds()) return emptyList()
            val expectedWorkspace = skillTypes.workspaceKey()
            fun current(): SkillType {
                checkWorkspace()
                if (scope == SkillScope.PROJECT && expectedWorkspace != skillTypes.workspaceKey()) throw ConfigError.InvalidValue("Workspace changed; read type again")
                return skillTypes.listLocal(scope).firstOrNull { it.id == forId } ?: throw ConfigError.InvalidValue("Unknown type")
            }
            fun text(key: String, read: (SkillType) -> String, change: (SkillType, String) -> SkillType) = ClosureField(
                path = "$basePath.$forId.$key", displayName = key, description = "Reusable skill type $key", valueSchema = ConfigSchema.Str(), risk = ConfigRisk.SENSITIVE,
                reader = { ConfigValue.Str(read(current())) }, writer = { value ->
                    val string = (value as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                    skillTypes.save(change(current(), string), scope, expectedWorkspace)
                })
            return listOf(text("name", { it.name }, { t, v -> t.copy(name = v) }),
                text("semantics", { it.semantics }, { t, v -> t.copy(semantics = v) }),
                text("functions", { it.functions }, { t, v -> t.copy(functions = v) }),
                ClosureField(path = "$basePath.$forId.tags", displayName = "tags", description = "Type tags", valueSchema = ConfigSchema.Array(ConfigSchema.Str()),
                    reader = { ConfigValue.Arr(current().tags.map { ConfigValue.Str(it) }) },
                    writer = { skillTypes.save(current().copy(tags = strings(it)), scope, expectedWorkspace) }))
        }
    }

    private inner class SkillCollection : ConfigCollection {
        override val basePath = "skill.skills"
        override val displayName = "技能"
        override val description =
            "Installed skills including builtin instructions; builtin writes require the user-only permission. User skill fields also include disabled and keywords."
        override val addable = false
        override val removable = false

        override fun childIds(): List<String> = skillNames()

        override fun fields(forId: String): List<ConfigField> {
            if (forId !in skillNames()) return emptyList()
            val content = ClosureField(
                path = "$basePath.$forId.instructions", displayName = "Skill instructions",
                description = "Builtin edits require user permission and save a global override.",
                valueSchema = ConfigSchema.Str(), risk = com.aharou.core.config.ConfigRisk.SENSITIVE,
                reader = { ConfigValue.Str(skill(forId).skill.instructions) }, writer = { value ->
                    checkWorkspace()
                    val entry = skill(forId); val s = entry.skill
                    val text = (value as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                    skillRepository.save(com.aharou.feature.agent.domain.skill.SkillForm(s.name, s.description, text, s.requiredTools, s.keywordEnabled, s.keywords, s.tags, s.typeId),
                        entry.scope, s.name)?.let { throw ConfigError.PermissionDenied("Skill update rejected: $it") }
                })
            if (skill(forId).scope == SkillScope.BUILTIN) return listOf(content) + metadataFields(forId, basePath)
            val editableContent = if (skillRepository.isBuiltinName(forId) && !com.aharou.core.config.BuiltinSkillEditCapability.enabled.value)
                emptyList() else listOf(content)
            return editableContent + metadataFields(forId, basePath) + listOf(
                ClosureField(
                    path = "$basePath.$forId.keyword_enabled",
                    displayName = "关键词公共模式",
                    description = "按当前用户/派发任务匹配任一关键词；已有成员绑定时拒绝，须先明确解除绑定。",
                    valueSchema = ConfigSchema.Bool,
                    revertable = true,
                    reader = { ConfigValue.Bool(skill(forId).skill.keywordEnabled) },
                    writer = { value ->
                        val entry = skill(forId)
                        skillRepository.setKeywordPolicy(forId, entry.scope,
                            (value as? ConfigValue.Bool)?.value ?: throw ConfigError.TypeMismatch("boolean"), entry.skill.keywords)
                    },
                ),
                ClosureField(
                    path = "$basePath.$forId.keywords",
                    displayName = "关键词",
                    description = "非空关键词数组，任一词匹配当前任务即注入名称与简介，不注入正文。",
                    valueSchema = ConfigSchema.Array(ConfigSchema.Str()),
                    revertable = true,
                    reader = { ConfigValue.Arr(skill(forId).skill.keywords.map { ConfigValue.Str(it) }) },
                    writer = { value ->
                        val words = (value as? ConfigValue.Arr)?.value?.map {
                            (it as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                        } ?: throw ConfigError.TypeMismatch("array")
                        val entry = skill(forId)
                        skillRepository.setKeywordPolicy(forId, entry.scope, entry.skill.keywordEnabled, words)
                    },
                ),
                ClosureField(
                    path = "$basePath.$forId.disabled",
                    displayName = "停用（$forId）",
                    description =
                        "全局作用域是否停用。读写同一全局名单；项目级名单独立，最终执行仍取两者并集。",
                    valueSchema = ConfigSchema.Bool,
                    revertable = true,
                    reader = { ConfigValue.Bool(forId.lowercase() in skillConfig.disabledNames(SkillScope.GLOBAL)) },
                    writer = { v ->
                        val b = (v as? ConfigValue.Bool)?.value
                            ?: throw ConfigError.TypeMismatch("boolean")
                        skillConfig.setDisabled(forId, b, SkillScope.GLOBAL)
                    },
                ),
            )
        }

        override fun add(payload: ConfigValue): String =
            throw ConfigError.InvalidValue("技能由 SKILL.md 目录决定，请直接创建技能目录")

        override fun remove(id: String) {
            throw ConfigError.InvalidValue("技能由 SKILL.md 目录决定，请直接删除技能目录")
        }
    }
}
