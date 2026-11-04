package com.aharou.core.theme

import androidx.annotation.StringRes
import androidx.compose.material3.ColorScheme

/** 一套主题在某个明暗模式下的完整配色。 */
data class AppThemeColors(
    val colorScheme: ColorScheme,
    val semanticColors: AppSemanticColors
)

/**
 * 固定霞橙：由 [AppSkinDefinition] 展开成浅色与深色两版。
 *
 * 皮肤自带明暗（[brightness]），另一侧按需派生——所以用户既能直接用皮肤自带的那一版，
 * 也能用手动明暗开关取另一版。
 */
data class AppThemePreset(
    val id: String,
    @param:StringRes val nameRes: Int,
    val brightness: SkinBrightness,
    val lightSkin: AppSkinTokens,
    val darkSkin: AppSkinTokens,
    val light: AppThemeColors,
    val dark: AppThemeColors
) {
    companion object {
        val DEFAULT: AppThemePreset = fromDefinition(AppSkins.Xiaocheng)

        private fun fromDefinition(definition: AppSkinDefinition): AppThemePreset =
            fromTokens(
                id = definition.id,
                nameRes = definition.nameRes,
                brightness = definition.brightness,
                tokens = definition.tokens
            )

        /** 把「皮肤自带明暗的那一版」展开成浅色与深色两套配色。 */
        private fun fromTokens(
            id: String,
            @StringRes nameRes: Int,
            brightness: SkinBrightness,
            tokens: AppSkinTokens
        ): AppThemePreset {
            val isLightSkin = brightness == SkinBrightness.LIGHT
            val lightSkin = if (isLightSkin) tokens else tokens.oppositeVariant(brightness)
            val darkSkin = if (isLightSkin) tokens.oppositeVariant(brightness) else tokens
            return AppThemePreset(
                id = id,
                nameRes = nameRes,
                brightness = brightness,
                lightSkin = lightSkin,
                darkSkin = darkSkin,
                light = lightSkin.toThemeColors(dark = false),
                dark = darkSkin.toThemeColors(dark = true)
            )
        }
    }
}
