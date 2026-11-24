package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    var showDialog by rememberSaveable(providerId) { mutableStateOf(false) }
    var input by rememberSaveable(providerId) { mutableStateOf((limit.takeIf { it > 0 } ?: 4).toString()) }
    var invalid by remember(providerId) { mutableStateOf(false) }
    fun openDialog() {
        input = (limit.takeIf { it > 0 } ?: 4).toString()
        invalid = false
        showDialog = true
    }
    SettingsDivider()
    SettingsRow(
        modifier = Modifier.testTag("provider_concurrency_row"),
        title = stringResource(R.string.provider_concurrency_title),
        onClick = ::openDialog,
        subtitle = stringResource(R.string.provider_concurrency_subtitle),
        trailing = {
            AppSwitch(checked = limit > 0, modifier = Modifier.testTag("provider_concurrency_toggle"), onCheckedChange = { enabled ->
                if (enabled) openDialog() else onChange(0)
            })
        },
    )
    if (!showDialog) return
    val parsed = parseProviderConcurrencyLimit(input)
    AlertDialog(
        onDismissRequest = { showDialog = false },
        title = { Text(stringResource(R.string.provider_concurrency_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(stringResource(R.string.provider_concurrency_dialog_hint, PROVIDER_MAX_CONCURRENT_REQUESTS))
                AppTextField(
                    value = input,
                    onValueChange = { input = it; invalid = false },
                    label = stringResource(R.string.provider_concurrency_dialog_value),
                    singleLine = true,
                    isError = invalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                if (invalid) Text(
                    stringResource(R.string.provider_concurrency_invalid, PROVIDER_MAX_CONCURRENT_REQUESTS),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (parsed == null || parsed !in 1..PROVIDER_MAX_CONCURRENT_REQUESTS) invalid = true
                else {
                    onChange(parsed)
                    showDialog = false
                }
            }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = { showDialog = false }) { Text(stringResource(R.string.common_cancel)) } },
    )
}
