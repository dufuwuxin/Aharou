package com.aharou.feature.agent.domain.knowledge

import android.util.AtomicFile
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.ContainerInstaller
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * 用户自己加的知识库源：`filesDir/aharou/knowledge-sources.json`。
 *
 * 与出厂源清单（`data/knowledge.json`，远端托管、随版本更新）分开存放——那是只读的内置内容，
 * 这个是用户自己的，升级不能覆盖。[KnowledgeCatalog] 读取时把两者合并，用户源同名可覆盖内置源。
 */
@Singleton
class KnowledgeSourceRepository @Inject constructor(
    private val containerInstaller: ContainerInstaller
) {
    private fun file(): File = File(containerInstaller.aharouDir, FILE_NAME)

    /** 全部自定义源（id → 定义）。 */
    @Synchronized
    fun all(): Map<String, KnowledgeSourceDef> = read()

    @Synchronized
    fun add(id: String, definition: KnowledgeSourceDef) {
        val current = read(strict = true)
        require(current[id]?.isLocal != true) { "Cannot replace a local knowledge source with a Git source" }
        write(current + (id to definition))
    }

    @Synchronized
    fun addLocal(id: String, definition: KnowledgeSourceDef) {
        val current = read(strict = true)
        require(id !in current) { "Knowledge source already exists" }
        require(definition.isLocal && definition.repo.isBlank()) { "Not a local knowledge source" }
        require(current.values.none {
            it.name.values.any { name -> name.equals(definition.displayName("en"), ignoreCase = true) }
        }) { "Knowledge name already exists" }
        write(current + (id to definition))
    }

    /** 按 id 删除自定义源；不存在时静默返回。 */
    @Synchronized
    fun remove(id: String) {
        val current = read(strict = true)
        if (id !in current) return
        write(current - id)
    }

    private fun read(strict: Boolean = false): Map<String, KnowledgeSourceDef> {
        val target = file()
        if (!target.exists() && !File(target.path + ".bak").exists()) return emptyMap()
        return try {
            val content = AtomicFile(target).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            json.decodeFromString<KnowledgeData>(content).sources
        } catch (error: Exception) {
            if (strict) throw error
            FileLogger.w("KnowledgeSourceRepository", "Cannot read knowledge source list", error)
            emptyMap()
        }
    }

    private fun write(sources: Map<String, KnowledgeSourceDef>) {
        val target = AtomicFile(file())
        val content = prettyJson.encodeToString(KnowledgeData(sources = sources)).toByteArray(Charsets.UTF_8)
        val stream = target.startWrite()
        try {
            stream.write(content)
            target.finishWrite(stream)
            val saved = target.openRead().use { it.readBytes() }
            if (!saved.contentEquals(content)) throw IOException("Knowledge source metadata was not saved")
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }

    private companion object {
        const val FILE_NAME = "knowledge-sources.json"
        val json = Json { ignoreUnknownKeys = true }
        val prettyJson = Json { ignoreUnknownKeys = true; prettyPrint = true }
    }
}
