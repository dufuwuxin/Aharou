package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.aharou.core.ui.AppTextField
import compose.icons.FeatherIcons
import compose.icons.feathericons.X

@Composable
internal fun SkillKeywordFields(
    values: List<String>,
    onValuesChange: (List<String>) -> Unit,
    enabled: Boolean = true
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        values.forEachIndexed { index, value ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                AppTextField(
                    value = value,
                    onValueChange = { text ->
                        onValuesChange(values.toMutableList().apply { set(index, text) })
                    },
                    label = stringResource(R.string.skills_keyword_item, index + 1),
                    modifier = Modifier.weight(1f),
                    enabled = enabled
                )
                IconButton(
                    enabled = enabled && values.size > 1,
                    onClick = { onValuesChange(values.toMutableList().apply { removeAt(index) }) }
                ) {
                    Icon(
                        FeatherIcons.X,
                        contentDescription = stringResource(R.string.skills_keyword_remove, index + 1)
                    )
                }
            }
        }
        TextButton(enabled = enabled, onClick = { onValuesChange(values + "") }) {
            Text(stringResource(R.string.skills_keyword_add))
        }
    }
}
