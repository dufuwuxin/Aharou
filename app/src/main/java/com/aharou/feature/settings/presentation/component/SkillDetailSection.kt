package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import com.aharou.core.ui.AppSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.feature.agent.domain.skill.SkillScope
import com.aharou.feature.agent.presentation.component.MarkdownContent
import com.aharou.feature.agent.presentation.component.MarkdownRenderCache
import com.aharou.feature.settings.presentation.SkillUiEntry
import com.aharou.feature.settings.presentation.SkillBindingSaveState
import com.aharou.feature.settings.presentation.SubAgentUiEntry
import com.aharou.feature.settings.presentation.SubAgentSaveState

/**
 * 技能详情页：分组卡片——「是否启用」开关行、「摘要」描述卡、「正文」指令卡（Markdown 渲染）。
 * 卡片左上小标题与设置主页分组一致。
 *
 * 「是否启用」那张卡片里还带一行存放位置（全局 / 当前项目）与搬运入口；内置技能随 App 打包，
 * 既不能停用也不能搬，那一整张卡不对内置显示。
 */
@Composable
internal fun SkillDetailSection(
    entry: SkillUiEntry,
    onToggle: (Boolean) -> Unit,
    onMove: (SkillScope) -> Unit,
    onExport: () -> Unit,
    exporting: Boolean,
    cache: MarkdownRenderCache? = null,
    members: List<SubAgentUiEntry> = emptyList(),
    bindingSaveState: SkillBindingSaveState = SkillBindingSaveState(),
    onSetMemberBinding: (SubAgentUiEntry, Boolean) -> Unit,
    onOpenMember: (SubAgentUiEntry) -> Unit
) {
    val bindingState = bindingSaveState.state.takeIf {
        it is SubAgentSaveState.Saving || bindingSaveState.skillName == entry.name
    } ?: SubAgentSaveState.Idle
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        // 卡片 1：是否启用（开关行）；内置技能随 App 打包，不可停用，不显示开关
        if (!entry.builtin) {
            SettingsGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.skills_enable),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    AppSwitch(
                        checked = !entry.disabled,
                        onCheckedChange = onToggle
                    )
                }
                SettingsDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.skills_scope_label),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(
                                if (entry.scope == SkillScope.GLOBAL) R.string.skills_scope_global
                                else R.string.skills_scope_project
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.semanticColors.subtleText
                        )
                    }
                    TextButton(
                        onClick = {
                            onMove(
                                if (entry.scope == SkillScope.GLOBAL) SkillScope.PROJECT
                                else SkillScope.GLOBAL
                            )
                        }
                    ) {
                        Text(
                            stringResource(
                                if (entry.scope == SkillScope.GLOBAL) R.string.skills_move_to_project
                                else R.string.skills_move_to_global
                            )
                        )
                    }
                }
            }
        }

        if (!entry.builtin) {
            SettingsGroupHeader(text = stringResource(R.string.skills_bound_members))
            SettingsGroup {
                Text(
                    text = stringResource(R.string.skills_bound_members_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.semanticColors.subtleText,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                )
                if (bindingState is SubAgentSaveState.Failed) {
                    Text(
                        text = stringResource(R.string.skills_binding_failed),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                    )
                }
                if (members.isEmpty()) {
                    Text(
                        text = stringResource(R.string.skills_bound_members_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText,
                        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                    )
                } else {
                    members.forEachIndexed { index, member ->
                        if (index > 0) SettingsDivider()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                        ) {
                            Column(
                                modifier = Modifier.weight(1f).clickable(
                                    enabled = bindingState !is SubAgentSaveState.Saving
                                ) { onOpenMember(member) }
                            ) {
                                Text(
                                    text = member.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = member.description.ifBlank { stringResource(R.string.mcp_no_description) },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.semanticColors.subtleText,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                            AppSwitch(
                                checked = member.skills.any { it.equals(entry.name, ignoreCase = true) },
                                onCheckedChange = { onSetMemberBinding(member, it) },
                                enabled = bindingState !is SubAgentSaveState.Saving
                            )
                        }
                    }
                    SettingsDivider()
                    Text(
                        text = stringResource(R.string.skills_keyword_inject_guide),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.semanticColors.subtleText,
                        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                    )
                }
            }
        }

        // 卡片 2：导出（内置技能也能导：正文从 assets 现攒一份 SKILL.md 打进包里）
        SettingsGroup {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !exporting) { onExport() }
                    .padding(horizontal = Spacing.lg, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.skills_export),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.skills_export_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
                if (exporting) {
                    Text(
                        text = stringResource(R.string.skills_export_running),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            }
        }

        // 卡片 3：摘要
        SettingsGroupHeader(text = stringResource(R.string.skills_summary))
        SettingsGroup {
            Text(
                text = entry.description.ifBlank { stringResource(R.string.mcp_no_description) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 12.dp)
            )
        }

        // 卡片 4：正文（Markdown 渲染）
        SettingsGroupHeader(text = stringResource(R.string.skills_instructions))
        SettingsGroup {
            MarkdownContent(
                text = entry.instructions.ifBlank { stringResource(R.string.mcp_no_description) },
                color = MaterialTheme.colorScheme.onSurface,
                cache = cache,
                lazyScroll = true,
                modifier = Modifier
                    .fillMaxWidth()
                    // 正文卡限高：长文本在卡内懒加载滚动（外层整页仍可继续滚），避免超大 md 全量渲染卡顿。
                    .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.62f)
                    .padding(horizontal = Spacing.lg, vertical = 12.dp)
            )
        }
    }
}
