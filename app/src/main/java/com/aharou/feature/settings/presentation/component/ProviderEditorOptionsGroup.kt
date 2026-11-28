package com.aharou.feature.settings.presentation.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aharou.R

@Composable
internal fun ProviderEditorOptionsGroup(
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    SettingsGroupHeader(
        text = stringResource(R.string.provider_section_options),
        expanded = expanded,
        onToggle = onToggle
    )
    SettingsGroup(visible = expanded, content = content)
}
