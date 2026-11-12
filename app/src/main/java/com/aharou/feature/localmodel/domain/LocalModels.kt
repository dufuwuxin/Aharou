package com.aharou.feature.localmodel.domain

/**
 * 端侧对话模型清单。
 *
 * 为什么「推理库随包、权重按需下载」：
 * - 推理库（SmolChatAndroidLib）带预编译 llama.cpp JNI 与多档 NEON `.so`，AAR 共 17.8MB，
 *   随包没有商量余地——本项目的立场是不引入 NDK/CMake，只能吃预编译产物。
 * - 权重则是数百 MB 到数 GB 的 GGUF，塞进 APK 会显著增加安装体积，因而按需下载。
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

    /** Qwen2.5 3B Instruct（q4_k_m）；内存为保守建议，未在设备上验证加载或推理。 */
    val MEDIUM = LocalModelSpec(
        id = "qwen2.5-3b",
        displayName = "Qwen2.5 3B（约 2.1GB，未设备验证）",
        fileName = "qwen2.5-3b-instruct-q4_k_m.gguf",
        sizeBytes = 2_104_932_768L,
        sha256 = "626b4a6678b86442240e33df819e00132d3ba7dddfe1cdc4fbb18e0a9615c62d",
        minRamGb = 8,
        urls = listOf(
            "$QWEN_BASE/Qwen2.5-3B-Instruct-GGUF/resolve/master/qwen2.5-3b-instruct-q4_k_m.gguf",
        ),
        contextSize = 4096L,
    )

    /**
     * 7B 的官方 q4_k_m 是分片；现有下载器只管理单文件，因此选择单文件 q3_k_m。
     * 内存为保守建议，未在设备上验证加载或推理；不代表优于小模型。
     */
    val LARGE = LocalModelSpec(
        id = "qwen2.5-7b-q3-k-m",
        displayName = "Qwen2.5 7B（Q3_K_M，约 3.8GB，未设备验证）",
        fileName = "qwen2.5-7b-instruct-q3_k_m.gguf",
        sizeBytes = 3_808_391_072L,
        sha256 = "a96b16179dc6cc9afdf0cf7a96a80c199cbd00b9be207c3465be21cb721cca5e",
        minRamGb = 12,
        urls = listOf(
            "$QWEN_BASE/Qwen2.5-7B-Instruct-GGUF/resolve/master/qwen2.5-7b-instruct-q3_k_m.gguf",
        ),
        contextSize = 4096L,
    )

    /** 全部可选模型（设置页按这个顺序排）。 */
    val ALL: List<LocalModelSpec> = listOf(TINY, SMALL, MEDIUM, LARGE)

    /** 按 id 查模型；未知 id 返回 null。 */
    fun byId(id: String): LocalModelSpec? = ALL.firstOrNull { it.id == id }
}
