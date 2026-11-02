package com.aharou.feature.agent.presentation.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import com.aharou.feature.agent.presentation.AgentUIMessage
import com.aharou.feature.agent.presentation.MessageRole

/**
 * 流式尾巴的三种状态，用于 [when] 分支分发。
 *
 * 早先用 [androidx.compose.animation.Crossfade] 做淡入，但 Crossfade 按 targetState
 * 缓存 content 子组合——流式期间 targetState 一直不变，文本增长时不会重新调用 content，
 * 导致 [StreamingBubble] 收不到后续文本、停在首句。故改用枚举 + 直接 [when] 分发。
 */
internal enum class TailKind { THINKING, STREAMING, COMPACTING, RETRYING, KEY_SWITCHED, NONE }

/** 附件上传意图：决定拉起哪个 picker（文件/图片/相机），供 [requestUploadFile/Image/TakePhoto] 驱动。 */
internal enum class UploadIntent { FILE, IMAGE, PHOTO }

/** 悬浮层（横幅/面板/输入框）与最后一条消息的间距。 */
internal val FLOATING_LAYER_GAP_DP = 8.dp

/**
 * 贴底校准的死区（dp）：最后内容底部超出安全区不足这一档时不动视口。
 *
 * 流式追加与 Markdown 异步重排会让内容底部在亚像素~数像素量级来回动，此前按 2px 容差逐帧
 * `scrollToItem(末尾)` 回锚点，实际表现是「刚补下去又被顶回来」的反向抖动；死区把它放宽到
 * 一次追加自然盖过的量级，每次也只按超出量平移（见 `calibrateToAnchor`），不再跳回固定锚点。
 */
internal val AUTO_SCROLL_DEADBAND_DP = 8.dp

/** AI 收工后继续逐帧校准的时长（ms）：md 异步解析仍可能改高度，不能一停就收手。 */
internal const val CALIBRATE_TAIL_MS = 1_200L

/** 一次滚动手势的起点：外层视口锚点 + 手势前的贴底跟随态。 */
internal data class ScrollGestureStart(val index: Int, val offset: Int, val following: Boolean)

/** 连续新消息的入场错开间隔（ms）与总上限：一次插入很多卡片时不能让最后一张等好几秒。 */
internal const val MESSAGE_ENTRY_STAGGER_MS = 90L
internal const val MESSAGE_ENTRY_MAX_STAGGER_MS = 360L

/** 消息未就绪时延迟多久才显示加载提示（ms）：本地读库很快，立即显示反而闪。 */
internal const val MESSAGES_LOADING_HINT_DELAY_MS = 220L

/**
 * 记住「最近一次非空值」但无需自身触发重组的通用工具：源值变化本身就会重组读取点，
 * 进快照系统反而会多引发一次无效重组。
 */
@Composable
internal fun <T : Any> rememberLastNonNull(value: T?): T? {
    val holder = remember { LastNonNullHolder<T>() }
    if (value != null) holder.value = value
    return holder.value
}

internal class LastNonNullHolder<T : Any>(var value: T? = null)

/**
 * 工具卡片入场动画的调度器：决定哪些消息该播入场、以及各自错开多久。
 *
 * 判据是「本调度器存续期间新追加到尾部」，而不是「timestamp 距今 N 秒内」——
 * 后者在切页返回时会把仍在时间窗内的那批消息再判成新消息，动画重播一遍。
 * 首次调用时列表里已有的消息一律记为存量；向上翻页加载进来的历史比已见最大时间戳更旧，
 * 同样不入场。会话切换时整个调度器重建，新会话的存量消息也不入场。
 */
internal class MessageEntryScheduler {
    private val seen = mutableSetOf<String>()
    private val delays = mutableMapOf<String, Long>()
    private var initialized = false
    private var maxSeenTimestamp = Long.MIN_VALUE

    /** 返回「消息 id → 入场延迟（ms）」；不在表内的消息直接显示。 */
    fun schedule(messages: List<AgentUIMessage>): Map<String, Long> {
        if (!initialized) {
            initialized = true
            messages.forEach { seen += it.id }
            maxSeenTimestamp = messages.maxOfOrNull { it.timestamp } ?: Long.MIN_VALUE
            return emptyMap()
        }
        var consecutive = 0
        for (message in messages) {
            if (message.id in seen) {
                consecutive = 0
                continue
            }
            seen += message.id
            val appendedAtTail = message.timestamp >= maxSeenTimestamp
            maxSeenTimestamp = maxOf(maxSeenTimestamp, message.timestamp)
            if (appendedAtTail && message.role == MessageRole.TOOL) {
                delays[message.id] = (consecutive * MESSAGE_ENTRY_STAGGER_MS)
                    .coerceAtMost(MESSAGE_ENTRY_MAX_STAGGER_MS)
                consecutive++
            } else {
                consecutive = 0
            }
        }
        return delays.toMap()
    }
}
