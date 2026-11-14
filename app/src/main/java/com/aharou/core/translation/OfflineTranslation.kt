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
    @ApplicationContext context: Context,
    private val models: TranslationModelFiles,
    private val engine: TranslationWebEngine,
) {
    private val preferences = context.getSharedPreferences("offline_translation", Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(preferences.getBoolean("enabled", true))
    val enabled = _enabled.asStateFlow()
    private val states = mapOf("zh" to MutableStateFlow(TranslationModelState.CHECKING),
        "en" to MutableStateFlow(TranslationModelState.CHECKING))
    val modelState = states.getValue("zh").asStateFlow()
    val reverseModelState = states.getValue("en").asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val modelLock = Mutex()
    private val translationLock = Mutex()
    private val cache = LinkedHashMap<String, String>(128, 0.75f, true)
    private var cacheChars = 0
    private val automaticDownloadAttempted = mutableSetOf<String>()

    init {
        scope.launch {
            modelLock.withLock {
                for ((target, state) in states) {
                    state.value = try {
                        if (models.isInstalled(target)) TranslationModelState.READY else TranslationModelState.MISSING
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        TranslationModelState.FAILED
                    }
                }
            }
        }
    }

    fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean("enabled", value).apply()
        _enabled.value = value
    }

    fun prepareModel(retry: Boolean = false, target: String = "zh") {
        val state = states.getValue(target)
        scope.launch {
            modelLock.withLock {
                if (state.value == TranslationModelState.READY) return@withLock
                if (target in automaticDownloadAttempted && !retry) return@withLock
                automaticDownloadAttempted += target
                state.value = TranslationModelState.DOWNLOADING
                state.value = try {
                    models.install(target)
                    translationLock.withLock { engine.prepare(target) }
                    TranslationModelState.READY
                } catch (e: CancellationException) {
                    state.value = TranslationModelState.FAILED
                    throw e
                } catch (failure: Exception) {
                    com.aharou.core.util.FileLogger.e("OfflineTranslation", "Model preparation failed: $target", failure)
                    TranslationModelState.FAILED
                }
            }
        }
    }

    suspend fun translateRequired(text: String, target: String): String {
        if (isStructuredTranslationPayload(text) || TranslationDocument.parts(text, target).none { it.translate }) return text
        check(states.getValue(target).value == TranslationModelState.READY) {
            "Offline $target translation package is not ready; open Settings / Offline translation and download the selected direction, or choose native provider language."
        }
        val result = translate(text, target)
        check(enabled.value && states.getValue(target).value == TranslationModelState.READY) { "Offline translation settings changed or inference failed; original request was not sent" }
        return result
    }

    suspend fun translate(text: String, target: String = "zh"): String {
        val state = states.getValue(target)
        if (!enabled.value || isStructuredTranslationPayload(text)) return text
        val parts = TranslationDocument.parts(text, target)
        if (parts.none { it.translate } || state.value != TranslationModelState.READY) return text
        return translationLock.withLock {
            try {
                buildString {
                    for (part in parts) {
                        if (!enabled.value) return@withLock text
                        if (!part.translate) append(part.text) else {
                            val key = "$target\u0000${part.text}"
                            val translated = cache[key] ?: engine.translate(part.text, target)
                                .takeIf { it.isNotBlank() }?.also { cache(key, it) }
                                ?: error("Empty local translation")
                            append(translated)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (failure: Exception) {
                com.aharou.core.util.FileLogger.e("OfflineTranslation", "Local inference failed: $target", failure)
                state.value = TranslationModelState.FAILED
                text
            }
        }
    }

    private fun cache(source: String, translated: String) {
        cache.put(source, translated)?.let { cacheChars -= source.length + it.length }
        cacheChars += source.length + translated.length
        while (cache.size > 256 || cacheChars > 256_000) {
            val oldest = cache.entries.iterator().next()
            cacheChars -= oldest.key.length + oldest.value.length
            cache.remove(oldest.key)
        }
    }
}
