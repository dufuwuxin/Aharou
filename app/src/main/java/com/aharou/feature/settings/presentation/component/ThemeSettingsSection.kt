package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.feature.agent.data.repository.ChatAppearance
import com.aharou.feature.agent.data.repository.ChatAppearanceRepository
import com.aharou.feature.settings.data.repository.AppThemeMode
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch

/**
 * 外观主题页：固定霞橙，保留明暗模式、聊天外观与背景图。
 */
@Composable
internal fun ThemeSettingsSection(
    selected: AppThemeMode,
    backgroundImagePath: String?,
    backgroundAlpha: Float,
    onSelected: (AppThemeMode) -> Unit,
    onPickBackgroundImage: (android.net.Uri) -> Unit,
    onBackgroundAlphaChange: (Float) -> Unit,
    onRemoveBackgroundImage: () -> Unit
) {
    val context = LocalContext.current
    val entryPoint = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, ThemeSkinEntryPoint::class.java)
    }
    val chatRepository = entryPoint.chatAppearanceRepository()
    val chatAppearance by chatRepository.settingsFlow
        .collectAsStateWithLifecycle(initialValue = ChatAppearance())
    val scope = rememberCoroutineScope()
    var chatSheetVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = Spacing.sm)
            .padding(bottom = Spacing.xl)
    ) {
        SectionLabel(stringResource(R.string.theme_mode_section))

        AppThemeMode.entries.forEach { mode ->
            val isSelected = mode == selected
            Surface(
                onClick = { onSelected(mode) },
                color = Color.Transparent
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(mode.labelRes),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f)
                    )
                    if (isSelected) {
                        Icon(
                            imageVector = FeatherIcons.Check,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(Spacing.sm))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Spacer(Modifier.height(Spacing.md))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { chatSheetVisible = true }
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.chat_appearance_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (chatAppearance.userBubbleFollowsSkin && !chatAppearance.assistantBubbleEnabled) {
                    stringResource(R.string.chat_appearance_follow_skin)
                } else {
                    stringResource(R.string.skin_custom)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(Spacing.sm))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Spacer(Modifier.height(Spacing.md))
        SectionLabel(stringResource(R.string.settings_background_image))
        BackgroundImageControls(
            imagePath = backgroundImagePath,
            alpha = backgroundAlpha,
            onPickImage = onPickBackgroundImage,
            onAlphaChange = onBackgroundAlphaChange,
            onRemove = onRemoveBackgroundImage
        )

        if (chatSheetVisible) {
            ChatAppearanceSheet(
                current = chatAppearance,
                onDismiss = { chatSheetVisible = false },
                onSave = { next ->
                    scope.launch { chatRepository.update { next } }
                    chatSheetVisible = false
                }
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.sm)
    )
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ThemeSkinEntryPoint {
    fun chatAppearanceRepository(): ChatAppearanceRepository
}
