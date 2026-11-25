package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.SwipeToDeleteRow
import com.aharou.feature.settings.domain.model.AIProviderConfig
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import androidx.compose.ui.res.stringResource
import com.aharou.R

/**
 * 提供商行：左侧品牌 logo（按提供商名称自动识别），
 * 中部两行（名称 / 类型 + 模型数量 pills），右侧状态 pill + 箭头。
 * 整行点击进入编辑，左滑露出删除按钮；长按左侧信息区域可调整顺序。
 */
@Composable
fun ProviderItem(
    provider: AIProviderConfig,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    dragModifier: Modifier = Modifier
) {
    val sortDescription = stringResource(R.string.provider_sort_long_press)
    // 状态色与 MCP 行一致：启用用主题 tertiary（绿调），停用用 outline（灰）。
    val statusColor = if (provider.isEnabled) MaterialTheme.semanticColors.success else MaterialTheme.colorScheme.outline

    SwipeToDeleteRow(
        onDelete = onDelete,
        onClick = onEdit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .then(dragModifier)
                    .semantics {
                        contentDescription = sortDescription
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧品牌 logo 容器
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        )
                ) {
                    ProviderLogoIcon(
                        provider = provider,
                        size = 22.dp,
                        modifier = Modifier.size(22.dp).align(Alignment.Center)
                    )
                }

                Spacer(modifier = Modifier.width(Spacing.md))

                // 中间：名称 / 类型 + 模型数量
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = provider.name,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        McpPill(
                            text = providerTypeLabel(provider.type),
                            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        )
                        McpPill(
                            text = stringResource(R.string.provider_models_count_tag, provider.models.size),
                            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(Spacing.sm))

            // 启用/停用状态 pill + 右箭头
            McpPill(
                text = stringResource(if (provider.isEnabled) R.string.common_enabled else R.string.common_disabled),
                textColor = statusColor,
                backgroundColor = statusColor.copy(alpha = 0.12f)
            )
            Spacer(Modifier.width(Spacing.xs))
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
