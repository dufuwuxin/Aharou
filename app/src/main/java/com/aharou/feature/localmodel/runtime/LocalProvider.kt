package com.aharou.feature.localmodel.runtime

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.provider.AIProvider
import com.aharou.feature.agent.domain.provider.AIResponse
import com.aharou.feature.agent.domain.provider.AIStreamChunk
import com.aharou.feature.agent.domain.provider.KeySwitchOutcome
import com.aharou.feature.agent.domain.tool.AgentTool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 把端侧 GGUF 模型接进正常对话链路的 [AIProvider]：选了这个供应商，聊天/标题/压缩全走本机推理，
 * 不出网、不需要 Key。
 *
 * 三件事必须和云端 adapter 不同：
 * 1. **工具调用一律不发**。llama.cpp 这一层（SmolLM）没有 function-calling，把工具塞给 0.5B 级模型
 *    只会让它把 JSON 当正文吐出来；这里直接忽略 [tools]，模型就按纯聊天回答。
 * 2. **历史只发「新增的那一段」**。原生上下文是常驻的（`storeChats = true`），上层每轮却会重发完整历史，
 *    照原样转发会让同一段话在上下文里越叠越多，4K 窗口几轮就满。所以这里记住上次发到哪，
 *    正常情况下只追加尾巴；一旦发现上层历史不是「上次的延续」（压缩、编辑、换会话），
 *    先卸载再重载模型，用完整历史重建上下文。
 * 3. **采样参数不能每轮变**。[LocalLlmEngine] 里参数变了就重载模型（几秒到十几秒），
 *    所以这里固定用默认参数，只把线程数按机器核心数定一次。
 */
class LocalProvider(private val engine: LocalLlmEngine) : AIProvider {

    override var apiKey: String = ""
    override var baseUrl: String = ""
    override var useFullUrl: Boolean = false
    override var useResponseApi: Boolean = false
    override var model: String = ""
    override var providerId: String = ""
    override var logSessionId: String? = null
    override var customHeaders: Map<String, String> = emptyMap()
    override var keySwitcher: (suspend (Throwable, String, Set<String>) -> KeySwitchOutcome?)? = null
    override var maxOutputTokens: Int? = null
    override var temperature: Float? = null
    override var firstByteTimeoutMs: Long = 0L
    override var streamIdleTimeoutMs: Long = 0L
    override var maxNetworkRetries: Int = 0

    /** 上一次成功发出去的历史，用来算「本轮新增尾巴」。 */
    private var sentHistory: List<LocalChatMessage> = emptyList()

    /** [sentHistory] 属于哪个模型；换模型后旧上下文作废。 */
    private var sentForModel: String? = null

    override suspend fun complete(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): AIResponse {
        val text = StringBuilder()
        var final: AIResponse? = null
        completeStream(systemPrompt, messages, tools, reasoningEffort).collect { chunk ->
            when (chunk) {
                is AIStreamChunk.TextDelta -> text.append(chunk.text)
                is AIStreamChunk.Final -> final = chunk.response
                else -> Unit
            }
        }
        return final ?: AIResponse(content = text.toString(), stopReason = STOP_REASON)
    }

    override fun completeStream(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): Flow<AIStreamChunk> = flow {
        val history = toLocalHistory(systemPrompt, messages)
        val params = LocalGenParams(numThreads = threadCount())
        engine.ensureLoaded(model)

        // 上层历史不是「上次的延续」时，原生上下文里的旧内容已经对不上了：重载一次，用完整历史重建。
        val appended = sentForModel == model && isPrefixOf(history, sentHistory)
        if (!appended) {
            if (sentHistory.isNotEmpty()) {
                FileLogger.i(TAG, "端侧对话历史非追加式变化，重建上下文：${sentHistory.size} -> ${history.size}")
                engine.unload()
                engine.ensureLoaded(model)
            }
            sentHistory = emptyList()
        }
        val toSend = if (appended && history.size > sentHistory.size) {
            history.subList(sentHistory.size, history.size)
        } else {
            history
        }

        val collected = StringBuilder()
        engine.stream(toSend, params).collect { token ->
            collected.append(token)
            emit(AIStreamChunk.TextDelta(token))
        }
        sentHistory = history
        sentForModel = model
        emit(
            AIStreamChunk.Final(
                AIResponse(content = collected.toString(), stopReason = STOP_REASON)
            )
        )
    }

    /**
     * 把上层历史摊平成原生上下文认的三段式消息。
     *
     * 工具结果（[AgentMessage.ToolResultMessage]）不会被端侧模型触发，但历史里可能残留（比如中途换了模型），
     * 摊成一条用户消息比丢掉更安全——丢掉会让模型看到「有提问没回应」的断层。
     */
    private fun toLocalHistory(systemPrompt: String, messages: List<AgentMessage>): List<LocalChatMessage> {
        val out = ArrayList<LocalChatMessage>(messages.size + 2)
        if (systemPrompt.isNotBlank()) out += LocalChatMessage(ROLE_SYSTEM, systemPrompt.trim())
        messages.forEach { message ->
            when (message) {
                is AgentMessage.UserMessage ->
                    if (message.content.isNotBlank()) out += LocalChatMessage(ROLE_USER, message.content)
                is AgentMessage.AssistantMessage ->
                    if (message.content.isNotBlank()) out += LocalChatMessage(ROLE_ASSISTANT, message.content)
                is AgentMessage.ToolResultMessage -> {
                    val body = message.modelResult ?: message.result
                    if (body.isNotBlank()) {
                        out += LocalChatMessage(ROLE_USER, "「${message.toolName}」的执行结果：\n$body")
                    }
                }
            }
        }
        // 引擎要求最后一条是用户消息：最后若是 assistant（重试/续写），补一句引导语当作本轮提问。
        if (out.lastOrNull()?.role != ROLE_USER) {
            out += LocalChatMessage(ROLE_USER, "请接着上面的内容继续回答。")
        }
        return out
    }

    private fun isPrefixOf(full: List<LocalChatMessage>, prefix: List<LocalChatMessage>): Boolean =
        prefix.isEmpty() || (full.size >= prefix.size && full.subList(0, prefix.size) == prefix)

    private fun threadCount(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(MIN_THREADS, MAX_THREADS)

    private companion object {
        const val TAG = "LocalProvider"
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val STOP_REASON = "stop"
        const val MIN_THREADS = 2
        const val MAX_THREADS = 4
    }
}
