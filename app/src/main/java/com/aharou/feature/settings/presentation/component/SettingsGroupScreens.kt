package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.feature.onboarding.domain.OnboardingStep
import com.aharou.feature.onboarding.presentation.onboardingTarget
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.AppSwitch
import com.aharou.feature.settings.data.repository.AppThemeMode
import com.aharou.feature.settings.presentation.RulesUiState
import com.aharou.feature.settings.presentation.RulesViewModel
import com.aharou.feature.settings.presentation.TerminalCleanupSettingsViewModel
import com.aharou.feature.terminal.data.repository.TerminalSettings
import compose.icons.FeatherIcons
import compose.icons.feathericons.BarChart2
import compose.icons.feathericons.BookOpen
import compose.icons.feathericons.Clock
import compose.icons.feathericons.Eye
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Globe
import compose.icons.feathericons.HardDrive
import compose.icons.feathericons.Info
import compose.icons.feathericons.Key
import compose.icons.feathericons.Lock
import compose.icons.feathericons.Monitor
import compose.icons.feathericons.Tool
import compose.icons.feathericons.Moon
import compose.icons.feathericons.PieChart
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Save
import compose.icons.feathericons.Server
import compose.icons.feathericons.Smartphone
import compose.icons.feathericons.Shield
import compose.icons.feathericons.Sliders
import compose.icons.feathericons.Smile
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Zap
import compose.icons.feathericons.Users

/**
 * 设置首页六个分组各自的内容页。
 *
 * 首页按配置、助手、通用、权限与后台、更多分组，
 * 点进去才是组里的各项。每一页都是「一个分组标题 + 一张卡片」的形态。
 */

@Composable
internal fun SettingsAharouRows(onOpen: (SettingsSection) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Clock,
                title = stringResource(SettingsSection.SpecialPermissions.titleRes),
                onClick = { onOpen(SettingsSection.SpecialPermissions) }
            )

        }
    }}

/** 规则入口的摘要：没配显示「未配置」，否则给出各作用域的字数或片段数（对齐简云的做法）。 */
@Composable
private fun rulesSummary(state: RulesUiState): String {
    val parts = ArrayList<String>()
    state.global?.let { doc ->
        val chars = doc.content.trim().length
        when {
            chars > 0 -> parts.add(stringResource(R.string.rules_summary_global_chars, chars))
            doc.fragments.isNotEmpty() ->
                parts.add(stringResource(R.string.rules_summary_global_fragments, doc.fragments.size))
        }
    }
    state.project?.let { doc ->
        val chars = doc.content.trim().length
        when {
            chars > 0 -> parts.add(stringResource(R.string.rules_summary_project_chars, chars))
            doc.fragments.isNotEmpty() ->
                parts.add(stringResource(R.string.rules_summary_project_fragments, doc.fragments.size))
        }
    }
    if (parts.isEmpty()) return stringResource(R.string.rules_summary_none)
    return parts.joinToString(" · ")
}

@Composable
internal fun SettingsAiGroupScreen(onOpen: (SettingsSection) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        OfflineTranslationSettings()
        SettingsGroup {
            SettingsRow(icon = FeatherIcons.Server, title = stringResource(SettingsSection.Providers.titleRes), onClick = { onOpen(SettingsSection.Providers) }, modifier = Modifier.onboardingTarget(OnboardingStep.CONFIG_PROVIDER))
            SettingsDivider()
            SettingsRow(icon = FeatherIcons.Zap, title = stringResource(SettingsSection.DefaultModels.titleRes), onClick = { onOpen(SettingsSection.DefaultModels) })
            SettingsDivider()
            SettingsRow(icon = FeatherIcons.Tool, title = stringResource(SettingsSection.Plugins.titleRes), onClick = { onOpen(SettingsSection.Plugins) })
            SettingsDivider()
            SettingsRow(icon = FeatherIcons.Globe, title = stringResource(SettingsSection.Connectors.titleRes), onClick = { onOpen(SettingsSection.Connectors) })
            SettingsDivider()
            SettingsRow(icon = FeatherIcons.Lock, title = stringResource(SettingsSection.Vault.titleRes), onClick = { onOpen(SettingsSection.Vault) })

        }
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Smile,
                title = stringResource(R.string.memory_settings_title),
                subtitle = stringResource(R.string.context_navigation_summary),
                onClick = { onOpen(SettingsSection.Memory) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Zap,
                title = stringResource(SettingsSection.Skills.titleRes),
                onClick = { onOpen(SettingsSection.Skills) }
            )
            SettingsDivider()
            SettingsRow(icon = FeatherIcons.Users, title = stringResource(SettingsSection.SubAgents.titleRes),
                onClick = { onOpen(SettingsSection.SubAgents) })
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Users,
                title = stringResource(SettingsSection.Teams.titleRes),
                onClick = { onOpen(SettingsSection.Teams) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Tool,
                title = stringResource(SettingsSection.Tools.titleRes),
                onClick = { onOpen(SettingsSection.Tools) }
            )

        }
    }
}

@Composable
internal fun SettingsGeneralRows(
    themeMode: AppThemeMode,
    terminalSettings: TerminalSettings,
    onOpenTheme: () -> Unit,
    onOpenTerminalSettingsSheet: () -> Unit,
    onOpen: (SettingsSection) -> Unit,
    cleanupViewModel: TerminalCleanupSettingsViewModel = hiltViewModel()
) {
    val autoCloseAgentTabs by cleanupViewModel.autoCloseFinishedAgentTabs.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Sliders,
                title = stringResource(SettingsSection.General.titleRes),
                onClick = { onOpen(SettingsSection.General) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Moon,
                title = stringResource(R.string.settings_theme_title),
                onClick = onOpenTheme,
                trailing = {
                    val colorLabel = stringResource(R.string.skin_xiaocheng)
                    Text(
                        text = "${stringResource(themeMode.labelRes)} · $colorLabel",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Terminal,
                title = stringResource(R.string.terminal_settings_title),
                onClick = onOpenTerminalSettingsSheet,
                trailing = {
                    Text(
                        text = stringResource(terminalSettings.theme.nameRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Clock,
                title = stringResource(R.string.settings_auto_close_agent_tabs_title),
                subtitle = stringResource(R.string.settings_auto_close_agent_tabs_subtitle),
                trailing = {
                    AppSwitch(
                        checked = autoCloseAgentTabs,
                        onCheckedChange = { cleanupViewModel.setAutoCloseFinishedAgentTabs(it) }
                    )
                }
            )

        }
    }
}

@Composable
internal fun SettingsPermissionsRows(onOpen: (SettingsSection) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Shield,
                title = stringResource(SettingsSection.AppPermissions.titleRes),
                onClick = { onOpen(SettingsSection.AppPermissions) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Eye,
                title = stringResource(SettingsSection.Accessibility.titleRes),
                onClick = { onOpen(SettingsSection.Accessibility) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.RefreshCw,
                title = stringResource(SettingsSection.BackgroundRun.titleRes),
                onClick = { onOpen(SettingsSection.BackgroundRun) }
            )
        }
    }
}

@Composable
internal fun SettingsMoreRows(onOpen: (SettingsSection) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.HardDrive,
                title = stringResource(SettingsSection.Container.titleRes),
                onClick = { onOpen(SettingsSection.Container) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Globe,
                title = stringResource(SettingsSection.Proxy.titleRes),
                onClick = { onOpen(SettingsSection.Proxy) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.FileText,
                title = stringResource(SettingsSection.Log.titleRes),
                onClick = { onOpen(SettingsSection.Log) }
            )
            SettingsDivider()
            // 团队入口已收进「配置」页（AiGroup），这里不再重复；角色在团队页里管理。
            SettingsRow(
                icon = FeatherIcons.Users,
                title = stringResource(SettingsSection.Experiments.titleRes),
                onClick = { onOpen(SettingsSection.Experiments) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Key,
                title = stringResource(SettingsSection.EnvVars.titleRes),
                onClick = { onOpen(SettingsSection.EnvVars) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.PieChart,
                title = stringResource(SettingsSection.Storage.titleRes),
                onClick = { onOpen(SettingsSection.Storage) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.BarChart2,
                title = stringResource(SettingsSection.TokenStats.titleRes),
                onClick = { onOpen(SettingsSection.TokenStats) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Save,
                title = stringResource(SettingsSection.Backup.titleRes),
                onClick = { onOpen(SettingsSection.Backup) }
            )
        }
    }
}
