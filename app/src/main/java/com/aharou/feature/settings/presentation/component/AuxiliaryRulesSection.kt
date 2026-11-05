package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppSwitch
import com.aharou.feature.agent.domain.prompt.PromptAuxFragment
import com.aharou.feature.settings.presentation.PromptsViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.FileText

/** 运行时规则只迁移编辑入口；文件位置、开关与覆盖仍由提示词目录管理。 */
@Composable
internal fun AuxiliaryRulesSection(viewModel: PromptsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<PromptAuxFragment?>(null) }
    if (state.auxFragments.isEmpty()) return

    Spacer(Modifier.height(Spacing.lg))
    SettingsGroupHeader(text = stringResource(R.string.rules_runtime_title))
    SettingsGroup(modifier = Modifier.padding(horizontal = Spacing.lg)) {
        state.auxFragments.forEachIndexed { index, fragment ->
            if (index > 0) SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.FileText,
                title = fragment.title,
                subtitle = auxiliaryRuleTrigger(fragment.name),
                onClick = { editing = fragment },
                trailing = {
                    AppSwitch(
                        checked = fragment.enabled,
                        onCheckedChange = { viewModel.setAuxEnabled(fragment.name, it) }
                    )
                }
            )
        }
    }

    editing?.let { fragment ->
        TextContentEditorSheet(
            key = fragment.name,
            title = fragment.title,
            hint = stringResource(R.string.prompts_aux_editor_hint),
            initialContent = fragment.content,
            onDismiss = { editing = null },
            onSave = { content ->
                viewModel.saveAuxFragment(fragment.name, content)
                editing = null
            }
        )
    }
}

@Composable
private fun auxiliaryRuleTrigger(name: String): String = stringResource(
    when (name.substringAfterLast('/').removeSuffix(".md")) {
        "plan-mode" -> R.string.rules_runtime_trigger_plan
        "auto-mode" -> R.string.rules_runtime_trigger_auto
        "subagent-base" -> R.string.rules_runtime_trigger_subagent
        "memory-discipline" -> R.string.rules_runtime_trigger_memory
        "stuck-nudge" -> R.string.rules_runtime_trigger_stuck
        "workspace-android", "workspace-rust", "workspace-python" -> R.string.rules_runtime_trigger_workspace
        else -> R.string.rules_runtime_trigger_unknown
    }
)
