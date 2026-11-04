package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.core.theme.AIEditorTheme
import com.aharou.feature.agent.data.repository.ChatAppearance
import com.aharou.feature.agent.data.repository.ChatAppearanceRepository
import com.aharou.feature.agent.presentation.component.LocalChatAppearance
import com.aharou.feature.settings.data.repository.AppThemeMode
import com.aharou.feature.settings.data.repository.ThemeSettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** 外观决议结果：明暗与聊天气泡外观。 */
data class AppAppearance(
    val darkTheme: Boolean,
    val chatAppearance: ChatAppearance
)

/**
 * 固定霞橙，按用户选择解析深浅色。
 *
 * 自动模式跟随系统；
 * 手动选浅色或深色时使用霞橙对应的配色。
 */
@Composable
fun rememberAppAppearance(themeSettings: ThemeSettingsRepository): AppAppearance {
    val context = LocalContext.current
    val chatRepository = remember(context) {
        EntryPointAccessors
            .fromApplication(context.applicationContext, ChatAppearanceEntryPoint::class.java)
            .chatAppearanceRepository()
    }
    val themeMode by themeSettings.themeModeFlow
        .collectAsStateWithLifecycle(initialValue = AppThemeMode.AUTO)
    val systemDark = isSystemInDarkTheme()
    val darkTheme = remember(themeMode, systemDark) {
        when (themeMode) {
            AppThemeMode.AUTO -> systemDark
            AppThemeMode.DARK -> true
            AppThemeMode.LIGHT -> false
        }
    }
    val chatAppearance by chatRepository.settingsFlow
        .collectAsStateWithLifecycle(initialValue = ChatAppearance())
    return AppAppearance(
        darkTheme = darkTheme,
        chatAppearance = chatAppearance
    )
}

/** 按外观决议包裹全局主题，并把聊天气泡外观下发给消息区。 */
@Composable
fun AppThemeHost(appearance: AppAppearance, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalChatAppearance provides appearance.chatAppearance) {
        AIEditorTheme(
            darkTheme = appearance.darkTheme,
            content = content
        )
    }
}

/** 外观宿主直读聊天气泡仓库：这条链路不经过行数已满的视图模型。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ChatAppearanceEntryPoint {
    fun chatAppearanceRepository(): ChatAppearanceRepository
}
