package com.aharou.feature.agent.domain.tool.custom

import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import com.aharou.feature.workspace.domain.ProjectAharouRoot
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 工具配置的落盘位置解析：全局层 + 项目层。
 *
 * 抽成接口不是为解耦而解耦：[ToolSettingsStore] 因此不依赖 `Context` / `ContainerInstaller`
 * 这些 Android 实体，单测能直接塞临时目录，不必拖 Robolectric 进 JVM 测试。
 */
interface ToolStorageLocator {

    /** 全局层工具目录（`<aharouDir>/tools`，与 `prompts.custom`、`skills` 同一个根）。 */
    fun globalToolsDir(): File

    /** 项目层工具目录（`<workspace>/.aharou/tools`，远程走 [ProjectAharouRoot] 私有目录）；无工作区为 null。 */
    fun projectToolsDir(): File?

    /** 运行期必须传会话工作区，空路径只加载全局层，不读取 UI 当前工作区。 */
    fun projectToolsDir(projectRoot: String): File? = projectRoot.takeIf { it.isNotBlank() }
        ?.let { File(it, ".aharou/tools") }
}

/** 生产实现：照抄 `PromptFragmentCatalog` 的两级来源解析（项目 > 全局）。 */
@Singleton
class AharouToolStorageLocator @Inject constructor(
    private val containerInstaller: ContainerInstaller,
    private val projectAharouRoot: ProjectAharouRoot,
    private val workspaceRepository: WorkspaceRepository
) : ToolStorageLocator {

    override fun globalToolsDir(): File = File(containerInstaller.aharouDir, TOOLS_DIR)

    override fun projectToolsDir(): File? =
        workspaceRepository.currentPath().takeIf { it.isNotBlank() }
            ?.let { File(projectAharouRoot.forPath(it), TOOLS_DIR) }

    override fun projectToolsDir(projectRoot: String): File? =
        projectRoot.takeIf { it.isNotBlank() }?.let { File(projectAharouRoot.forPath(it), TOOLS_DIR) }

    private companion object {
        const val TOOLS_DIR = "tools"
    }
}
