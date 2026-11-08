package com.aharou.core.translation

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class TranslationModelState { CHECKING, MISSING, DOWNLOADING, READY, FAILED }

@Singleton
class OfflineTranslation @Inject constructor(
    @param:ApplicationContext context: Context,
    private val models: TranslationModelFiles,
    private val engine: TranslationWebEngine,
) {
    private val preferences = context.getSharedPreferences("offline_translation", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(preferences.getBoolean("enabled", true))
    val enabled = _enabled.asStateFlow()
    private val _modelState = MutableStateFlow(TranslationModelState.CHECKING)
    val modelState = _modelState.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val modelLock = Mutex()
    private val translationLock = Mutex()
    private val cache = LinkedHashMap<String, String>(128, 0.75f, true)
    private var cacheChars = 0
    private var automaticDownloadAttempted = false

    init {
        scope.launch {
            modelLock.withLock {
                _modelState.value = try {
                    if (models.isInstalled()) {
                        engine.prepare()
                        TranslationModelState.READY
                    } else {
                        TranslationModelState.MISSING
                    }
                } catch (e: CancellationException) {
                    _modelState.value = TranslationModelState.FAILED
                    throw e
                } catch (failure: Exception) {
                    com.aharou.core.util.FileLogger.e("OfflineTranslation", "Model preparation failed", failure)
                    TranslationModelState.FAILED
                }
            }
        }
    }

    fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean("enabled", value).apply()
        _enabled.value = value
    }

    fun prepareModel(retry: Boolean = false) {
        scope.launch {
            modelLock.withLock {
                if (_modelState.value == TranslationModelState.READY) return@withLock
                if (automaticDownloadAttempted && !retry) return@withLock
                automaticDownloadAttempted = true
                _modelState.value = TranslationModelState.DOWNLOADING
                _modelState.value = try {
                    models.install()
                    engine.prepare()
                    TranslationModelState.READY
                } catch (e: CancellationException) {
                    _modelState.value = TranslationModelState.FAILED
                    throw e
                } catch (failure: Exception) {
                    com.aharou.core.util.FileLogger.e("OfflineTranslation", "Model preparation failed", failure)
                    TranslationModelState.FAILED
                }
            }
        }
    }

    suspend fun translate(text: String): String = translationOrOriginal(text) { translateReady(text) }

    private suspend fun translateReady(text: String): String {
        if (!enabled.value) return text
        val parts = TranslationDocument.parts(text)
        if (parts.none { it.translate }) return text
        if (modelState.value != TranslationModelState.READY) return text
        return translationLock.withLock {
            try {
                buildString {
                    for (part in parts) {
                        if (!enabled.value) return@withLock text
                        if (!part.translate) {
                            append(part.text)
                        } else {
                            val translated = cache[part.text] ?: engine.translate(part.text)
                                .takeIf { it.isNotBlank() }?.also { cache(part.text, it) } ?: part.text
                            append(translated)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (failure: Exception) {
                com.aharou.core.util.FileLogger.e("OfflineTranslation", "Local inference failed", failure)
                _modelState.value = TranslationModelState.FAILED
                text
            }
        }
    }

    private fun cache(source: String, translated: String) {
        cache[source] = translated
        cacheChars += source.length + translated.length
        while (cache.size > 256 || cacheChars > 256_000) {
            val oldest = cache.entries.iterator().next()
            cacheChars -= oldest.key.length + oldest.value.length
            cache.remove(oldest.key)
        }
    }
}
