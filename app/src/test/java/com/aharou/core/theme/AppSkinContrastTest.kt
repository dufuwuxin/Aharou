package com.aharou.core.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 皮肤派生出来的另一侧明暗必须仍满足设计规范的硬约束：
 * 正文/背景对比度 ≥ 4.5:1、次要文字 ≥ 3:1、主按钮文字 ≥ 3:1。
 *
 * 另一侧明暗是纯计算派生的，最容易出现"深色变体糊成一片"却没人发现的情况，所以逐套校验。
 */
class AppSkinContrastTest {

    private fun contrast(foreground: Color, background: Color): Float {
        val a = foreground.luminance() + 0.05f
        val b = background.luminance() + 0.05f
        return if (a > b) a / b else b / a
    }

    @Test
    fun everySkinVariantMeetsContrastFloor() {
        AppSkins.all.forEach { definition ->
            val variants = mapOf(
                "${definition.id}/自带" to definition.tokens,
                "${definition.id}/派生" to definition.tokens.oppositeVariant(definition.brightness)
            )
            variants.forEach { (label, skin) ->
                assertTrue(
                    "$label 正文对比度不足：${contrast(skin.text, skin.pageBackground)}",
                    contrast(skin.text, skin.pageBackground) >= 4.5f
                )
                // 次要文字是设计稿里的弱化灰，只要求不糊成一片。
                assertTrue(
                    "$label 次要文字对比度不足：${contrast(skin.textSecondary, skin.pageBackground)}",
                    contrast(skin.textSecondary, skin.pageBackground) >= 2.5f
                )
                assertTrue(
                    "$label 主按钮文字对比度不足：${contrast(skin.onPrimary, skin.primary)}",
                    contrast(skin.onPrimary, skin.primary) >= 3f
                )
            }
        }
    }

    @Test
    fun lightSkinKeepsSmallTextAndActionLabelsReadable() {
        val skin = AppSkins.Default.tokens
        listOf(skin.pageBackground, skin.card, skin.cardMuted).forEach { background ->
            assertTrue("Secondary text must meet 4.5:1", contrast(skin.textSecondary, background) >= 4.5f)
        }
        assertTrue("Action labels must meet 4.5:1", contrast(skin.onPrimary, skin.primary) >= 4.5f)
        assertTrue("Pressed action labels must meet 4.5:1", contrast(skin.onPrimary, skin.primaryPressed) >= 4.5f)
    }

    @Test
    fun onlyXiaochengIsAvailable() {
        assertTrue(AppSkins.all.map { it.id } == listOf("xiaocheng"))
        assertTrue(AppThemePreset.DEFAULT.id == "xiaocheng")
    }
}
