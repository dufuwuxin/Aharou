package com.aharou.feature.agent.domain.memory

import com.aharou.feature.agent.domain.context.*
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.common.Frontmatter
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal object MemoryFileStorage {
    val lock = Any()
    private var defaultFolderJournal: ContextMetadataRecovery? = null
    internal var folderJournal: ContextMetadataRecovery
        get() = checkNotNull(defaultFolderJournal)
        set(value) { defaultFolderJournal = value }

    internal fun resetFolderJournal() {
        defaultFolderJournal = null
    }

    @Volatile internal var beforeMetadataWrite: (File) -> Unit = {}
    @Volatile internal var beforeMetadataCommit: (File) -> Unit = {}

    /**
     * 内容版本：全文的 SHA-256 取前 12 位十六进制。
     *
     * 用内容哈希而不是 mtime / 递增号：版本不落盘、不加数据库字段，老条目天然就有版本；
     * 内容没变版本就不变，跨设备同步、拷贝、重写同样的正文都不会产生假冲突。
     */
    fun versionOf(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .take(6)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /**
     * 乐观锁校验。返回 null 表示放行。
     *
     * [expectedVersion] 三种语义：null=不校验（后台刚读过、或人在设置页手工保存）；
     * [MemorySource.VERSION_NEW]=要求文件**不存在**；其它=必须与当前版本相等。
     */
    fun conflictOf(
        name: String,
        expectedVersion: String?,
        currentText: String?
    ): MemoryWriteResult.VersionConflict? {
        if (expectedVersion == null) return null
        val currentVersion = currentText?.let(::versionOf)
        if (expectedVersion == MemorySource.VERSION_NEW) {
            return currentVersion?.let {
                MemoryWriteResult.VersionConflict(name, it, bodyOf(currentText))
            }
        }
        if (currentVersion == null) {
            return MemoryWriteResult.VersionConflict(name, "", "", missing = true)
        }
        if (currentVersion != expectedVersion) {
            return MemoryWriteResult.VersionConflict(name, currentVersion, bodyOf(currentText))
        }
        return null
    }

    /** 记忆文件里的正文（剥掉 frontmatter）：冲突时回给模型的就是它能直接合并的那部分。 */
    private fun bodyOf(text: String?): String {
        if (text == null) return ""
        val body = runCatching {
            Frontmatter.splitAndParse(text, "MemoryFileStorage", "解析记忆失败").second
        }.getOrNull() ?: return text.trim()
        return body.trim()
    }

    /**
     * 写盘。给了 [expectedVersion] 时先在同一把锁内校验版本，不一致就不写。
     *
     * 归档、恢复归档这类「内容原样搬运」的写入不传版本，走无条件分支。
     */
    fun write(file: File, content: String, expectedVersion: String? = null): Boolean = synchronized(lock) {
        if (expectedVersion != null) {
            val current = runCatching { if (file.isFile) file.readText() else null }.getOrNull()
            if (conflictOf(file.nameWithoutExtension, expectedVersion, current) != null) {
                FileLogger.w("MemoryFileStorage", "记忆写入被版本校验拦下: ${file.name}")
                return@synchronized false
            }
        }
        var temporary: File? = null
        try {
            beforeMetadataWrite(file)
            val parent = file.absoluteFile.parentFile ?: return@synchronized false
            if (!parent.isDirectory && !parent.mkdirs()) return@synchronized false
            if (file.exists() && !file.isFile) return@synchronized false
            temporary = File.createTempFile(".memory-", ".tmp", parent)
            FileOutputStream(temporary).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
            true
        } catch (e: Exception) {
            FileLogger.w("MemoryFileStorage", "记忆写入失败: ${file.name}", e)
            false
        } finally {
            temporary?.delete()
        }
    }

    fun renameChecked(original: Memory, updated: Memory, expectedVersion: String): Boolean = synchronized(lock) {
        val source = original.file ?: return@synchronized false
        val root = source.absoluteFile.parentFile ?: return@synchronized false
        if (updated.name.isBlank() || expectedVersion.isBlank() || !ContextFolderPath.valid(updated.contextFolder)) return@synchronized false
        var temporary: File? = null
        var published: File? = null
        try {
            val currentText = source.readText()
            if (conflictOf(original.name, expectedVersion, currentText) != null) return@synchronized false
            val current = MemoryParser.parse(source, original.scope) ?: return@synchronized false
            val claimedAliases = root.listFiles().orEmpty().filter { it != source && it.extension == "md" }
                .flatMap { MemoryParser.parse(it, original.scope)?.graphAliases.orEmpty() }.toSet()
            val aliases = (current.graphAliases + updated.graphAliases + current.name).distinct()
                .filter { it in current.graphAliases || it !in claimedAliases }
            val target = MemorySource.resolveMemoryFile(root, updated.name)
            if (target.canonicalFile == source.canonicalFile) {
                if (MemorySource.archiveExistingFile(root, original.name) == null) return@synchronized false
                return@synchronized write(source, MemoryParser.format(updated.copy(
                    type = current.type, keywords = current.keywords, expiresAt = current.expiresAt, pending = current.pending,
                    graphId = com.aharou.feature.agent.domain.context.ContextGraphIdentity.raw(current),
                    graphAliases = aliases
                )), expectedVersion)
            }
            if (target.exists()) return@synchronized false
            val payload = MemoryParser.format(updated.copy(
                type = current.type, keywords = current.keywords, expiresAt = current.expiresAt, pending = current.pending,
                    graphId = com.aharou.feature.agent.domain.context.ContextGraphIdentity.raw(current),
                    graphAliases = aliases
            ))
            temporary = File.createTempFile(".memory-rename-", ".tmp", root)
            FileOutputStream(temporary).use { output ->
                output.write(payload.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (MemorySource.archiveExistingFile(root, original.name) == null) return@synchronized false
            // A hard link publishes the complete payload without ever replacing an existing target.
            Files.createLink(target.toPath(), temporary.toPath())
            published = target
            if (versionOf(source.readText()) != expectedVersion || !source.delete()) return@synchronized false
            published = null
            true
        } catch (e: Exception) {
            FileLogger.w("MemoryFileStorage", "Memory rename failed", e)
            false
        } finally {
            published?.delete()
            temporary?.delete()
        }
    }

    /** Only metadata changes; the body and unrelated frontmatter bytes remain user-owned. */
    fun rewriteContextMetadata(updates: List<Memory>, finish: () -> Boolean = { true }): Boolean = synchronized(lock) {
        val originals = linkedMapOf<File, String>()
        val payloads = linkedMapOf<File, String>()
        val journal = if (updates.isEmpty()) null else defaultFolderJournal
        for (updated in updates) {
            val file = updated.file ?: return@synchronized false
            if (file in originals || !ContextFolderPath.valid(updated.contextFolder)) return@synchronized false
            val text = file.readText()
            if (updated.version.isBlank() || versionOf(text) != updated.version) return@synchronized false
            val delimiters = Regex("(?m)^---\\r?$").findAll(text).take(2).toList()
            if (delimiters.size != 2 || delimiters.first().range.first != 0) return@synchronized false
            val end = delimiters[1].range.first
            val eol = if (text.contains("\r\n")) "\r\n" else "\n"
            var header = text.substring(0, end)
            val formatted = MemoryParser.format(updated).substringBefore("\n---\n")
            for (key in listOf("context_folder", "context_request")) {
                header = header.replace(Regex("(?m)^$key:[^\\r\\n]*(?:\\r?\\n|$)"), "")
                val line = formatted.lineSequence().firstOrNull { it.startsWith("$key:") }
                if (line != null) header += line + eol
            }
            originals[file] = text
            payloads[file] = header + text.substring(end)
        }
        val written = mutableListOf<File>()
        var committed = false
        try {
            if (journal != null && !journal.recover()) return@synchronized false
            if (journal != null && !journal.prepare(originals)) return@synchronized false
            journal?.active = true
            for ((file, payload) in payloads) {
                written += file
                if (!write(file, payload, versionOf(originals.getValue(file)))) return@synchronized false
            }
            if (!finish() || journal?.commit() == false) return@synchronized false
            committed = true
            if (journal?.committed == true) journal.finish()
            true
        } catch (e: Exception) {
            FileLogger.w("MemoryFileStorage", "目录元数据操作中断", e)
            false
        } finally {
            journal?.active = false
            if (!committed && journal?.committed != true) {
                for (file in written.asReversed()) {
                    if (!write(file, originals.getValue(file))) {
                        FileLogger.w("MemoryFileStorage", "目录条目恢复失败，恢复记录已保留: ${file.name}")
                        return@synchronized false
                    }
                }
                if (journal != null && !journal.recover()) {
                    FileLogger.w("MemoryFileStorage", "目录操作恢复记录保留待重试")
                }
            }
        }
    }


    fun migrateContextLayout(memories: List<Memory>): List<Memory> = synchronized(lock) {
        val updates = memories.mapNotNull { memory ->
            if (!memory.hasContextMetadata) return@mapNotNull null
            val folder = memory.contextFolder
            when {
                memory.contextCategory == ContextCategory.RULE && folder == "requests" ->
                    memory.copy(contextFolder = "辅助请求")
                memory.contextCategory == ContextCategory.RULE &&
                    folder.startsWith("requests/") && folder.removePrefix("requests/").isNotBlank() ->
                    memory.copy(contextFolder = "辅助请求", contextRequest = memory.contextRequest ?: folder.removePrefix("requests/"))
                memory.contextCategory.isInjection && (folder == "内置默认" || folder.startsWith("内置默认/")) ->
                    memory.copy(contextFolder = (if (memory.contextCategory == ContextCategory.ROLE_INJECTED)
                        "角色注入" else "主代理注入") + folder.removePrefix("内置默认"))
                else -> null
            }
        }
        if (updates.isEmpty()) return@synchronized memories
        if (!rewriteContextMetadata(updates)) return@synchronized memories
        val updatedFiles = updates.map { it.file }.toSet()
        memories.map { memory ->
            if (memory.file in updatedFiles) checkNotNull(MemoryParser.parse(checkNotNull(memory.file), memory.scope)) else memory
        }
    }

    fun merge(
        group: List<Memory>, name: String, description: String, content: String, type: MemoryType? = null
    ): Boolean = synchronized(lock) {
        if (group.size < 2 || group.any { it.pending || it.file == null }) return@synchronized false
        val first = group.first()
        if (group.any { it.scope != first.scope || it.type != first.type || it.expiresAt != first.expiresAt || it.contextKind != first.contextKind || it.contextLoad != first.contextLoad || it.contextCategory != first.contextCategory || it.contextFolder != first.contextFolder || it.contextSessionId != first.contextSessionId || it.contextRequest != first.contextRequest || it.worldbook != first.worldbook }) {
            return@synchronized false
        }
        val root = first.file?.parentFile ?: return@synchronized false
        val files = group.map { it.file!! }
        if (files.any { it.parentFile != root } || files.distinct().size != files.size) return@synchronized false
        // 组员在读取后是否被改过：重新解析（含版本）与调用方手里的比对，不一致就整簇放弃。
        if (group.any { MemoryParser.parse(it.file!!, it.scope) != it }) return@synchronized false
        val target = MemorySource.resolveMemoryFile(root, name)
        val outsideGroup = root.listFiles()?.any {
            it !in files && (it.name.equals(target.name, ignoreCase = true) ||
                (it.isFile && it.extension == "md" &&
                    MemoryParser.parse(it, first.scope)?.name.equals(name, ignoreCase = true)))
        } ?: return@synchronized false
        if (outsideGroup) return@synchronized false
        for (file in files) {
            if (MemorySource.archiveExistingFile(root, file.nameWithoutExtension) == null) return@synchronized false
        }
        val payload = MemoryParser.format(
            name, description, content, type ?: first.type, group.flatMap { it.keywords }.distinct(), first.expiresAt, first.pending,
            first.contextKind, first.contextLoad, first.contextCategory, first.contextFolder, first.contextSessionId, first.contextRequest, first.worldbook
        )
        if (!write(target, payload)) return@synchronized false
        var deleted = true
        files.filterNot { it == target }.forEach { if (!it.delete()) deleted = false }
        deleted
    }

    fun save(
        root: File,
        scope: MemoryScope,
        name: String,
        description: String,
        content: String,
        type: MemoryType?,
        expiresAt: Long?,
        pending: Boolean?,
        keywords: List<String>?
    ): Boolean = saveChecked(
        root, scope, name, description, content, type, expiresAt, pending, keywords,
        expectedVersion = null
    ) == MemoryWriteResult.Success

    /**
     * 带乐观锁的保存（新建或覆盖）。
     *
     * 读当前文件、校验版本、归档旧版、写新内容全在 [lock] 内完成，中间没有任何插入点：
     * 「校验通过」与「写下去」之间别人不可能再改一次。
     */
    fun saveChecked(
        root: File,
        scope: MemoryScope,
        name: String,
        description: String,
        content: String,
        type: MemoryType?,
        expiresAt: Long?,
        pending: Boolean?,
        keywords: List<String>?,
        expectedVersion: String?,
        contextKind: ContextKind? = null,
        contextLoad: ContextLoad? = null,
        contextCategory: ContextCategory? = null,
        contextFolder: String? = null,
        contextSessionId: String? = null,
        contextRequest: String? = null,
        worldbook: WorldbookRules? = null,
    ): MemoryWriteResult = synchronized(lock) {
        try {
            val file = MemorySource.resolveMemoryFile(root, name)
            val currentText = if (file.isFile) file.readText() else null
            conflictOf(name, expectedVersion, currentText)?.let { return@synchronized it }
            val existing = if (currentText != null) {
                MemoryParser.parse(file, scope)
                    ?: return@synchronized MemoryWriteResult.Failure("SAVE_FAILED", "已有记忆文件解析失败，未覆盖")
            } else {
                null
            }
            val folder = contextFolder ?: existing?.contextFolder.orEmpty()
            if (!ContextFolderPath.valid(folder)) return@synchronized MemoryWriteResult.Failure(
                "INVALID_CONTEXT_FOLDER", "文件夹必须是有效的相对目录路径，不能包含空层级或上级目录"
            )
            // 摘要（digest）是压缩留档，正文只能由 [MemoryDigestWriter] 写入；普通保存把它的 type
            // 改成别的类型，那段被压掉的历史就会以「现行知识」的身份重新进注入清单，等于把留档
            // 当知识用。改内容不改类型则放行：人工编辑、摘要二次整理走这条。
            if (existing?.type == MemoryType.DIGEST && type != null && type != MemoryType.DIGEST) {
                return@synchronized MemoryWriteResult.Failure(
                    "DIGEST_PROTECTED", "「${existing.name}」是上下文压缩摘要，不能改成其它类型"
                )
            }
            val payload = MemoryParser.format(
                name.trim(), description, content,
                type ?: existing?.type,
                keywords ?: existing?.keywords,
                expiresAt ?: existing?.expiresAt,
                pending ?: existing?.pending ?: false,
                contextKind ?: existing?.contextKind ?: ContextKind.REFERENCE,
                contextLoad ?: existing?.contextLoad ?: ContextLoad.INDEX,
                contextCategory ?: existing?.contextCategory ?: ContextCategory.LONG_TERM,
                folder,
                contextSessionId ?: existing?.contextSessionId,
                contextRequest ?: existing?.contextRequest,
                worldbook ?: existing?.worldbook ?: WorldbookRules(),
                existing?.let(com.aharou.feature.agent.domain.context.ContextGraphIdentity::raw) ?: java.util.UUID.randomUUID().toString(),
                existing?.graphAliases.orEmpty()
            )
            if (currentText == payload) return@synchronized MemoryWriteResult.Success
            if (currentText != null && MemorySource.archiveExistingFile(root, name) == null) {
                return@synchronized MemoryWriteResult.Failure("SAVE_ARCHIVE_FAILED", "归档旧版记忆失败，原文未修改")
            }
            if (write(file, payload)) MemoryWriteResult.Success
            else MemoryWriteResult.Failure("SAVE_WRITE_FAILED", "写入记忆文件失败，原文未修改")
        } catch (e: Exception) {
            FileLogger.w("MemoryFileStorage", "保存记忆失败: $name", e)
            MemoryWriteResult.Failure("SAVE_FAILED", "保存记忆失败: ${e.message}")
        }
    }
}
