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

/** Persisted identity stays stable when the visual palette changes. */
object AppSkins {
    val Xiaocheng = AppSkinDefinition(
        id = "xiaocheng",
        nameRes = R.string.skin_xiaocheng,
        brightness = SkinBrightness.LIGHT,
        tokens = AppSkinTokens(
            primary = Color(0xFF2563EB),
            primaryPressed = Color(0xFF1D4ED8),
            primarySoft = Color(0xFFEFF6FF),
            primarySoftPressed = Color(0xFFDBEAFE),
            onPrimary = Color(0xFFFFFFFF),
            pageBackground = Color(0xFFF7F8FA),
            card = Color(0xFFFFFFFF),
            cardMuted = Color(0xFFF0F2F5),
            text = Color(0xFF242830),
            textSecondary = Color(0xFF5F6672),
            textDisabled = Color(0xFF949BA5),
            border = Color(0xFFDDE1E7),
            divider = Color(0xFFE8EBEF),
            success = Color(0xFF16A34A),
            successSoft = Color(0xFFE9F9EF),
            warning = Color(0xFFB45309),
            warningSoft = Color(0xFFFBF0DD),
            danger = Color(0xFFE5484D),
            dangerSoft = Color(0xFFFDEBED),
            shadow = AppShadow(Color(0x0A242830), 8.dp, 2.dp),
            shadowHover = AppShadow(Color(0x14242830), 16.dp, 4.dp),
            glow = AppShadow(Color(0x262563EB), 12.dp, 2.dp),
            ring = Color(0x2E2563EB),
            scrim = Color(0x73242830),
            gradientStart = Color(0xFF2563EB),
            gradientEnd = Color(0xFF1D4ED8),
            radiusScale = 1.5f
        )
    )

    val all: List<AppSkinDefinition> = listOf(Xiaocheng)
    val Default: AppSkinDefinition = Xiaocheng
}
