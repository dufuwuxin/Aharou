package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.util.formatCostUsd
import com.aharou.feature.settings.presentation.TokenStatsUiState
import java.text.NumberFormat
import java.util.Locale

@Composable
internal fun TokenUsageOverview(state: TokenStatsUiState) {
    val summary = state.summary
    val hasData = summary != null && summary.calls > 0
    val numbers = NumberFormat.getIntegerInstance()
    fun count(value: Long?) = if (hasData && value != null) numbers.format(value) else "—"
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Surface(shape = RoundedCornerShape(Radius.mdLarge), color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.fillMaxWidth().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(stringResource(R.string.settings_token_stats_total), style = MaterialTheme.typography.labelLarge)
                Text(count(summary?.let { it.inputTokens + it.outputTokens }), style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                Text(stringResource(R.string.token_usage_cache_included), style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                    UsageValue(stringResource(R.string.settings_token_stats_legend_input), count(summary?.inputTokens), Modifier.weight(1f))
                    UsageValue(stringResource(R.string.settings_token_stats_legend_output), count(summary?.outputTokens), Modifier.weight(1f))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            UsageCard(stringResource(R.string.settings_token_stats_requests), count(summary?.calls?.toLong()), Modifier.weight(1f))
            UsageCard(stringResource(R.string.token_usage_priced_estimate),
                if (!hasData || state.unpricedModelGroups > 0 && state.totalCostUsd == 0.0) "—" else formatCostUsd(state.totalCostUsd),
                Modifier.weight(1f))
        }
        if (state.unpricedModelGroups > 0) {
            Text(stringResource(R.string.token_usage_unpriced, state.unpricedModelGroups),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            UsageCard(stringResource(R.string.token_usage_cache_read), count(summary?.cachedInputTokens), Modifier.weight(1f))
            UsageCard(stringResource(R.string.token_usage_cache_write), count(summary?.cacheCreationTokens), Modifier.weight(1f))
        }
        SettingsGroup {
            SettingsRow(title = stringResource(R.string.settings_token_stats_cache_hit_rate), trailing = {
                Text(if (summary != null && summary.inputTokens > 0) String.format(Locale.getDefault(), "%.1f%%",
                    summary.cachedInputTokens.coerceIn(0, summary.inputTokens) * 100.0 / summary.inputTokens) else "—")
            })
            SettingsDivider()
            SettingsRow(title = stringResource(R.string.token_usage_errors), trailing = {
                Text(if (summary != null && summary.calls > 0) stringResource(R.string.token_usage_error_value, summary.errors,
                    String.format(Locale.getDefault(), "%.1f%%", summary.errors * 100.0 / summary.calls)) else "—")
            })
            SettingsDivider()
            SettingsRow(title = stringResource(R.string.token_usage_first_token), trailing = { Text(usageDuration(summary?.avgTtfbMillis)) })
            SettingsDivider()
            SettingsRow(title = stringResource(R.string.token_usage_duration), trailing = { Text(usageDuration(summary?.avgDurationMillis)) })
        }
    }
}

@Composable
private fun UsageCard(label: String, value: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(Radius.mdLarge), color = MaterialTheme.semanticColors.cardSurface) {
        UsageValue(label, value, Modifier.padding(Spacing.md))
    }
}

@Composable
private fun UsageValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold)
    }
}

private fun usageDuration(value: Double?): String = when {
    value == null || !value.isFinite() || value < 0 -> "—"
    value >= 1000 -> String.format(Locale.getDefault(), "%.2f s", value / 1000)
    else -> String.format(Locale.getDefault(), "%.0f ms", value)
}
