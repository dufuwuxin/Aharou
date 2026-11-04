package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppTextField
import com.aharou.feature.agent.domain.subagent.AgentTeamStageForm
import com.aharou.feature.agent.domain.subagent.TeamInterfaceEdgeForm
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowRight
import compose.icons.feathericons.Plus

/**
 * 协作对齐（接口边）编辑器：把「谁和谁对齐什么」声明成可视化边。
 * 已有边按「A → B：内容」逐行列出可删除；底部「添加对齐」打开勾选弹层。
 * 阶段不足两名成员时只提示——没有对象可对齐。
 */
@Composable
internal fun InterfaceEdgeEditor(
    stage: AgentTeamStageForm,
    members: List<String>,
    onChange: (AgentTeamStageForm) -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }
    Text(
        text = stringResource(R.string.team_interface_title),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
    Text(
        text = stringResource(R.string.team_interface_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (members.size < 2) {
        Text(
            text = stringResource(R.string.team_interface_need_two),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        if (stage.interfaces.isEmpty()) {
            Text(
                text = stringResource(R.string.team_interface_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            stage.interfaces.forEachIndexed { index, edge ->
                InterfaceEdgeRow(
                    edge = edge,
                    onRemove = {
                        onChange(stage.copy(interfaces = stage.interfaces.filterIndexed { i, _ -> i != index }))
                    }
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.xs),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = { showAdd = true }) {
                Icon(
                    imageVector = FeatherIcons.Plus,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Text(stringResource(R.string.team_interface_add))
            }
        }
    }
    if (showAdd) {
        InterfaceEdgeSheet(
            members = members,
            existing = stage.interfaces,
            onConfirm = { added ->
                onChange(stage.copy(interfaces = stage.interfaces + added))
                showAdd = false
            },
            onDismiss = { showAdd = false }
        )
    }
}

/** 单条接口边：第一行「A → B」，第二行是对齐内容摘要；右侧删除。 */
@Composable
private fun InterfaceEdgeRow(edge: TeamInterfaceEdgeForm, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.semanticColors.cardSurface, RoundedCornerShape(10.dp))
            .padding(start = Spacing.md, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = edge.from,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    imageVector = FeatherIcons.ArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(14.dp)
                )
                Text(
                    text = edge.with,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            if (edge.about.isNotBlank()) {
                Text(
                    text = edge.about,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.common_remove),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 添加对齐的二级弹层：先勾来源（单选）、再勾目标（可多选，排除来源）、最后填对齐内容。
 * 一次确认生成 N 条边（同一来源 → 每个目标一条），已存在的 from → with 组合自动跳过。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun InterfaceEdgeSheet(
    members: List<String>,
    existing: List<TeamInterfaceEdgeForm>,
    onConfirm: (List<TeamInterfaceEdgeForm>) -> Unit,
    onDismiss: () -> Unit
) {
    var from by rememberSaveable { mutableStateOf("") }
    var targets by rememberSaveable { mutableStateOf(emptySet<String>()) }
    var about by rememberSaveable { mutableStateOf("") }
    val pending = targets.map { TeamInterfaceEdgeForm(from = from, with = it, about = about.trim()) }
        .filter { added ->
            existing.none { it.from.equals(added.from, ignoreCase = true) && it.with.equals(added.with, ignoreCase = true) }
        }
    val canConfirm = from.isNotBlank() && pending.isNotEmpty()

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = stringResource(R.string.team_interface_add_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.team_interface_source),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                members.forEach { name ->
                    FilterChip(
                        selected = from == name,
                        onClick = { from = if (from == name) "" else name },
                        label = { Text(name) }
                    )
                }
            }
            Text(
                text = stringResource(R.string.team_interface_targets),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                members.filter { it != from }.forEach { name ->
                    val picked = name in targets
                    FilterChip(
                        selected = picked,
                        onClick = {
                            targets = if (picked) targets - name else targets + name
                        },
                        label = { Text(name) }
                    )
                }
            }
            AppTextField(
                value = about,
                onValueChange = { about = it },
                label = stringResource(R.string.team_interface_about),
                singleLine = true
            )
            Divider(color = MaterialTheme.colorScheme.outlineVariant)
            Button(
                onClick = { onConfirm(pending); onDismiss() },
                enabled = canConfirm,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.team_interface_confirm, pending.size),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    }
}
