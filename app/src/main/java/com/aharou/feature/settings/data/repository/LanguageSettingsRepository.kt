package com.aharou.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.languageDataStore by preferencesDataStore(
    name = "language_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

private const val PREFS_NAME = "language_prefs_sync"
private const val PREFS_KEY = "language_tag"

/**
 * 固定中文；忽略旧配置与备份中的语言值。
 *
 * 对外读取始终返回 zh，恢复旧备份也写入 zh。
 * Application 与 Activity 的初始上下文独立固定中文，不等待异步配置读取。
 */
@Singleton
class LanguageSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val LANGUAGE_KEY = stringPreferencesKey("language_tag")
    }

    private val syncPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val languageFlow: Flow<String?> = context.languageDataStore.data.map {
        "zh"
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun setLanguage(tag: String?) {
        context.languageDataStore.edit {
            it[LANGUAGE_KEY] = "zh"
        }
        syncPrefs.edit().apply {
            putString(PREFS_KEY, "zh")
        }.apply()
    }

    suspend fun snapshot(): String? = languageFlow.first()

    /** 保留既有同步读取接口，旧偏好不再影响返回值。 */
    fun getLanguageSync(): String? = "zh"

    suspend fun restore(value: String?) {
        // 旧备份无论保存什么语言，都归一化成中文。
        setLanguage(value)
    }
}
