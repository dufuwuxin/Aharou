package com.aharou.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.memoryModelDataStore by preferencesDataStore(
    name = "memory_model_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 持久化「记忆整理模型」选择（providerId + model 两字符串）。
 *
 * 记忆整理（distill / AI 优化弹窗）默认跟随当前压缩模型；用户可在此单独指定专用记忆模型。
 * 未配置时（providerId/model 均为空）仍走压缩模型。
 */
@Singleton
class MemoryModelSettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) : ModelSelectionSettingsRepository(
    context.memoryModelDataStore, "memory_provider_id", "memory_model"
) {

    suspend fun setMemoryModel(providerId: String, model: String) = setSelection(providerId, model)
    suspend fun clear() = clearSelection()
    suspend fun getMemoryProviderId(): String = readProviderId()
    suspend fun getMemoryModel(): String = readModel()
}
