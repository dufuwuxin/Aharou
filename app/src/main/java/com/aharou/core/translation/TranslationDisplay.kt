package com.aharou.core.translation

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface TranslationEntryPoint {
    fun translation(): OfflineTranslation
}

@Composable
internal fun translationService(): OfflineTranslation {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        EntryPointAccessors.fromApplication(context, TranslationEntryPoint::class.java).translation()
    }
}

@Composable
internal fun rememberTranslatedText(text: String, enabled: Boolean = true): String {
    val translation = translationService()
    val automatic by translation.enabled.collectAsStateWithLifecycle()
    val modelState by translation.modelState.collectAsStateWithLifecycle()
    var displayed by remember(text, enabled, automatic, modelState) { mutableStateOf(text) }
    LaunchedEffect(text, enabled, automatic, modelState) {
        if (enabled && automatic) {
            delay(450)
            displayed = withContext(Dispatchers.Default) { translation.translate(text) }
        }
    }
    return displayed
}

@Composable
internal fun TranslationPreview(text: String) {
    val translated = rememberTranslatedText(text)
    if (translated != text) {
        Column {
            Text(
                text = stringResource(R.string.translation_preview),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = translated, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
