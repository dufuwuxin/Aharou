package com.aharou.feature.agent.domain.context

import android.content.Context
import android.util.AtomicFile
import com.aharou.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

@Singleton
class ContextFolderStore @Inject constructor(@param:ApplicationContext context: Context) {
    private val file = AtomicFile(File(context.filesDir, "aharou-global/memory/.context-folders.json"))
    private val lock = Any()
    private val builtinFolders = mapOf(
        ContextCategory.ROLE_INJECTED to context.getString(R.string.context_category_role_injected),
        ContextCategory.MAIN_INJECTED to context.getString(R.string.context_category_main_injected),
    )

    fun list(projectRoot: String?): Map<ContextCategory, List<String>> = synchronized(lock) {
        val root = read()
        val global = root.optJSONObject(GLOBAL) ?: JSONObject()
        val project = root.optJSONObject(projectRoot.orEmpty()) ?: JSONObject()
        ContextCategory.entries.associateWith { category ->
            (listOfNotNull(builtinFolders[category]) + names(global, category) + names(project, category)).distinct().sorted()
        }
    }

    fun create(category: ContextCategory, name: String, projectRoot: String?): Boolean = synchronized(lock) {
        if (name.isEmpty() || !ContextFolderPath.valid(name)) return false
        val segments = name.split('/')
        val paths = segments.indices.map { segments.take(it + 1).joinToString("/") }
        val root = read()
        val scope = projectRoot ?: GLOBAL
        val folders = root.optJSONObject(scope) ?: JSONObject()
        val values = (names(folders, category) + paths).distinct().sorted()
        folders.put(category.name, org.json.JSONArray(values))
        root.put(scope, folders)
        file.baseFile.parentFile?.mkdirs()
        var stream: java.io.FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(root.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
            true
        } catch (_: Exception) {
            stream?.let(file::failWrite)
            false
        }
    }

    private fun read(): JSONObject = if (!file.baseFile.exists()) JSONObject() else
        file.openRead().bufferedReader().use { JSONObject(it.readText()) }

    private fun names(root: JSONObject, category: ContextCategory): List<String> {
        // Keep legacy folders readable without rewriting or duplicating the library.
        val keys = if (category == ContextCategory.MAIN_INJECTED) listOf(category.name, "INJECTED") else listOf(category.name)
        return keys.flatMap { key ->
            val array = root.optJSONArray(key)
            if (array == null) emptyList() else (0 until array.length()).map { array.getString(it) }
        }.distinct()
    }

    private companion object { const val GLOBAL = "__global__" }
}
