package com.aharou.feature.agent.domain.tool.custom

import com.aharou.feature.agent.domain.container.CommandEngine
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自定义工具的来源：[ToolRegistry] 靠它把用户自建工具并进可用工具列表。
 *
 * 抽成接口是为了让注册表不直接依赖 [CommandEngine] / [WorkspaceRepository]
 * （注册表本身是纯内存结构，单测里 `ToolRegistry()` 不带任何参数也要能建出来）。
 */
fun interface CustomToolSource {
    fun customTools(): List<AgentTool>

    fun customTools(snapshot: ToolSettingsStore.Snapshot): List<AgentTool> = customTools()
}

/** 生产实现：从 [ToolSettingsStore] 的内存快照即时构建工具实例（不做磁盘 IO）。 */
@Singleton
class CustomToolRuntime @Inject constructor(
    private val store: ToolSettingsStore,
    private val commandEngine: CommandEngine,
    private val workspaceRepository: WorkspaceRepository,
    @param:ApplicationContext private val context: Context,
) : CustomToolSource {

    override fun customTools(): List<AgentTool> = toolsFrom(store.snapshotCustomTools())

    override fun customTools(snapshot: ToolSettingsStore.Snapshot): List<AgentTool> = toolsFrom(snapshot.tools)

    private fun toolsFrom(definitions: List<CustomToolDefinition>): List<AgentTool> =
        definitions.map { definition ->
            CustomCommandTool(definition, commandEngine, workspaceRepository, context)
        }
}
