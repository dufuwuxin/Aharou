package com.aharou.feature.agent.domain.context

import android.content.Context
import android.util.AtomicFile
import com.aharou.R
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.agent.domain.memory.ContextMetadataRecovery
import com.aharou.feature.agent.domain.memory.MemoryFileStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

@Singleton
class ContextFolderStore @Inject constructor(@ApplicationContext context: Context) {
    private val file = AtomicFile(File(context.filesDir, "aharou-global/memory/.context-folders.json"))
    private val lock get() = MemoryFileStorage.lock
    init { MemoryFileStorage.folderJournal = ContextMetadataRecovery(file.baseFile, ::readRaw, ::writeRaw) }
    private val builtinFolders = mapOf(
        ContextCategory.ROLE_INJECTED to context.getString(R.string.context_category_role_injected),
        ContextCategory.MAIN_INJECTED to context.getString(R.string.context_category_main_injected),
    )

    fun list(projectRoot: String?): Map<ContextCategory, List<String>> = synchronized(lock) {
        val root = read() ?: return emptyMap()
        val global = root.optJSONObject(GLOBAL) ?: JSONObject()
        val project = root.optJSONObject(projectRoot.orEmpty()) ?: JSONObject()
        ContextCategory.entries.associateWith { category ->
            (listOfNotNull(builtinFolders[category]) + names(global, category) + names(project, category)).distinct().sorted()
        }
    }

    fun snapshot(projectRoot: String?): Pair<Map<ContextCategory, List<String>>, String>? = synchronized(lock) {
        val value = read() ?: return@synchronized null
        list(projectRoot) to version(value.toString())
    }

    private fun version(text: String): String = ContextIdentity.version(text)

    fun listScope(category: ContextCategory, scope: MemoryScope, projectRoot: String?): List<String> = synchronized(lock) {
        if (scope == MemoryScope.PROJECT && projectRoot.isNullOrBlank()) return@synchronized emptyList()
        val root = read() ?: return@synchronized emptyList()
        names(root.optJSONObject(if (scope == MemoryScope.GLOBAL) GLOBAL else checkNotNull(projectRoot)) ?: JSONObject(), category).sorted()
    }

    fun canDelete(category: ContextCategory, path: String): Boolean = path.isNotEmpty() && ContextFolderPath.valid(path) &&
        builtinFolders[category]?.let { it == path || it.startsWith("$path/") } != true

    fun delete(category: ContextCategory, path: String, scope: MemoryScope, projectRoot: String?,
               expectedVersion: String? = null): Boolean = synchronized(lock) {
        if (!canDelete(category, path) || (scope == MemoryScope.PROJECT && projectRoot.isNullOrBlank())) return@synchronized false
        val root = read() ?: return@synchronized false
        if (expectedVersion != null && expectedVersion != version(root.toString())) return@synchronized false
        val key = if (scope == MemoryScope.GLOBAL) GLOBAL else checkNotNull(projectRoot)
        val folders = root.optJSONObject(key) ?: JSONObject()
        val remaining = names(folders, category).filterNot { it == path || it.startsWith("$path/") }
        folders.put(category.name, org.json.JSONArray(remaining))
        if (category == ContextCategory.MAIN_INJECTED) folders.remove("INJECTED")
        root.put(key, folders)
        write(root)
    }

    fun create(category: ContextCategory, name: String, projectRoot: String?): Boolean = synchronized(lock) {
        if (name.isEmpty() || !ContextFolderPath.valid(name)) return false
        val segments = name.split('/')
        val paths = segments.indices.map { segments.take(it + 1).joinToString("/") }
        val root = read() ?: return@synchronized false
        val scope = projectRoot ?: GLOBAL
        val folders = root.optJSONObject(scope) ?: JSONObject()
        folders.put(category.name, org.json.JSONArray((names(folders, category) + paths).distinct().sorted()))
        root.put(scope, folders)
        write(root)
    }

    private fun write(root: JSONObject): Boolean {
        file.baseFile.parentFile?.mkdirs()
        return writeRaw(root.toString())
    }

    private fun writeRaw(text: String): Boolean {
        if (!file.baseFile.exists() && text.isEmpty()) return true
        file.baseFile.parentFile?.mkdirs()
        var stream: java.io.FileOutputStream? = null
        return try {
            stream = file.startWrite()
            stream.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
            true
        } catch (_: Exception) {
            stream?.let(file::failWrite)
            false
        }
    }

    private fun readRaw(): String = if (!file.baseFile.exists()) "" else
        file.openRead().bufferedReader().use { it.readText() }

    private fun read(): JSONObject? {
        if (!MemoryFileStorage.folderJournal.recover()) return null
        if (!file.baseFile.exists()) return JSONObject()
        try {
            val root = file.openRead().bufferedReader().use { JSONObject(it.readText()) }
            var changed = false
            for (scopeKey in root.keys().asSequence().toList()) {
                val scoped = root.getJSONObject(scopeKey)
                for (categoryKey in scoped.keys().asSequence().toList()) {
                    val category = ContextCategory.from(categoryKey) ?: continue
                    val array = scoped.getJSONArray(categoryKey)
                    val original = (0 until array.length()).map { array.getString(it) }
                    val mapped = original.map { path ->
                        when {
                            category == ContextCategory.RULE && (path == "requests" || path.startsWith("requests/")) -> "辅助请求"
                            category.isInjection && (path == "内置默认" || path.startsWith("内置默认/")) ->
                                checkNotNull(builtinFolders[category]) + path.removePrefix("内置默认")
                            else -> path
                        }
                    }
                    if (mapped != original) {
                        scoped.put(categoryKey, org.json.JSONArray(mapped.distinct().sorted()))
                        changed = true
                    }
                }
            }
            if (changed && !write(root)) return null
            return root
        } catch (e: Exception) {
            com.aharou.core.util.FileLogger.w("ContextFolderStore", "目录配置读取失败，保留原文件", e)
            return null
        }
    }

    private fun names(root: JSONObject, category: ContextCategory): List<String> {
        val keys = if (category == ContextCategory.MAIN_INJECTED) listOf(category.name, "INJECTED") else listOf(category.name)
        return keys.flatMap { key ->
            val array = root.optJSONArray(key)
            if (array == null) emptyList() else (0 until array.length()).map { array.getString(it) }
        }.distinct()
    }

    private companion object { const val GLOBAL = "__global__" }
}
