package com.aharou.feature.agent.domain.prompt

import android.content.Context
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.agent.domain.context.ContextLibraryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Legacy method names remain API-compatible; the native library is the only body source. */
@Singleton
@Suppress("unused")
class PromptFileResolver @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val containerInstaller: ContainerInstaller,
    private val contextLibrary: ContextLibraryRepository? = null,
) {
    fun resolve(name: String): String = contextLibrary?.resolvePrompt(name).orEmpty()

    fun isDisabled(name: String): Boolean = resolve(name).isBlank()

    /** Raw no longer bypasses a user's native OFF setting. */
    fun resolveRaw(name: String): String = resolve(name)
}
