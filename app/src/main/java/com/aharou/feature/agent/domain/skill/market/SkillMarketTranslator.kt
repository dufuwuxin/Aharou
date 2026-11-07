package com.aharou.feature.agent.domain.skill.market

import com.aharou.core.translation.OfflineTranslation
import com.aharou.core.translation.TranslationModelState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class SkillMarketTranslator @Inject constructor(
    private val translation: OfflineTranslation,
    private val store: SkillTranslationStore,
) {
    fun cached(): Map<String, SkillTranslation> = store.all()

    suspend fun translate(
        skills: List<MarketSkill>,
        onBatch: suspend (Map<String, SkillTranslation>) -> Unit,
    ) = withContext(Dispatchers.Default) {
        if (!translation.enabled.value) return@withContext
        if (translation.modelState.value != TranslationModelState.READY) {
            translation.prepareModel()
            return@withContext
        }
        val done = store.all()
        val todo = skills.filter { it.translationKey() !in done && it.needsTranslation() }
        for (chunk in todo.chunked(20)) {
            val batch = mutableMapOf<String, SkillTranslation>()
            for (skill in chunk) {
                val sourceName = skill.displayName.ifBlank { skill.name }
                val name = translation.translate(sourceName)
                val description = translation.translate(skill.description)
                if (name != sourceName || description != skill.description) {
                    batch[skill.translationKey()] = SkillTranslation(name, description)
                }
            }
            if (batch.isNotEmpty()) {
                store.putAll(batch)
                onBatch(batch)
            }
        }
    }
}
