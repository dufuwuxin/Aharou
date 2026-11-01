package com.aharou.feature.localmodel.domain

/**
 * 端侧对话模型清单。
 *
 * 为什么「推理库随包、权重按需下载」：
 * - 推理库（SmolChatAndroidLib）带预编译 llama.cpp JNI 与多档 NEON `.so`，AAR 共 17.8MB，
 *   随包没有商量余地——本项目的立场是不引入 NDK/CMake，只能吃预编译产物。
 * - 权重则是 0.5~1.5GB 的 GGUF，塞进 APK 等于把安装包体积翻十几倍，低端机连安装都过不去。
 * - 而 GitCode/GitHub Release 的单文件上限是 100MB，权重也放不上去，所以走 ModelScope 第一方直链
 *   （国内直连实测可下，不需要代理）。
 *
 * 于是这里只记「去哪下、下多大、要什么架构」，由 [LocalModelManager] 负责下载、校验、落盘。
 */
object LocalModels {

    /** 模型根目录（相对 `filesDir`）。 */
    const val MODEL_ROOT_DIR = "local_models"

    /** ModelScope 上的 Qwen2.5 GGUF 直链前缀。 */
    private const val QWEN_BASE = "https://modelscope.cn/models/Qwen"

    /**
     * Qwen2.5 0.5B Instruct（q4_k_m）。
     *
     * 低端机首选：约 490MB 权重 + 4K 上下文，3GB 内存的机器也能跑起来，
     * 首字延迟明显低于 1.5B，适合「随手问一句」。
     */
    val TINY = LocalModelSpec(
        id = "qwen2.5-0.5b",
        displayName = "Qwen2.5 0.5B（约 490MB，低端机首选）",
        fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
        sizeBytes = 491_400_032L,
        sha256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
        minRamGb = 3,
        urls = listOf(
            "$QWEN_BASE/Qwen2.5-0.5B-Instruct-GGUF/resolve/master/qwen2.5-0.5b-instruct-q4_k_m.gguf",
        ),
        contextSize = 4096L,
    )

    /**
     * Qwen2.5 1.5B Instruct（q4_k_m）。
     *
     * 效果更好的一档：约 1.1GB，多步指令与中文长回答明显稳，代价是首字慢、内存要 4GB 以上。
     */
    val SMALL = LocalModelSpec(
        id = "qwen2.5-1.5b",
        displayName = "Qwen2.5 1.5B（约 1.1GB，效果更好）",
        fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        sizeBytes = 1_117_320_736L,
        sha256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
        minRamGb = 4,
        urls = listOf(
            "$QWEN_BASE/Qwen2.5-1.5B-Instruct-GGUF/resolve/master/qwen2.5-1.5b-instruct-q4_k_m.gguf",
        ),
        contextSize = 4096L,
    )

    /** 全部可选模型（设置页按这个顺序排）。 */
    val ALL: List<LocalModelSpec> = listOf(TINY, SMALL)

    /** 按 id 查模型；未知 id 返回 null。 */
    fun byId(id: String): LocalModelSpec? = ALL.firstOrNull { it.id == id }
}
