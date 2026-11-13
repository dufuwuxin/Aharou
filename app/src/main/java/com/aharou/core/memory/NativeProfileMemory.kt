package com.aharou.core.memory

import com.aharou.core.soul.SoulMDParser
import com.aharou.core.util.writeTextSafely
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.context.ContextCategory
import com.aharou.feature.agent.domain.context.ContextKind
import com.aharou.feature.agent.domain.context.ContextLoad
import com.aharou.feature.agent.domain.memory.MemoryFileStorage
import com.aharou.feature.agent.domain.memory.MemoryParser
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.agent.domain.memory.MemorySource
import com.aharou.feature.agent.domain.memory.MemoryWriteResult
import java.io.File

/** Compatibility settings edit native entries; they never supply a separate prompt source. */
object NativeProfileMemory {
    const val BODY = "人格正文"
    const val STYLE = "回复风格"
    const val LANGUAGE = "回复语言"
    private const val RECEIPT = ".native-profile-v1"

    fun migrate(root: File, enabled: Boolean): Boolean = synchronized(MemoryFileStorage.lock) {
        val receipt = File(root, RECEIPT)
        if (receipt.isFile) return@synchronized true
        try {
        val soul = File(root, "SOUL.md").takeIf { it.isFile }?.readText()?.let(SoulMDParser::parse)
        val values = listOf(BODY to soul?.body.orEmpty(), STYLE to soul?.metadata?.style.orEmpty(),
            LANGUAGE to soul?.metadata?.lang.orEmpty().takeUnless { it == "auto" }.orEmpty())
        for ((name, content) in values) {
            if (content.isBlank()) continue
            val file = MemorySource.resolveMemoryFile(root, name)
            if (!file.exists()) write(root, name, content, enabled)
        }
        val coreFile = File(root, "CORE.md")
        val core = MemoryParser.parse(coreFile, MemoryScope.GLOBAL)
        if (core != null && !core.hasContextMetadata && core.content.isNotBlank()) {
            write(root, "CORE", core.content, enabled)
        }
        root.mkdirs()
        check(receipt.writeTextSafely("version=1\n", "NativeProfileMemory"))
        true
        } catch (e: Exception) {
            FileLogger.w("NativeProfileMemory", "Profile migration incomplete; existing files retained (${e.javaClass.simpleName})")
            false
        }
    }

    fun read(root: File, name: String): String =
        MemoryParser.parse(MemorySource.resolveMemoryFile(root, name), MemoryScope.GLOBAL)?.content.orEmpty()

    fun write(root: File, name: String, content: String, enabled: Boolean = true) = synchronized(MemoryFileStorage.lock) {
        val file = MemorySource.resolveMemoryFile(root, name)
        val existing = MemoryParser.parse(file, MemoryScope.GLOBAL)
        val native = existing?.takeIf { it.hasContextMetadata }
        val result = MemoryFileStorage.saveChecked(root, MemoryScope.GLOBAL, name,
            native?.description ?: name, content, null, null, null, null,
            existing?.version ?: MemorySource.VERSION_NEW,
            contextKind = native?.contextKind ?: if (name == "CORE") ContextKind.REFERENCE else ContextKind.INSTRUCTION,
            contextLoad = native?.contextLoad ?: if (name == "CORE" || enabled) ContextLoad.FULL else ContextLoad.OFF,
            contextCategory = native?.contextCategory ?: if (name == "CORE") ContextCategory.LONG_TERM else ContextCategory.MAIN_INJECTED,
            contextFolder = native?.contextFolder ?: "助手设定")
        check(result == MemoryWriteResult.Success) { "Native profile write failed: $name" }
    }
}
