package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.core.ui.AppSwitch
import com.aharou.feature.agent.domain.provider.OpenAIServiceTier
import com.aharou.feature.settings.domain.model.ProviderType

/** Uses the existing custom-header state, so save/restore and child providers share one setting. */
@Composable
internal fun ProviderServiceTierRow(
    type: ProviderType,
    headers: MutableList<Pair<String, String>>,
    useResponseApi: Boolean,
    onResponseApiChange: (Boolean) -> Unit,
) {
    if (type != ProviderType.OPENAI && type != ProviderType.GEMINI) return
    val tier = runCatching { OpenAIServiceTier.value(headers.toMap()) }.getOrNull()
    val ultra = type == ProviderType.OPENAI && tier == "ultrafast"
    fun select(value: String?) {
        val updated = OpenAIServiceTier.select(headers.toMap(), value)
        headers.clear()
        headers.addAll(updated.toList())
        if (value == "ultrafast") onResponseApiChange(true)
    }
    SettingsDivider()
    SettingsRow(
        title = stringResource(if (type == ProviderType.GEMINI) R.string.provider_interactions_api else R.string.provider_response_api),
        trailing = {
            AppSwitch(checked = useResponseApi || ultra, onCheckedChange = {
                if (!it && ultra) select(null)
                onResponseApiChange(it)
            })
        }
    )
    if (type != ProviderType.OPENAI) return
    var choosing by remember { mutableStateOf(false) }
    val options = listOf(
        null to stringResource(R.string.provider_service_tier_default),
        "fast" to stringResource(R.string.provider_service_tier_fast),
        "ultrafast" to stringResource(R.string.provider_service_tier_ultrafast),
    )
    SettingsDivider()
    SettingsRow(
        title = stringResource(R.string.provider_service_tier_title),
        subtitle = options.firstOrNull { it.first == tier }?.second
            ?: stringResource(R.string.provider_service_tier_custom),
        onClick = { choosing = true }
    )
    if (choosing) AlertDialog(
        onDismissRequest = { choosing = false },
        title = { Text(stringResource(R.string.provider_service_tier_title)) },
        text = {
            Column {
                Text(stringResource(R.string.provider_service_tier_hint))
                options.forEach { (value, label) ->
                    SettingsRow(
                        title = label,
                        onClick = { select(value); choosing = false },
                        trailing = {
                            RadioButton(selected = tier == value, onClick = { select(value); choosing = false })
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.provider_service_tier_close)) }
        }
    )
}
