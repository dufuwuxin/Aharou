package com.aharou.feature.agent.domain.tool

import com.aharou.feature.agent.domain.tool.custom.CustomToolSource
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolRegistry @Inject constructor(
    private val guard: ToolCallGuard? = null,
    /** 用户自定义工具的来源（内存快照，不碰磁盘）。没配时注册表就是内置 + MCP。 */
    private val customToolSource: CustomToolSource? = null,
) {
    // LinkedHashMap 保留注册顺序（内置工具固定顺序 + MCP 动态追加），避免工具增删后
    // 前缀顺序漂移打断隐式前缀缓存；synchronizedMap 保证并发注册/读取安全。
    private val tools = Collections.synchronizedMap(LinkedHashMap<String, AgentTool>())

    /** 已展开的延迟加载工具名。只在 [activate] 里增长，[unregister] 时随之清理。 */
    private val activated = mutableSetOf<String>()

    fun register(name: String, tool: AgentTool) {
        // 统一套一层重复调用守卫：模型拿完全相同的参数反复打同一个工具时直接拦下。
        tools[name] = guard?.wrap(tool) ?: tool
    }

    fun getTool(name: String): AgentTool? {
        return tools[name] ?: customTools().firstOrNull { it.name == name }
    }

    fun unregister(name: String) {
        tools.remove(name)
        synchronized(activated) { activated.remove(name) }
    }

    /** 已注册工具名 + 自定义工具名（自定义工具不进 `tools` map，每次从快照现算）。 */
    fun getToolNames(): Set<String> {
        val custom = customTools().map { it.name }
        return synchronized(tools) { tools.keys.toSet() } + custom
    }

    /**
     * 返回可用工具列表（内置 + MCP 动态注册 + 用户自定义），**始终**按名称排序。
     *
     * 排序不是为了好看：工具顺序会参与隐式前缀缓存，必须每轮稳定；按名称排是确定性的，
     * 工具集不变时顺序就不变。同名以内置/已注册的为准（自定义名撞车时不会覆盖内置工具）。
     *
     * 「只有带自定义工具时才排序」是个坑（2026-11-01 修）：没有自定义工具时返回注册顺序，
     * 而在有自定义工具时才是名字序，两种形态的顺序在测试与真实环境里对不上，
     * 压缩用例 `manualCompactionWithoutDefinitionIncludesNestedTask` 就因此整条挂掉。
     *
     * PLAN 模式下仍返回全部工具定义（让 AI 知道这些工具存在，可在计划中引用），
     * 写操作工具由 [com.aharou.feature.agent.domain.permission.ToolPermissionPolicyEngine] 在运行时拦截并返回 PLAN_MODE_REJECTED。
     */
    fun getAvailableTools(): List<AgentTool> {
        val registered = synchronized(tools) { tools.values.toList() }
        val registeredNames = registered.mapTo(HashSet()) { it.name }
        val custom = customTools().filterNot { it.name in registeredNames }
        return (registered + custom).sortedBy { it.name }
    }

    private fun customTools(): List<AgentTool> = customToolSource?.customTools().orEmpty()

    /** 延迟加载工具的目录（名称 + 描述），供 `tool_search` 检索。 */
    fun getDeferredTools(): List<AgentTool> {
        synchronized(tools) {
            // 同样排序：tool_search 的目录顺序稳定，模型两次检索看到的结果才一致。
            return tools.values.filter { it.deferredLoading }.sortedBy { it.name }
        }
    }

    /** 展开一批延迟加载工具，使其进入后续每轮的 tools 数组。 */
    fun activate(names: Collection<String>) {
        synchronized(activated) { activated.addAll(names) }
    }

    fun isActivated(name: String): Boolean = synchronized(activated) { name in activated }

    fun hasTool(name: String): Boolean {
        return tools.containsKey(name) || customTools().any { it.name == name }
    }
}
