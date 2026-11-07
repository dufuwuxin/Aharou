package com.aharou.feature.settings.presentation.component

import androidx.compose.material3.Text
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
    SettingsGroup {
        SettingsRow(
            title = stringResource(R.string.translation_title),
            subtitle = stringResource(R.string.translation_description),
            trailing = { AppSwitch(checked = enabled, onCheckedChange = translation::setEnabled) },
        )
        SettingsDivider()
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
    }
}
