package com.aharou.feature.localmodel.runtime

import android.content.Context
import com.aharou.R
import com.aharou.core.util.FileLogger
import com.aharou.feature.localmodel.domain.LocalModelManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.shubham0204.smollm.SmolLM
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 一轮对话里的一条消息；[role] 取 `system` / `user` / `assistant`。 */
data class LocalChatMessage(
    val role: String,
    val content: String,
)

/** 生成参数；除 [numThreads] 外都是采样参数。 */
data class LocalGenParams(
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val maxTokens: Int = 512,
    val numThreads: Int = 4,
)

/**
 * 端侧推理引擎：持有唯一的 [SmolLM] 实例，负责加载、流式生成、卸载。
 *
 * llama.cpp 的原生上下文是独占资源，一个进程里同时开两个会互相踩内存，所以所有加载与生成都
 * 串在 [mutex] 后面；换模型时必须先 `close()` 旧的再 `load` 新的，否则内存里会短暂存在两份权重
 * （1.5B 一份就 1.1GB，叠起来低端机直接 OOM）。
 *
 * 生成走 [SmolLM.getResponseAsFlow]，逐 token 吐给上层，界面才能「边想边说」。
 * chat template 不写死：Qwen2.5 的模板就在 GGUF 元数据里，传 null 让库自己读，
 * 写死一份反而会随模型版本过期。
 */
@Singleton
class LocalLlmEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val manager: LocalModelManager,
) {
    /** 同一时刻只允许一个原生实例在跑。 */
    private val mutex = Mutex()

    private var llm: SmolLM? = null

    /** 当前实例是按哪套参数加载的：采样参数只能在建上下文时给，变了就得重载。 */
    private var loadedParams: LocalGenParams? = null

    private val _loadedModelId = MutableStateFlow<String?>(null)

    /** 已加载的模型 id；null 表示引擎空闲。界面用它判断「能不能直接发消息」。 */
    val loadedModelId: StateFlow<String?> = _loadedModelId.asStateFlow()

    /** 引擎是否已加载模型、可以直接生成。 */
    fun isReadyToChat(): Boolean = llm != null && _loadedModelId.value != null

    /**
     * 确保 [id] 这个模型已加载（同 id 且参数未变时直接返回）。
     *
     * 首次加载要读 490MB~1.1GB 的权重并建上下文，耗时几秒到十几秒，务必在 IO 线程里等，
     * 别在 UI 线程上卡（本方法内部已切 [Dispatchers.IO]）。
     *
     * @throws IOException 设备架构不支持、模型未下载，或原生库加载失败
     */
    suspend fun ensureLoaded(id: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (_loadedModelId.value == id && llm != null &&
                    loadedParams == DEFAULT_PARAMS
                ) {
                    return@withLock
                }
                loadLocked(id, DEFAULT_PARAMS)
            }
        }
    }

    /**
     * 流式生成：把 [messages] 依次喂给原生上下文，最后一条用户消息作为本轮提问。
     *
     * 用 [channelFlow] 而不是普通 `flow`：`emit` 必须发生在收集者上下文里，而原生推理是阻塞的，
     * 生成部分要用 `launch(Dispatchers.IO)` 甩到后台，只有 channel 能合法地跨上下文回传。
     *
     * @throws IOException 引擎空闲、模型文件不在，或原生推理失败（含 `UnsatisfiedLinkError`）
     */
    fun stream(
        messages: List<LocalChatMessage>,
        params: LocalGenParams = LocalGenParams(),
    ): Flow<String> = channelFlow {
        val last = messages.lastOrNull()
            ?: throw IOException("没有可发送的消息")
        if (last.role != ROLE_USER) {
            throw IOException("最后一条消息必须是用户消息")
        }
        val instance = mutex.withLock {
            val current = llm
            if (current == null) {
                throw IOException(context.getString(R.string.settings_local_model_not_downloaded))
            }
            // 采样参数只在建上下文时生效；参数变了就重载（会丢内部历史，所以默认参数下不重载）
            if (loadedParams != params) {
                val id = _loadedModelId.value ?: throw IOException(
                    context.getString(R.string.settings_local_model_not_downloaded)
                )
                loadLocked(id, params)
            }
            llm ?: throw IOException(context.getString(R.string.settings_local_model_not_downloaded))
        }
        launch(Dispatchers.IO) {
            try {
                // 历史消息先落进上下文（storeChats = true，后续轮次靠它记住前文）
                messages.dropLast(1).forEach { message ->
                    when (message.role) {
                        ROLE_SYSTEM -> instance.addSystemPrompt(message.content)
                        ROLE_ASSISTANT -> instance.addAssistantMessage(message.content)
                        else -> instance.addUserMessage(message.content)
                    }
                }
                instance.getResponseAsFlow(last.content).collect { token -> send(token) }
            } catch (e: CancellationException) {
                // 用户点了「停止」：原样抛，别包成 IOException 让界面弹错误
                throw e
            } catch (e: UnsatisfiedLinkError) {
                FileLogger.e(TAG, "端侧推理原生库不可用", e)
                throw IOException("端侧推理不可用：当前设备缺少可用的推理库", e)
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                FileLogger.e(TAG, "端侧推理失败", e)
                throw IOException("端侧推理失败：${e.message ?: "未知错误"}", e)
            }
        }
    }

    /** 中断当前生成（用户点「停止」）；不影响已加载的模型。 */
    fun stop() {
        try {
            llm?.stop()
        } catch (e: Exception) {
            FileLogger.w(TAG, "中断端侧推理失败", e)
        }
    }

    /**
     * 释放原生上下文（退出会话 / 省内存）。
     *
     * 故意不做 suspend：调用点常在 `onCleared` 之类无法挂起的地方；[SmolLM.close] 本身是同步的。
     * 代价是不与 [mutex] 互斥——若此时正在生成，[stop] 会由 `close` 带走，属可接受行为。
     */
    fun unload() {
        val current = llm
        llm = null
        loadedParams = null
        _loadedModelId.value = null
        try {
            current?.close()
        } catch (e: Exception) {
            FileLogger.w(TAG, "释放端侧模型失败", e)
        }
    }

    /** 真正建实例 + 读权重；调用前必须已持有 [mutex]。 */
    private suspend fun loadLocked(id: String, params: LocalGenParams) {
        if (!manager.isSupportedOnThisDevice()) {
            throw IOException(context.getString(R.string.settings_local_model_not_supported))
        }
        if (!manager.isReady(id)) {
            throw IOException(context.getString(R.string.settings_local_model_not_downloaded))
        }
        val file = manager.verifiedModelFile(id)
        if (!file.isFile) {
            throw IOException(context.getString(R.string.settings_local_model_not_downloaded))
        }
        // 换模型先放掉旧的，避免内存里同时存在两份权重
        releaseLocked()
        val startedAt = System.currentTimeMillis()
        val instance = SmolLM()
        try {
            instance.load(
                modelPath = file.absolutePath,
                params = SmolLM.InferenceParams(
                    temperature = params.temperature,
                    topP = params.topP,
                    topK = DEFAULT_TOP_K,
                    repeatPenalty = DEFAULT_REPEAT_PENALTY,
                    // 多轮要靠库内部历史；chatTemplate 传 null 让它读 GGUF 里的官方模板
                    storeChats = true,
                    contextSize = null,
                    chatTemplate = null,
                    numThreads = params.numThreads,
                    useMmap = true,
                    useMlock = false,
                ),
            )
        } catch (e: UnsatisfiedLinkError) {
            FileLogger.e(TAG, "加载端侧模型原生库失败：$id", e)
            throw IOException("端侧推理不可用：当前设备缺少可用的推理库", e)
        } catch (e: Exception) {
            FileLogger.e(TAG, "加载端侧模型失败：$id", e)
            throw IOException("端侧模型加载失败：${e.message ?: "未知错误"}", e)
        }
        llm = instance
        loadedParams = params
        _loadedModelId.value = id
        FileLogger.i(TAG, "端侧模型加载完成：$id，耗时 ${System.currentTimeMillis() - startedAt} ms")
    }

    private fun releaseLocked() {
        val current = llm
        llm = null
        loadedParams = null
        _loadedModelId.value = null
        try {
            current?.close()
        } catch (e: Exception) {
            FileLogger.w(TAG, "释放旧端侧模型失败", e)
        }
    }

    private companion object {
        const val TAG = "LocalLlmEngine"

        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"

        /** 默认采样参数（[ensureLoaded] 用）；top-k 与重复惩罚沿用 Qwen 官方推荐值。 */
        val DEFAULT_PARAMS = LocalGenParams()

        const val DEFAULT_TOP_K = 40
        const val DEFAULT_REPEAT_PENALTY = 1.1f
    }
}
