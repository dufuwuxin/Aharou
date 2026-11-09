package com.aharou.feature.agent.domain.context

import android.content.Context
import com.aharou.core.memory.AharouMemoryStore
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.memory.Memory
import com.aharou.feature.agent.domain.memory.MemoryFileStorage
import com.aharou.feature.agent.domain.memory.MemoryRepository
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.agent.domain.memory.MemorySource
import com.aharou.feature.agent.domain.memory.MemoryWriteResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Defaults are installed once, never used as a runtime prompt fallback. */
@Singleton
class BuiltinContextEntries internal constructor(
    private val ledger: File,
    private val loadAsset: () -> String,
) {
    @Inject constructor(@ApplicationContext context: Context, store: AharouMemoryStore) : this(
        File(store.dir, ".context-defaults-v1"),
        { context.assets.open("context-library/builtin.json").bufferedReader().use { it.readText() } },
    )

    internal data class Default(
        val id: String, val title: String, val description: String, val content: String,
        val category: ContextCategory, val loading: ContextLoad, val folder: String,
        val requestKey: String? = null,
    )

    internal fun defaults(): List<Default> {
        val root = Json.parseToJsonElement(loadAsset()).jsonObject
        require(root.getValue("version").jsonPrimitive.content == "1")
        val entries = root.getValue("entries").jsonArray.map { raw ->
            val entry = raw.jsonObject
            fun value(key: String) = entry.getValue(key).jsonPrimitive.content
            Default(value("id"), value("title"), value("description"), value("content"),
                ContextCategory.valueOf(value("category")), ContextLoad.valueOf(value("loading")), value("folder"),
                entry["requestKey"]?.jsonPrimitive?.content)
        }
        require(entries.map { it.id }.distinct().size == entries.size)
        require(entries.map { it.title }.distinct().size == entries.size)
        require(entries.all { it.id.matches(Regex("[a-z0-9-]+")) && it.title.isNotBlank() &&
            it.content.isNotBlank() && ContextFolderPath.valid(it.folder) })
        return entries
    }

    internal fun ensureSeeded(memories: MemoryRepository) = synchronized(MemoryFileStorage.lock) {
        // A separate durable receipt survives user rename/delete/OFF; a missing memory is not a migration request.
        val defaults = defaults()
        val done = if (ledger.exists()) {
            val lines = try { ledger.readLines() } catch (e: Exception) {
                FileLogger.w("BuiltinContextEntries", "读取默认收据失败", e)
                return@synchronized
            }
            val header = lines.firstOrNull()
            if (header !in setOf("version=1", "version=1-legacy") ||
                lines.drop(1).any { it !in defaults.map(Default::id) }) {
                FileLogger.w("BuiltinContextEntries", "默认收据损坏，保留现有条目并暂停初始化")
                return@synchronized
            }
            // Legacy adoption is terminal: later defaults must not be installed into an upgraded library.
            if (header == "version=1-legacy") return@synchronized
            lines.drop(1).toMutableSet()
        } else {
            val existing = memories.listMemoriesInScope(MemoryScope.GLOBAL, null)
            val adopted = adoptLegacy(existing, defaults)
            if (adopted == null) {
                FileLogger.w("BuiltinContextEntries", "旧原生条目认领失败，保留原文")
                return@synchronized
            }
            if (adopted.isNotEmpty() && !writeReceipt(adopted.toList(), legacyAdoption = true)) {
                FileLogger.w("BuiltinContextEntries", "默认认领收据写入失败，保留原文")
                return@synchronized
            }
            if (adopted.isNotEmpty()) return@synchronized
            adopted
        }
        for (entry in defaults) {
            if (entry.id in done) continue
            val result = memories.writeMemory(entry.title, entry.description, entry.content,
                MemoryScope.GLOBAL, null, expectedVersion = MemorySource.VERSION_NEW,
                contextKind = ContextKind.INSTRUCTION, contextLoad = entry.loading,
                contextCategory = entry.category, contextFolder = entry.folder, contextRequest = entry.requestKey)
            // An existing user-owned name wins, even if its body or metadata differs from our default.
            check(result == MemoryWriteResult.Success || result is MemoryWriteResult.VersionConflict) {
                "Native context default write failed: ${entry.id}"
            }
            if (!writeReceipt((done + entry.id).toList())) {
                if (result == MemoryWriteResult.Success) memories.deleteMemory(entry.title, MemoryScope.GLOBAL, null)
                return@synchronized
            }
            done += entry.id
        }
    }

    private fun writeReceipt(ids: List<String>, legacyAdoption: Boolean = false): Boolean {
        ledger.parentFile?.mkdirs()
        return try {
            val pending = File(ledger.parentFile, "${ledger.name}.tmp")
            FileOutputStream(pending).use { stream ->
                val header = if (legacyAdoption) "version=1-legacy" else "version=1"
                stream.write((header + "\n" + ids.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Files.move(pending.toPath(), ledger.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (_: Exception) { false }
    }

    private fun adoptLegacy(existing: List<Memory>, defaults: List<Default>): MutableSet<String>? {
        if (existing.isEmpty()) return mutableSetOf()
        val legacy = existing.filter { memory -> memory.hasContextMetadata &&
            (memory.contextFolder == "内置默认" || memory.contextFolder.startsWith("内置默认/") ||
                memory.contextFolder == "requests" || memory.contextFolder.startsWith("requests/")) }
        if (legacy.isEmpty()) return mutableSetOf()
        val adopted = mutableSetOf<String>()
        val updates = mutableListOf<Memory>()
        val legacyIds = setOf("main-foundation", "member-foundation", "title-request")
        for (entry in defaults) {
            if (entry.id !in legacyIds) continue
            val matches = legacy.filter { memory ->
                memory.contextCategory == entry.category && if (entry.requestKey == null) {
                    memory.contextFolder == "内置默认" && memory.name == entry.title
                } else {
                    memory.contextFolder == "requests/${entry.requestKey}" || memory.contextRequest == entry.requestKey
                }
            }
            if (matches.size > 1) return null
            adopted += entry.id
            val match = matches.singleOrNull() ?: continue
            if (match.contextRequest != entry.requestKey) updates += match.copy(contextRequest = entry.requestKey)
        }
        if (updates.isNotEmpty() && !MemoryFileStorage.rewriteContextMetadata(updates)) return null
        return adopted
    }
}
