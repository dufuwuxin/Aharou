package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppSwitch
import com.aharou.core.ui.AppTextField
import compose.icons.FeatherIcons
import compose.icons.feathericons.X

@Composable
internal fun PromptInjectionFields(
    defaultInjection: Boolean,
    onDefaultInjectionChange: (Boolean) -> Unit,
    keywordsEnabled: Boolean,
    onKeywordsEnabledChange: (Boolean) -> Unit,
    keywords: List<String>,
    onKeywordsChange: (List<String>) -> Unit,
    membersEnabled: Boolean,
    onMembersEnabledChange: (Boolean) -> Unit,
    memberNames: List<String>,
    selectedMembers: List<String>,
    onMembersChange: (List<String>) -> Unit
) {
    SettingsGroup {
        SettingsRow(
            title = stringResource(R.string.prompts_injection_default),
            subtitle = stringResource(R.string.prompts_injection_default_hint),
            trailing = { AppSwitch(checked = defaultInjection, onCheckedChange = onDefaultInjectionChange) }
        )
        SettingsDivider()
        SettingsRow(
            title = stringResource(R.string.prompts_injection_keywords),
            subtitle = stringResource(R.string.prompts_injection_keywords_hint),
            trailing = { AppSwitch(checked = keywordsEnabled, onCheckedChange = onKeywordsEnabledChange) }
        )
        if (keywordsEnabled) Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            keywords.forEachIndexed { index, keyword ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppTextField(
                        value = keyword,
                        onValueChange = { value -> onKeywordsChange(keywords.mapIndexed { i, old -> if (i == index) value else old }) },
                        label = stringResource(R.string.prompts_injection_keyword, index + 1),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { onKeywordsChange(keywords.filterIndexed { i, _ -> i != index }) }) {
                        Icon(FeatherIcons.X, stringResource(R.string.skills_keyword_remove, index + 1))
                    }
                }
            }
            TextButton(onClick = { onKeywordsChange(keywords + "") }) {
                Text(stringResource(R.string.skills_keyword_add))
            }
        }
    }
    SettingsGroup {
        SettingsRow(
            title = stringResource(R.string.prompts_injection_members),
            subtitle = stringResource(R.string.prompts_injection_members_hint),
            trailing = { AppSwitch(checked = membersEnabled, onCheckedChange = onMembersEnabledChange) }
        )
        if (membersEnabled) memberNames.forEach { name ->
            SettingsDivider()
            SettingsRow(
                title = name,
                trailing = {
                    AppSwitch(checked = name in selectedMembers, onCheckedChange = { checked ->
                        onMembersChange(if (checked) selectedMembers + name else selectedMembers - name)
                    })
                }
            )
        }
    }
}
