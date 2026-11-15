package com.aharou.feature.settings.presentation.component

import androidx.compose.material3.*
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.translation.TranslationModelState
import com.aharou.core.translation.translationService
import com.aharou.core.ui.AppSwitch

@Composable
internal fun OfflineTranslationSettings() {
    val translation = translationService()
    val enabled by translation.enabled.collectAsStateWithLifecycle()
    val state by translation.modelState.collectAsStateWithLifecycle()
    val reverse by translation.reverseModelState.collectAsStateWithLifecycle()
    var details by rememberSaveable { mutableStateOf(false) }
    val summary = when {
        state == TranslationModelState.FAILED || reverse == TranslationModelState.FAILED -> R.string.translation_failed
        state == TranslationModelState.DOWNLOADING || reverse == TranslationModelState.DOWNLOADING -> R.string.translation_downloading
        state == TranslationModelState.CHECKING || reverse == TranslationModelState.CHECKING -> R.string.translation_checking
        state == TranslationModelState.READY && reverse == TranslationModelState.READY -> R.string.translation_ready
        else -> R.string.translation_missing
    }
    SettingsGroup {
        SettingsRow(
            title = stringResource(R.string.translation_bilingual_title),
            subtitle = stringResource(summary),
            modifier = Modifier.testTag("translation_settings_entry"),
            onClick = { details = true },
            trailing = { AppSwitch(checked = enabled, onCheckedChange = translation::setEnabled) },
        )
    }
    if (details) AlertDialog(onDismissRequest = { details = false },
        title = { Text(stringResource(R.string.translation_title)) },
        confirmButton = { TextButton(onClick = { details = false }) { Text(stringResource(R.string.common_close)) } },
        text = { Column {
        Text(stringResource(R.string.translation_description))
        SettingsRow(
            title = stringResource(R.string.translation_model),
            subtitle = stringResource(when (state) {
                TranslationModelState.CHECKING -> R.string.translation_checking
                TranslationModelState.MISSING -> R.string.translation_missing
                TranslationModelState.DOWNLOADING -> R.string.translation_downloading
                TranslationModelState.READY -> R.string.translation_ready
                TranslationModelState.FAILED -> R.string.translation_failed
            }),
            enabled = state != TranslationModelState.DOWNLOADING && state != TranslationModelState.CHECKING,
            onClick = if (state == TranslationModelState.READY) null else {
                { translation.prepareModel(retry = true) }
            },
        )
        Text(stringResource(R.string.translation_attribution))
        SettingsDivider()
        SettingsRow(
            title = stringResource(R.string.translation_reverse_model),
            subtitle = stringResource(when (reverse) {
                TranslationModelState.CHECKING -> R.string.translation_checking
                TranslationModelState.MISSING -> R.string.translation_missing
                TranslationModelState.DOWNLOADING -> R.string.translation_downloading
                TranslationModelState.READY -> R.string.translation_ready
                TranslationModelState.FAILED -> R.string.translation_failed
            }),
            onClick = if (reverse == TranslationModelState.MISSING || reverse == TranslationModelState.FAILED) {
                { translation.prepareModel(retry = true, target = "en") }
            } else null,
        )

        } })
}
