package com.aharou.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import compose.icons.FeatherIcons
import compose.icons.feathericons.Box
import compose.icons.feathericons.MessageCircle
import compose.icons.feathericons.Settings
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors

/**
 * 底部主导航：三个标签「首页 / 配置 / 设置」。
 *
 * 与侧边栏不同，这是一条常驻在屏幕底部的胶囊条；每个标签独立可点，点击后
 * 由 [onClick] 切到对应目标页。
 */
@Composable
fun BottomNavBar(
    selectedTab: Int,           // 0 = 首页, 1 = 配置, 2 = 设置
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
            .height(56.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TabButton(
            icon = FeatherIcons.MessageCircle,
            label = stringResource(R.string.bottom_tab_home),
            selected = selectedTab == 0,
            onClick = { onClick(0) }
        )
        TabButton(
            icon = FeatherIcons.Box,
            label = stringResource(R.string.bottom_tab_config),
            selected = selectedTab == 1,
            onClick = { onClick(1) }
        )
        TabButton(
            icon = FeatherIcons.Settings,
            label = stringResource(R.string.bottom_tab_settings),
            selected = selectedTab == 2,
            onClick = { onClick(2) }
        )
    }
}

@Composable
private fun TabButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    } else {
        androidx.compose.ui.graphics.Color.Transparent
    }
    Row(
        modifier = Modifier
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
            .background(bg, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(20.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}
