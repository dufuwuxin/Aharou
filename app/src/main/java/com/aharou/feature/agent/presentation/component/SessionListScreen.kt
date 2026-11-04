package com.aharou.feature.agent.presentation.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Brand
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.feature.agent.presentation.AgentUIState
import com.aharou.feature.agent.domain.model.ChatSession
import compose.icons.FeatherIcons
import compose.icons.feathericons.MessageSquare
import compose.icons.feathericons.Plus
import java.util.Locale

/**
 * 独立会话列表页（主路由首页）。启动后先进这里，点进任意会话才会切换到聊天页。
 *
 * 展示逻辑：
 * - 仅列出当前工作区的顶层会话（不展开子代理子会话，避免与侧边栏双重展示）。
 * - 每条行显示标题/时间/状态点（等待权限 / 正在执行 / 空闲）；当前会话高亮。
 * - 「新建会话」按钮改为右下角悬浮（棕黄色），符合 Material 惯例。
 * - 长按可删除（与原侧边栏的长按菜单保留同样语义，但本页只暴露删除，重命名/置顶走侧边栏）。
 */
@Composable
fun SessionListScreen(
    sessions: List<ChatSession>,
    currentSessionId: String?,
    agentStates: Map<String, AgentUIState>,
    awaitingPermissionSessionIds: Set<String>,
    workspaceName: String,
    onSelect: (ChatSession) -> Unit,
    onDelete: (ChatSession) -> Unit,
    onNewSession: () -> Unit,
    showNewSession: Boolean = true,
) {
    // 长按触发删除二次确认。
    var pendingDelete by remember { mutableStateOf<ChatSession?>(null) }

    // 根用 Box：内层 Column 铺满（标题 + 会话列表），右下角叠加一个棕黄色悬浮「新建会话」按钮。
    // 之前它顶在右上角、蓝色，不符合 Material「悬浮按钮在右下角」的惯例，也容易被当成操作菜单。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top)),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            // 顶栏：标题 + 当前工作区名（新建会话按钮已移到右下角悬浮）。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg)
                    .padding(top = Spacing.md, bottom = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(com.aharou.R.string.session_list_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (workspaceName.isNotBlank()) {
                        Text(
                            text = workspaceName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.semanticColors.subtleText,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom)),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = Spacing.lg,
                    vertical = Spacing.sm
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
            if (sessions.isEmpty()) {
                item {
                    EmptyStateRow()
                }
            }
            items(sessions, key = { it.id }) { session ->
                // 圆角卡片容器：整 item 最小 56dp（icon+标题+padding），卡片间距 8.dp
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.semanticColors.cardSurface,
                    tonalElevation = 1.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                ) {
                    // 垂直居中：让 icon+标题+padding 在 56dp 卡片里均匀分布
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        ChatSessionRow(
                            session = session,
                            selected = session.id == currentSessionId,
                            isExecuting = agentStates[session.id] is AgentUIState.Loading || agentStates[session.id] is AgentUIState.Streaming,
                            awaitingPermission = session.id in awaitingPermissionSessionIds,
                            pinned = session.isPinned,
                            onClick = { onSelect(session) },
                            onLongClick = { pendingDelete = session },
                            trailing = null
                        )
                    }
                }
            }
        }  // LazyColumn 闭合
    }  // Column 闭合；FAB 独立放在 Box 底右，不能用 align(BottomEnd) 放 Column 里（类型不匹配）
    if (showNewSession) androidx.compose.material3.FloatingActionButton(
            onClick = onNewSession,
            containerColor = Brand.Orange,
            contentColor = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.BottomEnd)
                .padding(Spacing.lg)
                .size(56.dp),
        ) {
            Icon(
                FeatherIcons.Plus,
                contentDescription = stringResource(com.aharou.R.string.chat_new_session),
                modifier = Modifier.size(24.dp),
            )
        }
    }

    // 删除确认弹窗。
    pendingDelete?.let { pending ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(com.aharou.R.string.chat_batch_delete_title)) },
            text = {
                Text(
                    stringResource(com.aharou.R.string.session_delete_confirm),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    onDelete(pending)
                    pendingDelete = null
                }) {
                    Text(
                        stringResource(com.aharou.R.string.common_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(com.aharou.R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun EmptyStateRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xxl),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            FeatherIcons.MessageSquare,
            contentDescription = null,
            tint = MaterialTheme.semanticColors.subtleText,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = stringResource(com.aharou.R.string.session_list_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.semanticColors.subtleText
        )
    }
}