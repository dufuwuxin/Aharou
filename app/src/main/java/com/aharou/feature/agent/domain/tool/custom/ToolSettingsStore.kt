package com.aharou.feature.agent.domain.tool.custom

import com.aharou.core.util.FileLogger
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 自定义工具与覆盖项的持久化。
 *
 * 存储沿用仓库既有的「文件资产」范式（与 `prompts.custom` / `skills` 一致，不新增 config 通道标量）：
 *
 * ```
 * <全局层>/tools/custom/<name>.json      一条自定义工具一个文件
 * <全局层>/tools/overrides.json          内置 / MCP 工具的覆盖项（name → ToolOverride）
 * <工作区>/.aharou/tools/...             同结构，项目层优先、全局层兜底
 * ```
 *
 * 三件事是这个类的核心约束：
 * 1. **构建路径不能做磁盘 IO**：每轮提示词/工具数组的构建只读 [snapshotCustomTools] /
 *    [snapshotOverrides] 这份 `@Volatile` 内存快照，磁盘读取只发生在 [refresh] 与写操作之后。
 * 2. **单个文件坏掉不连坐**：每个文件独立 `runCatching`，坏的那条跳过并记日志，其余照常生效
 *    （用户手改 JSON 出错时，不能让整个工具页空掉）。
 * 3. **写完立即更新快照**：写盘后同步重读一次，内存与磁盘不留窗口期。读盘与写盘共用同一把
 *    互斥锁串行化——否则「读盘发生在写入之前、发布发生在写入之后」的旧快照会盖掉新值，
 *    界面上就会出现「刚保存好、列表又变回空的」。
 */
@Singleton
class ToolSettingsStore @Inject constructor(
    private val locator: ToolStorageLocator
) {

    data class Snapshot(
        val tools: List<CustomToolDefinition> = emptyList(),
        val overrides: Map<String, ToolOverride> = emptyMap()
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 读盘 / 写盘都在这把锁里完成：快照发布顺序与磁盘写入顺序保持一致。 */
    private val mutex = Mutex()

    @Volatile
    private var snapshot = Snapshot()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = true
    }

    init {
        // 启动即预热一次：工具数组的构建路径不做 IO，第一次 refresh 只能由这里发起。
        scope.launch { runCatching { refresh() }.onFailure { if (it is CancellationException) throw it } }
    }

    /** 全部自定义工具（项目层覆盖全局层），按名称排序。同步读内存快照，不做 IO。 */
    fun snapshotCustomTools(): List<CustomToolDefinition> = snapshot.tools

    /** 内置 / MCP 工具的覆盖项。同步读内存快照，不做 IO。 */
    fun snapshotOverrides(): Map<String, ToolOverride> = snapshot.overrides

    /** 单个自定义工具；界面编辑器与「这个工具是不是自建的」判定都用它。 */
    fun snapshotCustomTool(name: String): CustomToolDefinition? =
        snapshot.tools.firstOrNull { it.name == name }

    /** 从磁盘重读快照。与写操作串行：读盘期间不会有写操作插进来。 */
    suspend fun refresh() {
        mutex.withLock {
            snapshot = withContext(Dispatchers.IO) { loadSnapshot() }
        }
    }

    /** 独立运行快照：返回给调用轮，不发布到 UI 快照，也不会被后来的 refresh 替换。 */
    suspend fun snapshotForProject(projectRoot: String): Snapshot = mutex.withLock {
        withContext(Dispatchers.IO) { loadSnapshot(locator.projectToolsDir(projectRoot)) }
    }

    suspend fun saveCustomTool(definition: CustomToolDefinition): Boolean {
        val normalized = definition.normalized()
        if (!normalized.isValidName) {
            FileLogger.w(TAG, "拒绝保存自定义工具：名称不合法（${definition.name}）")
            return false
        }
        return mutate { writeCustomTool(normalized) }
    }

    suspend fun createCustomTool(definition: CustomToolDefinition): Boolean {
        val normalized = definition.normalized()
        if (!normalized.isValidName) return false
        return mutate {
            if (customDirs().any { File(it, "${normalized.name}.json").exists() }) false
            else writeCustomTool(normalized)
        }
    }

    suspend fun deleteCustomTool(name: String): Boolean = mutate {
        var deleted = false
        customDirs().forEach { dir ->
            val file = File(dir, "$name.json")
            if (file.isFile && file.delete()) deleted = true
        }
        deleted
    }

    /** 保存内置 / MCP 工具的覆盖；[override] 为空则等价于清除。 */
    suspend fun saveOverride(name: String, override: ToolOverride): Boolean {
        if (name.isBlank()) return false
        val normalized = override.normalized()
        if (normalized.isEmpty) return clearOverride(name)
        return mutate { writeOverride(name, normalized) }
    }

    suspend fun clearOverride(name: String): Boolean = mutate {
        var changed = false
        overrideFiles().forEach { file ->
            val current = readOverridesFile(file) ?: return@forEach
            if (current.containsKey(name)) {
                changed = writeOverridesFile(file, current - name) || changed
            }
        }
        changed
    }

    // ---- 写盘 ----

    /** 写盘后立即重读；失败时不碰快照（内存里仍是最后一次成功的状态）。 */
    private suspend fun mutate(write: () -> Boolean): Boolean = mutex.withLock {
        val ok = withContext(Dispatchers.IO) {
            runCatching { write() }.getOrElse { error ->
                FileLogger.e(TAG, "写工具配置失败", error)
                false
            }
        }
        if (ok) snapshot = withContext(Dispatchers.IO) { loadSnapshot() }
        ok
    }

    /**
     * 写到该工具「当前生效的那一层」：项目层有就写项目层，否则全局层有就写全局层，
     * 都没有就写可写层（有工作区即项目层）。写的同时清掉另一层的同名文件，
     * 保证一个工具只有一份权威定义——与 `PromptFragmentCatalog.saveOverride` 同一套语义。
     */
    private fun writeCustomTool(definition: CustomToolDefinition): Boolean {
        val projectDir = projectToolsDir()?.let { File(it, CUSTOM_DIR) }
        val globalDir = File(globalToolsDir(), CUSTOM_DIR)
        val target = when {
            projectDir != null && File(projectDir, "${definition.name}.json").isFile -> projectDir
            File(globalDir, "${definition.name}.json").isFile -> globalDir
            else -> projectDir ?: globalDir
        }
        listOfNotNull(projectDir, globalDir)
            .filter { it != target }
            .forEach { File(it, "${definition.name}.json").takeIf { stale -> stale.isFile }?.delete() }
        return writeText(File(target, "${definition.name}.json"), json.encodeToString(definition))
    }

    /** 覆盖项写到可写层（与工具本体一致：有工作区写项目层）；只改这一个 key，其余读改写保留。 */
    private fun writeOverride(name: String, override: ToolOverride): Boolean {
        val file = File(resolveWritableToolsDir(), OVERRIDES_FILE)
        val current = readOverridesFile(file).orEmpty()
        return writeOverridesFile(file, current + (name to override))
    }

    private fun writeOverridesFile(file: File, overrides: Map<String, ToolOverride>): Boolean {
        if (overrides.isEmpty()) {
            // 全空就把文件删掉，别在用户目录里留一个 `{}` 噪音文件
            return !file.isFile || file.delete()
        }
        return writeText(file, json.encodeToString(overrides))
    }

    /** 先写临时文件再改名：写到一半被杀掉时不会留下半截 JSON。 */
    private fun writeText(target: File, content: String): Boolean {
        val parent = target.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        val temp = File(parent, "${target.name}.tmp")
        return runCatching {
            temp.writeText(content, Charsets.UTF_8)
            if (temp.renameTo(target)) {
                true
            } else {
                target.delete()
                temp.renameTo(target)
            }
        }.getOrElse { error ->
            FileLogger.e(TAG, "写文件失败: ${target.path}", error)
            temp.delete()
            false
        }
    }

    // ---- 读盘 ----

    private fun loadSnapshot(projectDir: File? = projectToolsDir()): Snapshot {
        val tools = LinkedHashMap<String, CustomToolDefinition>()
        listOfNotNull(globalToolsDir(), projectDir).map { File(it, CUSTOM_DIR) }.forEach { dir ->
            readCustomTools(dir).forEach { tools[it.name] = it }
        }
        val overrides = LinkedHashMap<String, ToolOverride>()
        listOfNotNull(globalToolsDir(), projectDir).map { File(it, OVERRIDES_FILE) }.forEach { file ->
            readOverridesFile(file)?.forEach { (name, override) ->
                if (!override.isEmpty) overrides[name] = override
            }
        }
        return Snapshot(tools = tools.values.sortedBy { it.name }, overrides = overrides.toMap())
    }

    /** 目录里一条坏 JSON 只影响它自己：解析失败记日志跳过，其余条目照常返回。 */
    private fun readCustomTools(dir: File): List<CustomToolDefinition> {
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".json") } ?: return emptyList()
        return files.sortedBy { it.name }.mapNotNull { file ->
            val fallbackName = file.nameWithoutExtension
            runCatching {
                val parsed = json.decodeFromString(CustomToolDefinition.serializer(), file.readText(Charsets.UTF_8))
                val definition = parsed.copy(name = parsed.name.ifBlank { fallbackName }).normalized()
                definition.takeIf { it.isValidName }
            }.getOrElse { error ->
                FileLogger.w(TAG, "自定义工具文件损坏，已跳过: ${file.path}", error)
                null
            }
        }
    }

    private fun readOverridesFile(file: File): Map<String, ToolOverride>? {
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString(
                MapSerializer(String.serializer(), ToolOverride.serializer()),
                file.readText(Charsets.UTF_8)
            )
        }.getOrElse { error ->
            FileLogger.w(TAG, "工具覆盖文件损坏，已跳过: ${file.path}", error)
            null
        }
    }

    // ---- 路径 ----

    private fun projectToolsDir(): File? = locator.projectToolsDir()

    private fun globalToolsDir(): File = locator.globalToolsDir()

    /** 读顺序即优先级：全局先入表，项目层后入覆盖同名项。 */
    private fun customDirs(): List<File> =
        listOfNotNull(globalToolsDir(), projectToolsDir()).map { File(it, CUSTOM_DIR) }

    private fun overrideFiles(): List<File> =
        listOfNotNull(globalToolsDir(), projectToolsDir()).map { File(it, OVERRIDES_FILE) }

    /** 有工作区写项目层、否则写全局层（与 prompts / skills 的「可写层」一致）。 */
    private fun resolveWritableToolsDir(): File = projectToolsDir() ?: globalToolsDir()

    private companion object {
        const val TAG = "ToolSettingsStore"
        const val CUSTOM_DIR = "custom"
        const val OVERRIDES_FILE = "overrides.json"
    }
}
