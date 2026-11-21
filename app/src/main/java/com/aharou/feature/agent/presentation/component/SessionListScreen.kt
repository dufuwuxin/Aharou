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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import com.aharou.feature.workspace.domain.model.Workspace
import compose.icons.FeatherIcons
import compose.icons.feathericons.Folder
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
    activeSessionIds: Set<String> = emptySet(),
    sessionGroups: Map<String, List<ChatSession>> = emptyMap(),
    workspaceStatuses: Map<String, String> = emptyMap(),
    availableWorkspaces: List<Workspace> = emptyList(),
    onMove: (ChatSession, String) -> Unit = { _, _ -> },
) {
    var pendingDelete by remember { mutableStateOf<ChatSession?>(null) }
    var pendingMove by remember { mutableStateOf<ChatSession?>(null) }

    // 根用 Box：内层 Column 铺满（标题 + 会话列表），右下角叠加一个棕黄色悬浮「新建会话」按钮。
    // 之前它顶在右上角、蓝色，不符合 Material「悬浮按钮在右下角」的惯例，也容易被当成操作菜单。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            // 当前工作区名；新建会话按钮保留在右下角。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.md, bottom = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
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

            val groups = sessionGroups.filterValues { it.isNotEmpty() }
            val workspacePaths = (groups.keys + workspaceStatuses.keys).sorted()
            val grouped = workspacePaths.isNotEmpty()
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("session_list"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = Spacing.sm, bottom = 80.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!grouped && sessions.isEmpty()) {
                    item { EmptyStateRow() }
                }
                if (grouped) {
                    workspacePaths.forEach { group ->
                        item(key = "workspace:$group") {
                            Text(
                                text = group.ifBlank { stringResource(R.string.common_workspace) },
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.semanticColors.subtleText,
                                modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xs)
                            )
                        }
                        workspaceStatuses[group]?.let { status ->
                            item(key = "workspace-status:$group") {
                                Text(status, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.semanticColors.subtleText)
                            }
                        }
                        items(groups[group].orEmpty(), key = { "session:${it.id}" }) { session ->
                            SessionCard(session, currentSessionId, activeSessionIds, awaitingPermissionSessionIds,
                                onSelect, { pendingDelete = session }, { pendingMove = session })
                        }
                    }
                } else {
                    items(sessions, key = { it.id }) { session ->
                        SessionCard(session, currentSessionId, activeSessionIds, awaitingPermissionSessionIds,
                            onSelect, { pendingDelete = session }, { pendingMove = session })
                    }
                }
            }
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

    pendingMove?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingMove = null },
            title = { Text(stringResource(R.string.session_workspace_move_title)) },
            text = {
                Column {
                    availableWorkspaces.filter { it.available && it.path != pending.workspacePath }.forEach { workspace ->
                        TextButton(onClick = {
                            onMove(pending, workspace.path)
                            pendingMove = null
                        }) { Text(workspace.name) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pendingMove = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
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
private fun SessionCard(
    session: ChatSession,
    currentSessionId: String?,
    activeSessionIds: Set<String>,
    awaitingPermissionSessionIds: Set<String>,
    onSelect: (ChatSession) -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.semanticColors.cardSurface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ChatSessionRow(
                session = session,
                selected = session.id == currentSessionId,
                isExecuting = session.id in activeSessionIds,
                awaitingPermission = session.id in awaitingPermissionSessionIds,
                pinned = session.isPinned,
                onClick = { onSelect(session) },
                onLongClick = onDelete,
                trailing = {
                    IconButton(onClick = onMove, enabled = session.id !in activeSessionIds &&
                        session.id !in awaitingPermissionSessionIds) {
                        Icon(FeatherIcons.Folder, contentDescription = stringResource(R.string.session_workspace_move))
                    }
                }
            )
        }
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