package com.aharou.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing

/**
 * 底部主导航：三个图片图标标签（无文字），选中态用主色高亮 + 底部指示点。
 * 图标资源：ic_nav_home（气泡）、ic_nav_config（立方体）、ic_nav_settings（齿轮）。
 */
@Composable
fun BottomNavBar(
    selectedTab: Int,           // 0 = 首页, 1 = 配置, 2 = 设置
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        tonalElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
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
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = label,
                modifier = Modifier.size(22.dp),
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
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
