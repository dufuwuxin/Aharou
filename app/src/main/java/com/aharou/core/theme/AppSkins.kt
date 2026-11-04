package com.aharou.core.theme

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aharou.R

/** 一套皮肤的定义：身份 + 自带明暗 + 长相参数。 */
@Immutable
data class AppSkinDefinition(
    val id: String,
    @param:StringRes val nameRes: Int,
    val brightness: SkinBrightness,
    val tokens: AppSkinTokens
)

/** 固定霞橙配色。 */
object AppSkins {
    /** 霞橙：浅色暖调，电商/社交/活动。 */
    val Xiaocheng = AppSkinDefinition(
        id = "xiaocheng",
        nameRes = R.string.skin_xiaocheng,
        brightness = SkinBrightness.LIGHT,
        tokens = AppSkinTokens(
            primary = Color(0xFFEF7016),
            primaryPressed = Color(0xFFD65F08),
            primarySoft = Color(0xFFFDF0E2),
            primarySoftPressed = Color(0xFFFBE4CC),
            onPrimary = Color(0xFFFFFFFF),
            pageBackground = Color(0xFFF9F5EF),
            card = Color(0xFFFFFDFA),
            cardMuted = Color(0xFFFAF4EC),
            text = Color(0xFF2A2118),
            textSecondary = Color(0xFF948676),
            textDisabled = Color(0xFFBDB1A2),
            border = Color(0xFFEEE3D3),
            divider = Color(0xFFF3EABD),
            success = Color(0xFF16A34A),
            successSoft = Color(0xFFE9F9EF),
            warning = Color(0xFFB45309),
            warningSoft = Color(0xFFFBF0DD),
            danger = Color(0xFFE5484D),
            dangerSoft = Color(0xFFFDEBED),
            shadow = AppShadow(Color(0x0F2A2118), 12.dp, 2.dp),
            shadowHover = AppShadow(Color(0x1F2A2118), 24.dp, 6.dp),
            glow = AppShadow(Color(0x52EF7016), 14.dp, 4.dp),
            ring = Color(0x2EEF7016),
            scrim = Color(0x732A2118),
            gradientStart = Color(0xFFF59E0B),
            gradientEnd = Color(0xFFEF4444),
            radiusScale = 1.5f
        )
    )

    val all: List<AppSkinDefinition> = listOf(Xiaocheng)
    val Default: AppSkinDefinition = Xiaocheng
}
