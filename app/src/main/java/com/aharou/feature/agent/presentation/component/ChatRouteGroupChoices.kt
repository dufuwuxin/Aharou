package com.aharou.feature.agent.presentation.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ModelRouteGroup
import com.aharou.feature.settings.domain.model.resolveModelRoute
import com.aharou.feature.settings.presentation.ModelRouteGroupViewModel

internal fun resolveChatRoutePreview(
    selection: String?,
    routeGroups: List<ModelRouteGroup>,
    providers: List<AIProviderConfig>,
    readError: Boolean,
): AIProviderConfig? {
    if (readError) return null
    val group = routeGroups.firstOrNull { it.selectionId == selection } ?: return null
    return runCatching {
        resolveModelRoute(group) { providerId -> providers.firstOrNull { it.id == providerId } }
    }.getOrNull()
}

@Composable
internal fun ChatRouteGroupChoices(onSelect: (String, String) -> Unit, onDismiss: () -> Unit, enabled: Boolean = true,
    viewModel: ModelRouteGroupViewModel = hiltViewModel()) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    if (groups.isNotEmpty()) {
        Text(stringResource(R.string.route_groups_title), style = MaterialTheme.typography.titleSmall)
        Column(Modifier.heightIn(max = 144.dp).verticalScroll(rememberScrollState())) {
            groups.forEach { group ->
                TextButton(enabled = enabled, onClick = { onSelect(group.selectionId, group.id); onDismiss() }, modifier = Modifier.testTag("chat_route_group_${group.id}")) {
                    Text(group.name)
                }
            }
        }
    }
}
