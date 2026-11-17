package com.aharou.feature.agent.domain.skill

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.common.NameValidation
import com.aharou.feature.workspace.domain.FileAccessProvider
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Skill 仓库，聚合各 [SkillSource]（内置 + 全局目录 + 项目目录）提供的技能，
 * 并按 [SkillConfigRepository] 的禁用名单过滤注入清单。
 *
 * 全局技能 `~/.aharou/skills`、项目级技能 `<workspace>/.aharou/skills`，两者都经 [FileAccessProvider]
 * 以容器路径读写，跟随当前执行环境（本地宿主目录或远程 SSH 工作区）；内置技能随 App 打包，只读。
 */
@Singleton
class SkillRepository @Inject constructor(
    private val builtinSkillSource: BuiltinSkillSource,
    private val globalDirectorySkillSource: GlobalDirectorySkillSource,
    private val projectDirectorySkillSource: ProjectDirectorySkillSource,
    private val skillConfigRepository: SkillConfigRepository,
    private val fileAccess: FileAccessProvider,
    private val agents: com.aharou.feature.agent.domain.subagent.AgentDefinitionRepository,
    val skillTypes: SkillTypeRepository = SkillTypeRepository(fileAccess)
) {
    /** 全部技能（含来源作用域），未过滤禁用；同名按 内置 < 全局 < 项目 逐级覆盖。 */
    fun isBuiltinName(name: String): Boolean = builtinSkillSource.listSkills().any { it.name.equals(name.trim(), true) }
    private fun mayModify(name: String): Boolean = !isBuiltinName(name) || com.aharou.core.config.BuiltinSkillEditCapability.enabled.value

    fun listAllSkills(): List<SkillEntry> = mergeAll(
        visibleBuiltinSkills(),
        globalDirectorySkillSource.listSkills(),
        projectDirectorySkillSource.listSkills()
    )

    /** 启用的技能列表（注入系统提示词用），禁用技能被过滤。 */
    fun listSkills(): List<Skill> = listSkillsForAgent(null)

    fun listCommandSkills(): List<Skill> = synchronized(SkillParser) {
        val exclusive = agents.listAll().flatMap { it.definition.skills }.map { it.lowercase() }.toSet()
        filterDisabled(listAllSkills(), skillConfigRepository.disabledNames()).map { it.skill }
            .filterNot { it.name.lowercase() in exclusive }
    }

    /** Ownership is derived from every registered role, including disabled roles. */
    fun listSkillsForAgent(
        definition: com.aharou.feature.agent.domain.subagent.AgentDefinition?,
        currentTask: String = "",
        includePublic: Boolean = true
    ): List<Skill> = synchronized(SkillParser) {
        val definitions = agents.listAll().map { it.definition }
        val exclusive = definitions.flatMap { it.skills }.map { it.lowercase() }.toSet()
        val own = definition?.let { role ->
            definitions.firstOrNull { it.name.equals(role.name, ignoreCase = true) }?.skills
        }.orEmpty().map { it.lowercase() }.toSet()
        return@synchronized filterDisabled(listAllSkills(), skillConfigRepository.disabledNames()).map { it.skill }.filter { skill ->
            if (skill.name.lowercase() in exclusive) skill.name.lowercase() in own && !skill.keywordEnabled
            else (!skill.keywordEnabled && includePublic) || (skill.keywordEnabled && skill.keywords.any {
                it.isNotBlank() && currentTask.contains(it, ignoreCase = true)
            })
        }
    }

    /** Atomically switches keyword mode; any failed write restores all touched files. */
    fun setKeywordPolicy(name: String, scope: SkillScope, enabled: Boolean, keywords: List<String>, confirmUnbind: Boolean = false) {
        synchronized(SkillParser) {
            require(scope != SkillScope.BUILTIN) { "Builtin skills are read-only" }
            val words = keywords.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            require(validKeywordPolicy(enabled, words)) { "At least one keyword is required" }
            val skill = entriesIn(scope).firstOrNull { it.name.equals(name, true) } ?: error("Skill not found")
            val bound = agents.listAll().filter { entry -> entry.definition.skills.any { it.equals(name, true) } }
            require(canEnableKeywords(enabled && bound.isNotEmpty(), confirmUnbind)) { "Confirm removing member bindings first" }
            val skillFile = instructionFile(fileAccess, skill.dirPath ?: error("Missing skill directory")) ?: error("Missing skill file")
            val originals = linkedMapOf(skillFile to fileAccess.readFile(skillFile))
            if (enabled) bound.forEach { entry ->
                val path = entry.definition.filePath ?: error("Missing member file")
                originals[path] = fileAccess.readFile(path)
            }
            try {
                if (enabled) bound.forEach { entry ->
                    check(agents.setSkillBinding(entry.definition.name, entry.scope, name, false) == null) { "Failed to remove member binding" }
                }
                // Patch only these frontmatter keys, preserving translated descriptions and other metadata.
                val original = originals.getValue(skillFile).replace("\r\n", "\n")
                val end = if (original.startsWith("---\n")) original.indexOf("\n---", 3) else -1
                val metadata = if (end >= 0) original.substring(4, end) else ""
                val body = if (end >= 0) original.substring(end + 4).removePrefix("\n") else original
                val yaml = org.yaml.snakeyaml.Yaml()
                val fields = (yaml.load<Map<String, Any>>(metadata) ?: emptyMap()).toMutableMap()
                fields["keyword_enabled"] = enabled
                fields["keywords"] = words
                fileAccess.writeFile(skillFile, "---\n${yaml.dump(fields)}---\n$body", overwrite = true)
            } catch (failure: Exception) {
                originals.entries.toList().asReversed().forEach { (path, text) ->
                    try { fileAccess.writeFile(path, text, overwrite = true) }
                    catch (rollback: Exception) { failure.addSuppressed(rollback) }
                }
                throw failure
            }
        }
    }

    fun loadInstructionsForAgent(
        name: String,
        definition: com.aharou.feature.agent.domain.subagent.AgentDefinition?,
        currentTask: String,
        includePublic: Boolean
    ): String? = synchronized(SkillParser) {
        if (listSkillsForAgent(definition, currentTask, includePublic).none { it.name == name }) null
        else loadInstructions(name)
    }

    /** 读取指定 skill 的完整指令正文；不存在 / 解析失败 / 已被禁用时返回 null。 */
    fun loadInstructions(name: String): String? {
        if (name.lowercase() in skillConfigRepository.disabledNames()) return null
        val projectBody = projectDirectorySkillSource.loadInstructions(name)
        val globalBody = if (projectBody == null) globalDirectorySkillSource.loadInstructions(name) else null
        val body = (projectBody ?: globalBody
            ?: if (name.lowercase() in deletedBuiltinNames()) null else builtinSkillSource.loadInstructions(name))
            ?: return null
        val scope = when {
            projectBody != null -> SkillScope.PROJECT
            globalBody != null -> SkillScope.GLOBAL
            else -> SkillScope.BUILTIN
        }
        val skill = entriesIn(scope).firstOrNull { it.name.equals(name, true) }
        val type = skill?.typeId?.let { skillTypes.get(it, scope) ?: return null }
        val content = listOfNotNull(type?.semantics, type?.functions, body).filter { it.isNotBlank() }.joinToString("\n\n")
        return content + (skill?.dirPath?.let { SkillResources.manifest(fileAccess, it, skill.requiredTools) }
            ?: if (skill?.requiredTools.isNullOrEmpty()) "" else "\n\nRequired tools (no permission grant): ${skill?.requiredTools?.joinToString(", ")}")
    }

    private fun builtinFiles(name: String): Map<String, ByteArray> {
        val files = builtinSkillSource.resourceFiles(name)
        check(files.isNotEmpty()) { "Selected builtin version is unavailable" }
        return files.mapKeys { (path, _) -> path.substringAfter('/') }.also { entries ->
            entries.keys.filterNot { it.equals(INSTRUCTION_FILE, true) || it.equals("CLAUDE.md", true) }.forEach(SkillResources::relativePath)
        }
    }

    fun resourcePaths(skill: Skill): List<String> = skill.dirPath?.let { SkillResources.list(fileAccess, it) }
        ?: builtinFiles(skill.name).keys.filterNot { it.equals(INSTRUCTION_FILE, true) || it.equals("CLAUDE.md", true) }.sorted()

    fun resourceTexts(skill: Skill, paths: List<String>): Map<String, String> =
        skill.dirPath?.let { SkillResources.texts(fileAccess, it, paths) } ?: run {
            val files = builtinFiles(skill.name)
            var total = 0
            buildMap {
                for (path in paths) {
                    SkillResources.relativePath(path)
                    if (path.substringAfterLast('.', "").lowercase() !in setOf("md", "txt", "py", "sh", "js", "ts", "json", "yaml", "yml", "toml", "kt", "xml", "csv", "html", "css", "sql")) continue
                    val bytes = files[path] ?: error("Selected builtin resource is unavailable")
                    if (bytes.size > 256 * 1024 || total + bytes.size > 1024 * 1024 || bytes.any { it == 0.toByte() }) continue
                    val text = bytes.toString(Charsets.UTF_8)
                    if (!text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) continue
                    total += bytes.size
                    put(path, text)
                }
            }
        }

    /** 技能是否在任一作用域中被禁用。 */
    fun isSkillDisabled(name: String): Boolean =
        name.lowercase() in skillConfigRepository.disabledNames()

    /** 在指定作用域启用/禁用某个技能。内置技能不可停用，直接忽略。 */
    fun setSkillDisabled(name: String, disabled: Boolean, scope: SkillScope) {
        if (scope == SkillScope.BUILTIN) return
        skillConfigRepository.setEffectiveDisabled(name, disabled, scope)
    }

    /**
     * 写入技能文件（新建或编辑）。[originalName] 为编辑前的名称，新建时传 null；返回 null 表示成功。
     *
     * 编辑时写回原目录里的原指令文件（可能叫 CLAUDE.md），改名只改 frontmatter 的 name、不动目录名——
     * 技能正文常按 `~/.aharou/skills/<目录>/run.py` 引用同目录脚本，跟着改名会把这些引用打断。
     */
    fun save(form: SkillForm, scope: SkillScope, originalName: String? = null): SkillSaveError? = synchronized(com.aharou.core.config.BuiltinSkillEditCapability) { synchronized(SkillParser) save@ {
        if (!mayModify(form.name) || originalName?.let { !mayModify(it) } == true) return@save SkillSaveError.READ_ONLY
        if (scope == SkillScope.BUILTIN) {
            if (!isBuiltinName(form.name) || originalName != null && !originalName.equals(form.name, true)) return@save SkillSaveError.READ_ONLY
            return@save save(form, SkillScope.GLOBAL, originalName ?: form.name)
        }
        val name = form.name.trim()
        if (!isValidName(name)) return@save SkillSaveError.INVALID_NAME
        if (form.instructions.isBlank()) return@save SkillSaveError.EMPTY_INSTRUCTIONS
        val typeId = form.typeId?.trim()?.takeIf { it.isNotEmpty() }
        if (typeId != null && skillTypes.get(typeId, scope) == null) return@save SkillSaveError.UNKNOWN_TYPE
        val tags = form.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val scopeSkills = entriesIn(scope)
        val keywordEnabled = form.keywordEnabled
        val keywords = form.keywords.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (!validKeywordPolicy(keywordEnabled, keywords)) return@save SkillSaveError.EMPTY_KEYWORDS
        if (keywordEnabled && agents.listAll().any { entry ->
            entry.definition.skills.any { it.equals(originalName ?: name, true) || it.equals(name, true) }
        }) return@save SkillSaveError.BOUND_KEYWORD_POLICY
        val keepingName = originalName != null && originalName.equals(name, ignoreCase = true)
        if (originalName != null && !keepingName && agents.listAll().any { entry ->
            entry.definition.skills.any { it.equals(originalName, ignoreCase = true) }
        }) return@save SkillSaveError.BOUND_RENAME
        if (!keepingName && scopeSkills.any { it.name.equals(name, ignoreCase = true) }) {
            return@save SkillSaveError.NAME_CONFLICT
        }

        val existingDir = originalName?.let { old ->
            scopeSkills.firstOrNull { it.name.equals(old, ignoreCase = true) }?.dirPath
        }

        val text = SkillParser.serialize(
            name = name,
            description = form.description,
            requiredTools = form.requiredTools,
            instructions = form.instructions,
            keywordEnabled = keywordEnabled,
            keywords = keywords,
            tags = tags,
            typeId = typeId
        )

        val provider = fileAccess.checked()
        return@save try {
            SkillResources.validateEdits(form.resourceEdits)
            val dir = existingDir?.takeIf { provider.isDirectory(it) } ?: "${skillsRoot(scope)}/$name"
            provider.mkdirs(dir)
            val seed = if (scope == SkillScope.GLOBAL && existingDir == null && originalName?.let(::isBuiltinName) == true)
                builtinFiles(requireNotNull(originalName)) else emptyMap()
            val resourceBytes = seed.filterKeys { !it.equals(INSTRUCTION_FILE, true) && !it.equals("CLAUDE.md", true) } +
                form.resourceEdits.mapValues { (_, text) -> text.toByteArray(Charsets.UTF_8) }
            val resourceTargets = resourceBytes.mapKeys { (relative, _) -> SkillResources.path(provider, dir, relative) }
            val resourceOriginals = resourceTargets.mapValues { (path, _) -> if (provider.exists(path)) provider.readBytes(path) else null }
            val target = instructionFile(provider, dir) ?: "${dir.trimEnd('/')}/$INSTRUCTION_FILE"
            val original = if (provider.exists(target)) provider.readFile(target) else null
            // Keep unknown/translated frontmatter. Only fields owned by this form are patched.
            val source = original ?: seed[INSTRUCTION_FILE]?.toString(Charsets.UTF_8)
            val updated = if (source == null) text else {
                val normalized = source.replace("\r\n", "\n")
                val end = if (normalized.startsWith("---\n")) normalized.indexOf("\n---", 3) else -1
                val yaml = org.yaml.snakeyaml.Yaml()
                val fields = if (end >= 0) {
                    (yaml.load<Map<String, Any>>(normalized.substring(4, end)) ?: emptyMap()).toMutableMap()
                } else mutableMapOf()
                fields["name"] = name
                fields["description"] = form.description
                fields["required_tools"] = form.requiredTools
                fields["keyword_enabled"] = keywordEnabled
                fields["keywords"] = keywords
                fields["tags"] = tags
                if (typeId == null) fields.remove("type_id") else fields["type_id"] = typeId
                "---\n${yaml.dump(fields)}---\n${form.instructions.trim()}\n"
            }
            try {
                resourceTargets.forEach { (path, content) -> provider.writeBytes(path, content, overwrite = true) }
                provider.writeFile(target, updated, overwrite = true)
            } catch (failure: Exception) {
                resourceOriginals.forEach { (path, bytes) ->
                    try { if (bytes == null) provider.delete(path) else provider.writeBytes(path, bytes, overwrite = true) }
                    catch (rollback: Exception) { failure.addSuppressed(rollback) }
                }
                try {
                    if (original != null) provider.writeFile(target, original, overwrite = true) else if (provider.exists(target)) provider.delete(target)
                } catch (rollback: Exception) { failure.addSuppressed(rollback) }
                throw failure
            }
            null
        } catch (e: Exception) {
            FileLogger.e(TAG, "保存技能失败: $name", e)
            SkillSaveError.IO_FAILED
        }
    } }

    /** 指定作用域的技能根目录（容器路径）。内置技能随 App 打包，没有可写目录。 */
    fun skillsRoot(scope: SkillScope): String = when (scope) {
        SkillScope.GLOBAL -> globalDirectorySkillSource.skillsRoot
        SkillScope.PROJECT -> projectDirectorySkillSource.skillsRoot
        SkillScope.BUILTIN -> error("内置技能随 App 打包，没有可写目录")
    }

    /**
     * 从 Markdown 文本导入技能到指定作用域；[fallbackName] 为 frontmatter 缺 name 时的兜底名。
     * 名称非法 / 同名冲突 / 正文为空时整体失败，不落盘；[overwrite] 为 true 时同名直接覆盖。
     */
    fun importMarkdown(
        text: String,
        fallbackName: String,
        scope: SkillScope,
        overwrite: Boolean = false,
        expectedWorkspace: String? = null,
        authorizeWrite: (String) -> Unit = {},
        boundAccess: FileAccessProvider? = null,
    ): SkillImportReport = synchronized(com.aharou.core.config.BuiltinSkillEditCapability) {
        val access = boundAccess ?: (expectedWorkspace?.let(fileAccess::forWorkspace) ?: fileAccess).checked()
        val root = skillsRoot(scope)
        val existing = importEntries(access, root)
        SkillImporter.importMarkdown(
            guardedImportAccess(scope, access, root, existing, authorizeWrite), root, existing.map { it.name.lowercase() }.toSet(), text, fallbackName, overwrite, ::mayModify
        )
    }

    /** 从 zip 输入流导入技能（可含多个技能目录）到指定作用域。[overwrite] 同 [importMarkdown]。 */
    fun importZip(
        input: InputStream,
        fallbackName: String,
        scope: SkillScope,
        overwrite: Boolean = false,
        expectedWorkspace: String? = null,
        authorizeWrite: (String) -> Unit = {},
        boundAccess: FileAccessProvider? = null,
    ): SkillImportReport = synchronized(com.aharou.core.config.BuiltinSkillEditCapability) {
        val access = boundAccess ?: (expectedWorkspace?.let(fileAccess::forWorkspace) ?: fileAccess).checked()
        val root = skillsRoot(scope)
        val existing = importEntries(access, root)
        SkillImporter.importArchive(
            guardedImportAccess(scope, access, root, existing, authorizeWrite), root, existing.map { it.name.lowercase() }.toSet(), input, fallbackName, overwrite, ::mayModify,
            authorizeWrite = { path -> authorizeWrite(access.toDisplayPath(path)) },
            restoreFile = { path, bytes ->
                access.requireResourcePath(root, path.removePrefix(root.trimEnd('/') + "/"))
                if (bytes == null) {
                    if (access.exists(path)) access.delete(path)
                    check(!access.exists(path)) { "Imported file cleanup failed" }
                } else {
                    access.writeBytes(path, bytes, overwrite = true)
                    check(access.readBytes(path).contentEquals(bytes)) { "Imported file restore failed" }
                }
            },
        )
    }

    /**
     * 指定作用域里真实存在的技能。**必须按来源直查**：`listAllSkills()` 是合并视图，
     * 同名技能只留优先级最高的那一条，用它按作用域找实体时低优先级那侧会被整条藏掉——
     * 结果就是「目标已有同名」判不出来，删除 / 导出也定位不到目录。
     */
    private fun importEntries(access: FileAccessProvider, root: String): List<Skill> {
        if (!access.exists(root)) return emptyList()
        return access.listFilesRecursive(root, 4)
            .filter { it.substringAfterLast('/').equals("SKILL.md", true) || it.substringAfterLast('/').equals("CLAUDE.md", true) }
            .map { relative ->
                val dir = relative.substringBeforeLast('/', "")
                val target = if (dir.isBlank()) root else "$root/$dir"
                access.requireResourcePath(root, relative)
                SkillParser.parseText(access.readFile("$root/$relative"), target.substringAfterLast('/')).copy(dirPath = target)
            }
    }

    private fun guardedImportAccess(
        scope: SkillScope, access: FileAccessProvider, skillRoot: String, existing: List<Skill>, authorizeWrite: (String) -> Unit,
    ): FileAccessProvider = object : FileAccessProvider by access {
        private fun checkWrite(path: String, content: String?) {
            val root = skillRoot.trimEnd('/') + "/"
            require(path.startsWith(root)) { "Import path escapes skill root" }
            val relative = path.removePrefix(root)
            val parts = relative.split('/')
            require(parts.size >= 2 && parts.all { it.isNotBlank() && it != "." && it != ".." && '\\' !in it })
            val name = parts.first()
            access.requireResourcePath(root.trimEnd('/'), relative)
            check(mayModify(name)) { "Builtin skill editing is disabled" }
            existing.filter { it.dirPath != null && path.startsWith(it.dirPath!!.trimEnd('/') + "/") }
                .forEach { check(mayModify(it.name)) { "Builtin skill editing is disabled" } }
            if (content != null && path.substringAfterLast('/') in setOf("SKILL.md", "CLAUDE.md")) {
                val parsed = SkillParser.parseText(content, name)
                check(mayModify(parsed.name)) { "Builtin skill editing is disabled" }
                check(parsed.typeId == null || SkillTypeRepository(access).get(parsed.typeId, scope) != null) { "Unknown skill type" }
            }
        }
        override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: java.nio.charset.Charset) {
            checkWrite(path, content)
            authorizeWrite(access.toDisplayPath(path))
            access.writeFile(path, content, overwrite, encoding)
        }
        override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) {
            checkWrite(path, bytes.toString(Charsets.UTF_8))
            authorizeWrite(access.toDisplayPath(path))
            access.writeBytes(path, bytes, overwrite)
        }
    }

    private fun entriesIn(scope: SkillScope): List<Skill> = when (scope) {
        SkillScope.BUILTIN -> visibleBuiltinSkills()
        SkillScope.GLOBAL -> globalDirectorySkillSource.listSkills()
        SkillScope.PROJECT -> projectDirectorySkillSource.listSkills()
    }

    /** 指定作用域下已有技能名（小写），供导入查重。 */
    private fun existingNamesIn(scope: SkillScope): Set<String> =
        entriesIn(scope).map { it.name.lowercase() }.toSet()

    /**
     * 把指定作用域的技能整个导出成一个 zip（见 [SkillExporter]），技能不存在或读不到时返回 null。
     *
     * 内置技能从用户已选定的磁盘版本完整导出，不与当前 APK 的资源混合。
     */
    fun exportZip(name: String, scope: SkillScope): ByteArray? {
        if (scope == SkillScope.BUILTIN) return exportBuiltin(name)
        val skill = entriesIn(scope).firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return null
        val dirPath = skill.dirPath ?: return null
        return SkillExporter.zip(fileAccess, dirPath, skill.name)
    }

    private fun exportBuiltin(name: String): ByteArray? {
        val skill = visibleBuiltinSkills()
            .firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return null
        return SkillExporter.zipOf(skill.name, builtinFiles(skill.name))
    }

    private val deletedBuiltinsPath = "~/.aharou/skills.deleted-builtins.json"

    private fun deletedBuiltinNames(): Set<String> {
        if (!fileAccess.isFile(deletedBuiltinsPath)) return emptySet()
        val entries = kotlinx.serialization.json.Json.parseToJsonElement(fileAccess.readFile(deletedBuiltinsPath))
        require(entries is kotlinx.serialization.json.JsonArray)
        return entries.map {
            require(it is kotlinx.serialization.json.JsonPrimitive && it.isString)
            it.content.lowercase()
        }.toSet()
    }

    private fun visibleBuiltinSkills(): List<Skill> {
        val deleted = deletedBuiltinNames()
        return builtinSkillSource.listSkills().filterNot { it.name.lowercase() in deleted }
    }

    /** APK 资产不可写；内置技能保存删除记录，其余技能删除目录。 */
    fun deleteSkill(name: String, scope: SkillScope): Boolean {
        return synchronized(com.aharou.core.config.BuiltinSkillEditCapability) {
        if (scope != SkillScope.BUILTIN && !mayModify(name)) return false
        if (scope == SkillScope.BUILTIN) return synchronized(this) {
            if (builtinSkillSource.listSkills().none { it.name.equals(name, true) }) return@synchronized false
            val deleted = deletedBuiltinNames() + name.lowercase()
            val json = kotlinx.serialization.json.JsonArray(deleted.sorted().map { kotlinx.serialization.json.JsonPrimitive(it) })
            fileAccess.writeFile(deletedBuiltinsPath, json.toString(), overwrite = true)
            true
        }
        val dirPath = entriesIn(scope).firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?.dirPath ?: return false
        return safeDeleteSkillDir(fileAccess, dirPath)
        }
    }

    /**
     * 在全局与当前项目之间搬一个技能：直接搬它的目录（[FileAccessProvider.move]），目录名原样带走，
     * 不同名字的技能互不干扰。目标位置已有同名技能时不覆盖，直接返回 [SkillMoveResult.NAME_CONFLICT]。
     *
     * 用「搬目录」而不是「导出成 zip 再导入」：后者要经过打包、解包、逐个文件写回三道关，
     * 任何一道静默失败都会变成“什么都没发生”，而且目录名会被换成技能名——技能名与目录名不一致时就搬歪了。
     */
    fun moveSkill(name: String, fromScope: SkillScope, toScope: SkillScope): SkillMoveResult {
        return synchronized(com.aharou.core.config.BuiltinSkillEditCapability) {
        if (!mayModify(name)) return SkillMoveResult.READ_ONLY
        if (fromScope == SkillScope.BUILTIN || toScope == SkillScope.BUILTIN) return SkillMoveResult.READ_ONLY
        if (fromScope == toScope) return SkillMoveResult.FAILED
        if (name.lowercase() in existingNamesIn(toScope)) return SkillMoveResult.NAME_CONFLICT

        val sourceSkill = entriesIn(fromScope).firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (sourceSkill?.typeId?.let { skillTypes.get(it, toScope) == null } == true) return SkillMoveResult.FAILED
        val source = sourceSkill?.dirPath
        if (source.isNullOrBlank()) {
            FileLogger.w(TAG, "移动技能失败（找不到源目录）：$name ($fromScope)")
            return SkillMoveResult.FAILED
        }

        val dirName = source.trimEnd('/').substringAfterLast('/')
        val target = "${skillsRoot(toScope).trimEnd('/')}/$dirName"
        return try {
            fileAccess.move(source, target, overwrite = false)
            FileLogger.i(TAG, "移动技能成功：$name $source -> $target")
            SkillMoveResult.OK
        } catch (e: Exception) {
            FileLogger.e(TAG, "移动技能失败：$name ($source -> $target)", e)
            SkillMoveResult.FAILED
        }
        }
    }

    companion object {
        internal fun validKeywordPolicy(enabled: Boolean, keywords: List<String>): Boolean =
            !enabled || keywords.any { it.isNotBlank() }

        internal fun canEnableKeywords(hasBindings: Boolean, confirmed: Boolean): Boolean = !hasBindings || confirmed

        private const val TAG = "SkillRepository"

        /** 新建技能时写入的指令文件名。 */
        private const val INSTRUCTION_FILE = "SKILL.md"

        /** 名称同时用作目录名，禁掉路径分隔符与保留字符；长度上限防止极端目录名。 */
        internal fun isValidName(name: String): Boolean = NameValidation.isValid(name, MAX_NAME_LENGTH)

        private const val MAX_NAME_LENGTH = 64

        /** 目录里已有的指令文件完整路径：SKILL.md 优先，其次 CLAUDE.md（与 [SkillParser.parse] 同一套回退）。 */
        internal fun instructionFile(provider: FileAccessProvider, dirPath: String): String? {
            val files = runCatching { provider.listFiles(dirPath) }.getOrNull() ?: return null
            val name = files.firstOrNull { !it.isDirectory && it.name.equals("SKILL.md", ignoreCase = true) }?.name
                ?: files.firstOrNull { !it.isDirectory && it.name.equals("CLAUDE.md", ignoreCase = true) }?.name
                ?: return null
            return "${dirPath.trimEnd('/')}/$name"
        }

        /** 合并三级来源：同名逐级覆盖（内置 < 全局 < 项目），按名称排序。 */
        internal fun mergeAll(
            builtin: List<Skill>,
            global: List<Skill>,
            project: List<Skill>
        ): List<SkillEntry> {
            val byName = LinkedHashMap<String, SkillEntry>()
            builtin.forEach { byName[it.name.lowercase()] = SkillEntry(it, SkillScope.BUILTIN) }
            global.forEach { byName[it.name.lowercase()] = SkillEntry(it, SkillScope.GLOBAL) }
            project.forEach { byName[it.name.lowercase()] = SkillEntry(it, SkillScope.PROJECT) }
            return byName.values.sortedBy { it.skill.name.lowercase() }
        }

        /** 过滤禁用技能（禁用名单已归一化为小写）。 */
        internal fun filterDisabled(entries: List<SkillEntry>, disabled: Set<String>): List<SkillEntry> =
            entries.filterNot { it.skill.name.lowercase() in disabled }

        /** 仅当目录存在且含 SKILL.md/CLAUDE.md 指令文件时才删除，避免误删非技能目录。 */
        internal fun safeDeleteSkillDir(provider: FileAccessProvider, dirPath: String): Boolean {
            if (!provider.isDirectory(dirPath)) return false
            val hasInstruction = runCatching { provider.listFiles(dirPath) }.getOrNull()?.any {
                !it.isDirectory && (it.name.equals("SKILL.md", ignoreCase = true) || it.name.equals("CLAUDE.md", ignoreCase = true))
            } ?: false
            if (!hasInstruction) return false
            return runCatching { provider.deleteRecursively(dirPath); true }.getOrDefault(false)
        }
    }
}
