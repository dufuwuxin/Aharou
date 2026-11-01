package com.aharou.feature.settings.presentation

import androidx.lifecycle.ViewModel
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolPermissionPolicy
import com.aharou.feature.agent.domain.tool.ToolRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** 工具在「工具」页上的只读快照；设置页只展示，不改注册表。 */
data class ToolUiEntry(
    val name: String,
    val description: String,
    val permission: ToolPermissionPolicy,
    /** 是否属于「延迟加载」目录：这类工具先由 `tool_search` 检索出来才进上下文，省每轮 schema 钱。 */
    val deferred: Boolean,
    val category: ToolCategory,
)

/** 工具分类：按能力轴归组，用户找工具时先按「这类工具能干什么」扫，而不是背工具名。 */
enum class ToolCategory {
    FILE, COMMAND, NETWORK, DEVICE, AGENT, EXTERNAL, OTHER
}

/**
 * 「工具」页的数据源：直接读 [ToolRegistry] 的当前快照。
 *
 * 刻意不塞进 SettingsViewModel：那个类已经顶在架构门禁的棘轮基线上（只许降不许涨），
 * 而且工具列表是纯只读派生数据，跟设置页的编辑状态没有交集。
 */
@HiltViewModel
class ToolsViewModel @Inject constructor(
    toolRegistry: ToolRegistry
) : ViewModel() {

    /** 延迟加载目录里的工具名：UI 上要标出来，否则用户会奇怪为什么对话里「看不见」它。 */
    private val deferredNames: Set<String> = toolRegistry.getDeferredTools().map { it.name }.toSet()

    /** 全部已注册工具，按分类 + 名称排序，保证每次进页面顺序稳定。 */
    val entries: List<ToolUiEntry> = toolRegistry.getAvailableTools()
        .map { tool ->
            ToolUiEntry(
                name = tool.name,
                description = tool.description.lineSequence().firstOrNull().orEmpty().trim(),
                permission = tool.permissionPolicy,
                deferred = tool.name in deferredNames,
                category = categoryOf(tool),
            )
        }
        .sortedWith(compareBy({ it.category.ordinal }, { it.name.lowercase() }))

    /** 按分类归组（保持枚举顺序，空组不返回）。 */
    fun grouped(): List<Pair<ToolCategory, List<ToolUiEntry>>> =
        ToolCategory.entries.mapNotNull { category ->
            entries.filter { it.category == category }.takeIf { it.isNotEmpty() }?.let { category to it }
        }

    /**
     * 一个工具只归一类，按「用户最想先知道的影响面」排序：
     * 能跑命令 > 能改文件 > 能联网 > 能读设备 > 改 agent 自身配置 > 外部服务。
     */
    private fun categoryOf(tool: AgentTool): ToolCategory {
        val capabilities = tool.capabilities
        return when {
            ToolCapability.EXECUTE_COMMANDS in capabilities -> ToolCategory.COMMAND
            ToolCapability.WRITE_WORKSPACE in capabilities || ToolCapability.READ_WORKSPACE in capabilities ->
                ToolCategory.FILE
            ToolCapability.NETWORK_WRITE in capabilities || ToolCapability.NETWORK_READ in capabilities ->
                ToolCategory.NETWORK
            ToolCapability.READ_DEVICE_DATA in capabilities ||
                ToolCapability.MODIFY_DEVICE_STATE in capabilities -> ToolCategory.DEVICE
            ToolCapability.MODIFY_AGENT_CONFIG in capabilities ||
                ToolCapability.READ_AGENT_CONFIG in capabilities ||
                ToolCapability.MODIFY_SESSION_STATE in capabilities ||
                ToolCapability.MODIFY_TODO_STATE in capabilities ||
                ToolCapability.USER_INTERACTION in capabilities -> ToolCategory.AGENT
            ToolCapability.EXTERNAL_TOOL in capabilities -> ToolCategory.EXTERNAL
            else -> ToolCategory.OTHER
        }
    }
}
