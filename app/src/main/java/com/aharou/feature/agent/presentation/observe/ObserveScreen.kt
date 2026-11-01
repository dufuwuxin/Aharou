package com.aharou.feature.agent.presentation.observe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.feature.agent.domain.subagent.ObserveDirection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 只读观察面板（路由层）：把 [ObservePanelViewModel] 接上无状态 UI。
 *
 * 宿主只需要一个 Composable 入口即可，导航参数里带 `sessionId` 就自动观察那棵会话树。
 */
@Composable
fun ObserveScreenRoute(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    viewModel: ObservePanelViewModel = hiltViewModel()
) {
    // 宿主若没把 sessionId 放进导航参数，就从全局 holder 取（顶栏「观察」入口写的就是它）。
    val target = ObserveTargetHolder.sessionId
    LaunchedEffect(target) { target?.let(viewModel::bind) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ObserveScreen(
        state = state,
        onDirectionChange = viewModel::setDirection,
        onDepthChange = viewModel::setDepth,
        onSelectMember = viewModel::selectMember,
        onRefresh = viewModel::refresh,
        onBack = onBack,
        modifier = modifier
    )
}

/**
 * 只读观察面板本体（无状态）。
 *
 * 三条边界直接写在 UI 上：
 * - 顶部「只读」徽标 + 「只读观察 · 上级/下级第 N 层」，让用户随时知道**看的不是自己那页，也改不了任何东西**；
 * - 没有输入框、没有停止按钮、没有工具操作 —— 面板只有「换看待什么」的交互；
 * - 视觉走浅色：全部用 MaterialTheme 的 surface / surfaceVariant / primaryContainer 与 [Spacing] 档位，
 *   不铺深色大色块（用户强制偏好）。
 */
@Composable
fun ObserveScreen(
    state: ObservePanelUiState,
    onDirectionChange: (ObserveDirection) -> Unit,
    onDepthChange: (Int) -> Unit,
    onSelectMember: (String?) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        ObserveHeader(
            state = state,
            onDirectionChange = onDirectionChange,
            onDepthChange = onDepthChange,
            onSelectMember = onSelectMember,
            onRefresh = onRefresh,
            onBack = onBack
        )
        HairLine()
        ObserveMessageList(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
    }
}

// ---------------------------------------------------------------- 顶部一行

@Composable
private fun ObserveHeader(
    state: ObservePanelUiState,
    onDirectionChange: (ObserveDirection) -> Unit,
    onDepthChange: (Int) -> Unit,
    onSelectMember: (String?) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            // 第一行：只读徽标 + 方向/层级 + up-down 切换 + 刷新
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillButton(
                    text = stringResource(R.string.observe_action_back),
                    selected = false,
                    onClick = onBack
                )
                Spacer(Modifier.width(Spacing.sm))
                ReadOnlyBadge()
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(
                        R.string.observe_readonly_title,
                        directionText(state.direction),
                        state.depth
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(Spacing.sm))
                DirectionSwitch(
                    direction = state.direction,
                    onDirectionChange = onDirectionChange
                )
                Spacer(Modifier.width(Spacing.xs))
                PillButton(
                    text = stringResource(R.string.observe_action_refresh),
                    selected = false,
                    onClick = onRefresh
                )
            }

            // 第二行：正在看谁（标题 + 角色 + 运行态）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (state.observedTitle.isBlank()) stringResource(R.string.observe_unnamed_session)
                    else state.observedTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(Spacing.sm))
                StatePill(state.observedState)
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = when {
                        state.observingSelf -> stringResource(R.string.observe_self)
                        !state.observedRole.isNullOrBlank() -> state.observedRole
                        else -> stringResource(R.string.observe_main_session)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            // 第三行：层级深度（1..5）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.observe_label_depth),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(Spacing.sm))
                for (level in 1..5) {
                    PillButton(
                        text = level.toString(),
                        selected = state.depth == level,
                        onClick = { onDepthChange(level) }
                    )
                    Spacer(Modifier.width(Spacing.xs))
                }
            }

            // 第四行：可点选的候选成员（自己 + 上级/下级）
            if (state.members.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    contentPadding = PaddingValues(vertical = Spacing.xs)
                ) {
                    items(state.members, key = { it.id }) { member ->
                        MemberPill(
                            member = member,
                            selected = if (member.isSelf) state.observingSelf
                            else member.id == state.observedMemberId,
                            onClick = { onSelectMember(if (member.isSelf) null else member.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadOnlyBadge() {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text = stringResource(R.string.observe_readonly_badge),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp)
        )
    }
}

@Composable
private fun DirectionSwitch(
    direction: ObserveDirection,
    onDirectionChange: (ObserveDirection) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        PillButton(
            text = stringResource(R.string.observe_direction_up),
            selected = direction == ObserveDirection.UP,
            onClick = { onDirectionChange(ObserveDirection.UP) }
        )
        PillButton(
            text = stringResource(R.string.observe_direction_down),
            selected = direction == ObserveDirection.DOWN,
            onClick = { onDirectionChange(ObserveDirection.DOWN) }
        )
    }
}

@Composable
private fun PillButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)
        )
    }
}

@Composable
private fun MemberPill(
    member: ObserveMemberItem,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(999.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = depthPrefix(member),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text = if (member.title.isBlank()) stringResource(R.string.observe_unnamed_session)
                else member.title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp)
            )
            Spacer(Modifier.width(Spacing.xs))
            StatePill(member.runState)
        }
    }
}

@Composable
private fun StatePill(state: ObserveRunState) {
    val tint: Color = when (state) {
        ObserveRunState.RUNNING -> MaterialTheme.semanticColors.info
        ObserveRunState.DONE -> MaterialTheme.semanticColors.success
        ObserveRunState.FAILED -> MaterialTheme.colorScheme.error
        ObserveRunState.STOPPED, ObserveRunState.INTERRUPTED -> MaterialTheme.semanticColors.warning
        ObserveRunState.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = tint.copy(alpha = 0.12f),
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            text = runStateLabel(state),
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 1.dp)
        )
    }
}

// ---------------------------------------------------------------- 消息流

@Composable
private fun ObserveMessageList(
    state: ObservePanelUiState,
    modifier: Modifier = Modifier
) {
    // 工具卡片的折叠状态：按消息 id 记，默认折叠（观察时先看骨架，需要细节再展开）。
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        state.error?.let { err ->
            item(key = "error") {
                val text = when (err) {
                    is ObserveError.SessionMissing ->
                        stringResource(R.string.observe_session_missing, err.idPrefix)
                    is ObserveError.TreeReadFailed ->
                        stringResource(R.string.observe_error_tree, err.detail)
                }
                HintRow(text, tone = MaterialTheme.colorScheme.error)
            }
        }
        if (state.sessionId == null) {
            item(key = "unbound") { HintRow(stringResource(R.string.observe_unbound)) }
            return@LazyColumn
        }
        state.emptyHint?.let { hint ->
            item(key = "empty-hint") {
                val text = when (hint) {
                    ObserveEmptyHint.NO_PARENT -> stringResource(R.string.observe_hint_no_parent)
                    ObserveEmptyHint.NO_CHILD -> stringResource(R.string.observe_hint_no_child)
                }
                HintRow(text)
            }
        }
        if (state.messages.isEmpty()) {
            item(key = "empty-stream") { HintRow(stringResource(R.string.observe_empty_stream)) }
            return@LazyColumn
        }
        items(state.messages, key = { it.id }) { message ->
            when (message) {
                is ObserveMessage.Bubble -> BubbleRow(message)
                is ObserveMessage.ToolCard -> ToolCardRow(
                    message = message,
                    expanded = expanded[message.id] == true,
                    onToggle = { expanded[message.id] = !(expanded[message.id] ?: false) }
                )
            }
        }
    }
}

@Composable
private fun BubbleRow(message: ObserveMessage.Bubble) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (message.fromUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 560.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(
                            if (message.fromUser) R.string.observe_role_user
                            else R.string.observe_role_ai
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        text = timeText(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = message.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message.fromUser) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 工具调用卡片：标题行常显（名字 + 状态），参数/结果折叠在下方。 */
@Composable
private fun ToolCardRow(
    message: ObserveMessage.ToolCard,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val statusTint: Color = when {
        message.pending -> MaterialTheme.semanticColors.info
        message.isError -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.semanticColors.success
    }
    val statusText = stringResource(
        when {
            message.pending -> R.string.observe_tool_pending
            message.isError -> R.string.observe_tool_failed
            else -> R.string.observe_tool_success
        }
    )

    Surface(
        onClick = onToggle,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (expanded) "▾" else "▸",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = if (message.name.isBlank()) stringResource(R.string.observe_tool_fallback_name)
                    else message.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(Spacing.sm))
                Surface(
                    color = statusTint.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusTint,
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 1.dp)
                    )
                }
            }

            if (!expanded) return@Column

            message.args?.let { args ->
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = stringResource(R.string.observe_tool_args),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = args,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 12,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                )
            }
            message.result?.let { result ->
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = stringResource(R.string.observe_tool_result),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (message.isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 20,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun HintRow(text: String, tone: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = tone,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
        )
    }
}

@Composable
private fun HairLine() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

// ---------------------------------------------------------------- 文案小工具

@Composable
private fun directionText(direction: ObserveDirection): String =
    stringResource(
        if (direction == ObserveDirection.UP) R.string.observe_direction_up
        else R.string.observe_direction_down
    )

/** 运行态徽标的本地化文案。 */
@Composable
private fun runStateLabel(state: ObserveRunState): String = stringResource(
    when (state) {
        ObserveRunState.RUNNING -> R.string.observe_state_running
        ObserveRunState.DONE -> R.string.observe_state_done
        ObserveRunState.FAILED -> R.string.observe_state_failed
        ObserveRunState.STOPPED -> R.string.observe_state_stopped
        ObserveRunState.INTERRUPTED -> R.string.observe_state_interrupted
        ObserveRunState.UNKNOWN -> R.string.observe_state_unknown
    }
)

/** 候选成员的层级前缀：自己 / 父 / 祖父 / 下级第 N 层。 */
@Composable
private fun depthPrefix(member: ObserveMemberItem): String = when {
    member.isSelf -> stringResource(R.string.observe_prefix_self)
    member.depth < 0 -> when (-member.depth) {
        1 -> stringResource(R.string.observe_prefix_parent)
        2 -> stringResource(R.string.observe_prefix_grandparent)
        else -> stringResource(R.string.observe_prefix_up_n, -member.depth)
    }

    else -> stringResource(R.string.observe_prefix_down_n, member.depth)
}

private fun timeText(timestamp: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
