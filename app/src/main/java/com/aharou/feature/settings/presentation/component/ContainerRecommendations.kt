package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.core.theme.Spacing

@Composable
internal fun ContainerRecommendations() {
    SettingsGroupHeader(text = stringResource(R.string.container_recommendation_title))
    SettingsGroup {
        Text(
            text = stringResource(R.string.container_recommendation_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(Spacing.lg)
        )
    }
}
