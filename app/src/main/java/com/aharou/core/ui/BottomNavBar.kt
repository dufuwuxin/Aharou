package com.aharou.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing

/**
 * 底部主导航：悬浮圆角玻璃卡片（16dp 边距 + 28dp 圆角 + 外投影 + 渐变玻璃面 + 顶部高光线）。
 * 三个图标 Tab（无文字）；仅首页/配置/设置三条主路由可见，进入会话详情后隐藏。
 *
 * 与旧 `Surface(fillMaxWidth).height(64dp)` 实现的区别：
 * - 不再吸底占用内容区，而是「浮」在内容上方；因此聊天输入框等内容不被遮挡。
 * - 使用渐变半透明背景 + 高光描边模拟液体玻璃材质。
 */
@Composable
fun BottomNavBar(
    selectedTab: Int,           // 0 = 首页, 1 = 配置, 2 = 设置
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val glassBackgroundBrush = Brush.verticalGradient(
        colors = if (isLight) listOf(
            Color(0xFFFFFFFF).copy(alpha = 0.72f),
            Color(0xFFF4F7FA).copy(alpha = 0.84f)
        ) else listOf(
            Color(0xFF0A0F18).copy(alpha = 0.72f),
            Color(0xFF121B2A).copy(alpha = 0.84f)
        )
    )
    val glassHighlightBrush = Brush.linearGradient(
        colorStops = arrayOf(
            0.0f to Color.White.copy(alpha = if (isLight) 0.45f else 0.14f),
            0.45f to Color.White.copy(alpha = if (isLight) 0.06f else 0.02f),
            1.0f to Color.White.copy(alpha = 0f)
        )
    )
    val glassBorderBrush = Brush.verticalGradient(
        colors = if (isLight) listOf(
            Color.Black.copy(alpha = 0.08f),
            Color.Black.copy(alpha = 0.02f),
            Color.Black.copy(alpha = 0.10f)
        ) else listOf(
            Color.White.copy(alpha = 0.22f),
            Color.White.copy(alpha = 0.06f),
            Color.White.copy(alpha = 0.14f)
        )
    )

    // 外层占满宽度，底侧渐隐：让内容从卡片下方滑过时自然被遮盖。
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(68.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background.copy(alpha = 0f),
                        MaterialTheme.colorScheme.background.copy(alpha = 0.98f)
                    )
                )
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            tonalElevation = 0.dp,
            color = Color.Transparent,
            modifier = Modifier
                .padding(horizontal = Spacing.xl)
                .padding(bottom = Spacing.md)
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(Radius.pill),
                    ambientColor = if (isLight) Color.Black.copy(alpha = 0.06f) else Color(0xFF000000).copy(alpha = 0.30f),
                    spotColor = if (isLight) Color.Black.copy(alpha = 0.12f) else Color(0xFF000000).copy(alpha = 0.45f)
                )
                .clip(RoundedCornerShape(Radius.pill))
                .background(glassBackgroundBrush)
                .border(1.dp, glassBorderBrush, RoundedCornerShape(Radius.pill))
                .height(52.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                NavTab(
                    iconRes = R.drawable.ic_nav_home,
                    label = stringResource(R.string.bottom_tab_home),
                    selected = selectedTab == 0,
                    onClick = { onClick(0) }
                )
                NavTab(
                    iconRes = R.drawable.ic_nav_config,
                    label = stringResource(R.string.bottom_tab_config),
                    selected = selectedTab == 1,
                    onClick = { onClick(1) }
                )
                NavTab(
                    iconRes = R.drawable.ic_nav_settings,
                    label = stringResource(R.string.bottom_tab_settings),
                    selected = selectedTab == 2,
                    onClick = { onClick(2) }
                )
            }
            // 顶部高光线：玻璃「厚度」的视觉线索。
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(0.90f)
                    .height(1.dp)
                    .background(glassHighlightBrush)
            )
        }
    }
}

@Composable
private fun NavTab(
    iconRes: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(64.dp)
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.xs)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(26.dp)
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = label,
                modifier = Modifier.size(20.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(3.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                        .align(Alignment.BottomCenter)
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Spacing.xs)
        )
    }
}