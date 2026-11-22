package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.feature.agent.domain.provider.OpenAIServiceTier
import com.aharou.feature.agent.presentation.SettingsViewModel
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ProviderType

internal fun saveOpenAIServiceTier(provider: AIProviderConfig?, tier: String?, settings: SettingsViewModel?) {
    val selected = provider?.takeIf { it.type == ProviderType.OPENAI } ?: return
    settings?.saveProvider(selected.copy(customHeaders = OpenAIServiceTier.select(selected.customHeaders, tier)))
}

@Composable
internal fun openAIServiceTierSummary(provider: AIProviderConfig?): String? {
    if (provider?.type != ProviderType.OPENAI) return null
    return stringResource(when (runCatching { OpenAIServiceTier.value(provider.customHeaders) }.getOrNull()) {
        "fast" -> R.string.provider_service_tier_fast
        "ultrafast" -> R.string.provider_service_tier_ultrafast
        else -> R.string.provider_service_tier_default
    })
}

@Composable
internal fun OpenAIServiceTierSelector(
    provider: AIProviderConfig?,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
) {
    if (provider?.type != ProviderType.OPENAI) return
    var expanded by remember(provider.id) { mutableStateOf(false) }
    val tier = runCatching { OpenAIServiceTier.value(provider.customHeaders) }.getOrNull()
    Column {
        TextButton(enabled = enabled, onClick = { expanded = true }) {
            Text(stringResource(R.string.provider_service_tier_title) + ": " +
                stringResource(when (tier) {
                    "fast" -> R.string.provider_service_tier_fast
                    "ultrafast" -> R.string.provider_service_tier_ultrafast
                    else -> R.string.provider_service_tier_default
                }), maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.provider_service_tier_default)) },
                onClick = { expanded = false; onSelect(null) })
            DropdownMenuItem(text = { Text(stringResource(R.string.provider_service_tier_fast)) },
                onClick = { expanded = false; onSelect("fast") })
            DropdownMenuItem(text = { Text(stringResource(R.string.provider_service_tier_ultrafast)) },
                onClick = { expanded = false; onSelect("ultrafast") })
        }
    }
}
