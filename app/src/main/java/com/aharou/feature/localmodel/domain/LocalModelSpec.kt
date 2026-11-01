package com.aharou.feature.localmodel.domain

/**
 * 端侧对话模型的元数据（一个模型一条，见 [LocalModels]）。
 *
 * [sizeBytes] 与 [sha256] 都是官方发布值：字节数用来判「下完没有」，sha256 用来判「下对没有」。
 * 只看文件在不在会把上次断点留下的半成品当成就绪，所以两样都要。
 */
data class LocalModelSpec(
    /** 稳定标识，也是落盘目录名：`filesDir/local_models/<id>/`。 */
    val id: String,
    /** 展示名（设置页那一行用）。 */
    val displayName: String,
    /** 权重文件名（GGUF），与模型仓库里的文件名一致。 */
    val fileName: String,
    /** 权重字节数：下载完成的判据之一。 */
    val sizeBytes: Long,
    /** 权重 sha256（小写十六进制），校验下载结果的完整性。 */
    val sha256: String,
    /** 建议的最小内存（GB），只用于设置页给提示，不做硬拦截。 */
    val minRamGb: Int,
    /** 下载地址候选，按优先级排列；前一个失败才试下一个。 */
    val urls: List<String>,
    /** 上下文窗口上限（token）；真实值以 GGUF 元数据为准，这里只供 UI 展示与预估显存。 */
    val contextSize: Long,
)

/**
 * 单个模型的落盘状态。
 *
 * [installedBytes] 是正式文件（`<fileName>`）的字节数，[partialBytes] 是断点文件
 * （`<fileName>.part`）的字节数，两者都为 null 表示这台机器上根本没有这个模型。
 */
data class LocalModelStatus(
    val id: String,
    /** 应有的字节数（[LocalModelSpec.sizeBytes]）。 */
    val expectedBytes: Long,
    /** 正式文件字节数；不存在为 null。 */
    val installedBytes: Long?,
    /** 半成品（.part）字节数；不存在为 null。 */
    val partialBytes: Long?,
) {
    /** 正式文件存在且字节数正好：可以加载。 */
    val ready: Boolean get() = installedBytes == expectedBytes

    /** 从未下载过（与「下过但残缺」区分开，设置页文案不同）。 */
    val neverDownloaded: Boolean get() = installedBytes == null && partialBytes == null

    /** 有半成品且尚未就绪：可以做断点续传。 */
    val partial: Boolean get() = partialBytes != null && !ready
}

/** 端侧模型的一次操作状态；设置页照它显示进度/校验/失败原因。 */
sealed interface LocalModelState {
    data object Idle : LocalModelState

    /** 下载中；[progress] 为 0f~1f（含续传起点）。 */
    data class Downloading(val id: String, val progress: Float) : LocalModelState

    /** 已下完、正在算 sha256。大文件要几秒，界面上要有个「校验中」。 */
    data class Verifying(val id: String) : LocalModelState

    /** 校验通过、已原子落盘更新；可以加载推理。 */
    data class Ready(val id: String) : LocalModelState

    /** 失败；[message] 已本地化，可直接显示。 */
    data class Failed(val id: String, val message: String) : LocalModelState
}
