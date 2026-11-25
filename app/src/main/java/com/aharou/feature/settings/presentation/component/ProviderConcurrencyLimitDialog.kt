package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppSwitch
import com.aharou.core.ui.AppTextField
import com.aharou.feature.settings.domain.model.PROVIDER_MAX_CONCURRENT_REQUESTS

internal fun parseProviderConcurrencyLimit(input: String): Int? =
    input.trim().toIntOrNull()?.takeIf { it in 1..PROVIDER_MAX_CONCURRENT_REQUESTS }

@Composable
internal fun ProviderConcurrencyLimitSection(
    providerId: String,
    limit: Int,
    onChange: (Int) -> Unit,
) {
    var input by rememberSaveable(providerId) { mutableStateOf((limit.takeIf { it > 0 } ?: 4).toString()) }
    LaunchedEffect(providerId, limit) {
        if (input != limit.takeIf { it > 0 }?.toString()) {
            input = (limit.takeIf { it > 0 } ?: 4).toString()
        }
    }
    val parsed = parseProviderConcurrencyLimit(input)
    val invalid = input.isBlank() || parsed == null

    SettingsDivider()
    SettingsRow(
        modifier = Modifier.testTag("provider_concurrency_row"),
        title = stringResource(R.string.provider_concurrency_title),
        subtitle = stringResource(R.string.provider_concurrency_subtitle),
        trailing = {
            AppSwitch(
                checked = limit > 0,
                modifier = Modifier.testTag("provider_concurrency_toggle"),
                onCheckedChange = { enabled ->
                    if (enabled) onChange(parsed ?: limit.takeIf { it > 0 } ?: 4) else onChange(0)
                },
            )
        },
    )
    if (limit <= 0) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, bottom = Spacing.md),
    ) {
        AppTextField(
            value = input,
            onValueChange = { value ->
                input = value
                parseProviderConcurrencyLimit(value)?.let(onChange)
            },
            modifier = Modifier.testTag("provider_concurrency_input"),
            label = stringResource(R.string.provider_concurrency_value),
            supportingText = { Text(stringResource(
                if (invalid) R.string.provider_concurrency_invalid else R.string.provider_concurrency_limit_range,
                PROVIDER_MAX_CONCURRENT_REQUESTS,
            )) },
            isError = invalid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}
