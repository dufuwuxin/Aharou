package com.aharou.feature.settings.presentation

import com.aharou.R
import com.aharou.feature.agent.data.local.dao.CallSummary
import com.aharou.feature.agent.data.local.dao.DayCallStats
import com.aharou.feature.agent.data.local.dao.ModelCallStats
import com.aharou.feature.agent.data.local.dao.ProviderCallStats
import com.aharou.feature.agent.data.local.dao.RecentCallRecord
import java.util.Calendar
import java.util.TimeZone

enum class TokenStatsPeriod(val labelRes: Int, val startMillis: (Long) -> Long) {
    TODAY(R.string.settings_token_stats_period_today, { tokenStatsStartOfDay(it, 0) }),
    LAST_7_DAYS(R.string.settings_token_stats_period_7d, { tokenStatsStartOfDay(it, 6) }),
    LAST_30_DAYS(R.string.settings_token_stats_period_30d, { tokenStatsStartOfDay(it, 29) }),
    ALL(R.string.settings_token_stats_period_all, { 0L })
}

internal fun tokenStatsStartOfDay(now: Long, daysAgo: Int, zone: TimeZone = TimeZone.getDefault()): Long =
    Calendar.getInstance(zone).apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, -daysAgo)
    }.timeInMillis

data class TokenStatsUiState(
    val period: TokenStatsPeriod = TokenStatsPeriod.LAST_7_DAYS,
    val summary: CallSummary? = null,
    val trend: List<DayCallStats> = emptyList(),
    val providers: List<ProviderCallStats> = emptyList(),
    val models: List<ModelCallStats> = emptyList(),
    val recentCalls: List<RecentCallRecord> = emptyList(),
    /** 调用明细当前页（0 起）。 */
    val callsPage: Int = 0,
    /** 当前周期调用总数，供分页显示总页数。 */
    val callsTotal: Int = 0,
    /** 渠道当前页（0 起）。 */
    val providersPage: Int = 0,
    /** 渠道总数。 */
    val providersTotal: Int = 0,
    /** 模型当前页（0 起）。 */
    val modelsPage: Int = 0,
    /** 模型总数。 */
    val modelsTotal: Int = 0,
    /** Only priced provider/model groups contribute; unknown groups are counted separately. */
    val totalCostUsd: Double = 0.0,
    val unpricedModelGroups: Int = 0,
    /** 调用明细分页内每条记录的费用（key=记录 id，null=模型无单价）。 */
    val recentCallCosts: Map<Long, Double?> = emptyMap(),
    val filterProviderId: String? = null,
    val filterModel: String? = null,
    val availableProviders: List<Pair<String, String>> = emptyList(),
    val availableModels: List<String> = emptyList(),
    /** 各供应商 ID 对应的密钥/凭据数量。 */
    val providerKeyCounts: Map<String, Int> = emptyMap()
)

internal data class ModelStatsPaging(
    val paged: List<ModelCallStats>,
    val page: Int,
    val total: Int
)
