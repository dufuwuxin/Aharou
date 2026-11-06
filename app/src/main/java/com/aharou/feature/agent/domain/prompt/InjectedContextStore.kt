package com.aharou.feature.agent.domain.prompt

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 各会话「最近一轮实际注入给模型的默认内容」的内存快照。
 *
 * 为什么要有它：系统提示词（规则、记忆、技能、时间、团队名册…）是每轮现算现用的局部变量，
 * 出函数即丢。主人在界面上只能看到自己打的字，看不到这些「不是我发的、但模型确实收到了」的内容，
 * 于是没法判断某个回答为什么那样。这份快照就是「显示全部」按钮背后的数据源。
 *
 * 只留最近一轮、只在本进程内存里：
 * - 不落库：注入内容含记忆与规则正文，落库会把它变成可被备份／同步的磁盘痕迹，隐私面变大；
 * - 不按轮累积：按钮问的是「现在生效的默认注入是什么」，历史轮次的注入没有展示价值。
 */
@Singleton
class InjectedContextStore @Inject constructor() {

    data class Section(val source: String, val characters: Int, val estimatedTokens: Int)
    data class Composition(val characters: Int, val estimatedTokens: Int, val sections: List<Section>) {
        /** Prompt headings stay local: they can contain private memory names or other user content. */
        fun externalSections(): JsonArray = buildJsonArray {
            sections.forEachIndexed { index, section ->
                addJsonObject {
                    put("source", "section_${index + 1}")
                    put("characters", section.characters)
                    put("estimatedTokens", section.estimatedTokens)
                }
            }
        }
    }

    companion object {
        /** Heading-derived labels are not authoritative provider provenance. Tokens are a local heuristic. */
        fun composition(text: String): Composition {
            val headings = Regex("(?m)^#{1,6} +([^\\r\\n]+)").findAll(text).toList()
            val starts = (listOf(0) + headings.map { it.range.first }).distinct().sorted()
            val sections = starts.mapIndexedNotNull { index, start ->
                val end = starts.getOrNull(index + 1) ?: text.length
                if (start == end) return@mapIndexedNotNull null
                val source = headings.firstOrNull { it.range.first == start }?.groupValues?.get(1)
                    ?: "system_prompt"
                val characters = text.codePointCount(start, end)
                Section(source, characters, (characters + 3) / 4)
            }
            return Composition(sections.sumOf { it.characters }, sections.sumOf { it.estimatedTokens }, sections)
        }
    }

    fun snapshot(sessionId: String): Composition? = requests[sessionId]?.let { request ->
        Composition(request.characters, request.estimatedTokens,
            request.parts.map { Section(it.source, it.characters, it.estimatedTokens) })
    }

    private val versions = mutableMapOf<String, Long>()
    private val requests = java.util.concurrent.ConcurrentHashMap<String, com.aharou.feature.agent.domain.workflow.RequestContextComposition.Snapshot>()
    @Synchronized fun beginRequest(sessionId: String?): Long {
        if (sessionId.isNullOrBlank()) return -1
        val version = (versions[sessionId] ?: 0L) + 1
        versions[sessionId] = version
        requests.remove(sessionId)
        _bySession.value = _bySession.value - sessionId
        return version
    }
    @Synchronized fun recordRequest(sessionId: String?, request: com.aharou.feature.agent.domain.workflow.RequestContextComposition.Snapshot) {
        if (sessionId.isNullOrBlank() || versions[sessionId] != request.version) return
        requests[sessionId] = request
        _bySession.value = _bySession.value + (sessionId to request.displayText())
    }
    fun requestMetadata(sessionId: String) = requests[sessionId]?.metadata()

    private val _bySession = MutableStateFlow<Map<String, String>>(emptyMap())

    /** 会话 id → 本轮注入正文。 */
    val bySession: StateFlow<Map<String, String>> = _bySession.asStateFlow()

    /** 记下一轮的系统提示词快照；空串与会话 id 缺失都直接忽略，免得界面上出现空白面板。 */
    @Synchronized
    fun record(sessionId: String?, text: String) {
        if (sessionId.isNullOrBlank() || text.isBlank()) return
        // Prompt construction is not an outbound request (middleware, folding and masking follow).
        // Retain this entry point for prompt builders, without publishing a misleading snapshot.
    }

    /** 切走会话时不必清理：Map 里每个会话各留一份，内存代价是「会话数 × 提示词长度」，可接受。 */
    @Synchronized
    fun forget(sessionId: String) {
        if (sessionId.isBlank()) return
        beginRequest(sessionId)
    }
}
