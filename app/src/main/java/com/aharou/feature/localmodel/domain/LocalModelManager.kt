package com.aharou.feature.localmodel.domain

import android.content.Context
import android.os.Build
import android.os.StatFs
import com.aharou.R
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 端侧模型的下载、校验、安装与删除。
 *
 * 目录布局：`filesDir/local_models/<spec.id>/<spec.fileName>`，下载中的半成品是同目录下的
 * `<spec.fileName>.part`。就绪判据只有一条——正式文件字节数正好等于 [LocalModelSpec.sizeBytes]，
 * 不写「已安装」标记文件，避免标记与实体不一致。
 *
 * 安装流程（[download]）：已有半成品就断点续传 → 下完算 sha256 与官方值比对 → 一致才
 * `renameTo` 成正式文件。sha256 不匹配是唯一会删半成品的场合；用户中途取消时半成品必须留着，
 * 那正是续传的价值（1.5GB 重下对流量和耐心的消耗都不小）。
 *
 * 同一时刻只允许一个下载：[mutex] 串行化，[cancel] 取消的是正在跑的那个协程。
 */
@Singleton
class LocalModelManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** 下载串行化：两处同时触发会互相覆盖同一个 `.part`。 */
    private val mutex = Mutex()

    private val downloader = LocalModelDownloader(context)

    /** 正在跑的下载协程；[cancel] 用它中断，平时为 null。 */
    @Volatile
    private var activeJob: Job? = null

    /** 按 id 查模型；未知 id 返回 null。 */
    fun spec(id: String): LocalModelSpec? = LocalModels.byId(id)

    /** 单个模型的落盘状态（正式文件 / 半成品各自多少字节）。 */
    fun status(id: String): LocalModelStatus {
        val spec = LocalModels.byId(id)
            ?: return LocalModelStatus(id, 0L, installedBytes = null, partialBytes = null)
        val dir = modelDir(id)
        val installed = File(dir, spec.fileName)
        val part = File(dir, "${spec.fileName}.part")
        return LocalModelStatus(
            id = id,
            expectedBytes = spec.sizeBytes,
            installedBytes = installed.takeIf { it.isFile }?.length(),
            partialBytes = part.takeIf { it.isFile }?.length(),
        )
    }

    /** 全部模型的聚合状态（设置页列表照它显示）。 */
    fun statusAll(): List<LocalModelStatus> = LocalModels.ALL.map { status(it.id) }

    /** 模型是否已就绪（正式文件存在且字节数正好）。 */
    fun isReady(id: String): Boolean = status(id).ready

    /** 模型的正式文件路径；调用方拿到后可直接喂给推理库。 */
    fun modelFile(id: String): File {
        val spec = LocalModels.byId(id)
            ?: throw IllegalArgumentException("未知的端侧模型：$id")
        return File(modelDir(id), spec.fileName)
    }

    fun verifiedModelFile(id: String): File {
        val spec = spec(id) ?: throw IllegalArgumentException("Unknown local model: $id")
        return modelFile(id).also { LocalModelFiles.verify(it, spec) }
    }

    suspend fun importModel(id: String, source: () -> java.io.InputStream,
                            onState: (LocalModelState) -> Unit = {}): File = withContext(Dispatchers.IO) {
        mutex.withLock {
            val spec = spec(id) ?: throw IllegalArgumentException("Unknown local model: $id")
            val job = currentCoroutineContext()[Job]
            activeJob = job
            try {
                onState(LocalModelState.Verifying(id))
                val coroutine = currentCoroutineContext()
                source().use { LocalModelFiles.import(it, modelFile(id), spec) { coroutine.ensureActive() } }
                onState(LocalModelState.Ready(id))
                modelFile(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onState(LocalModelState.Failed(id, e.message.orEmpty()))
                throw e
            } finally {
                if (activeJob === job) activeJob = null
            }
        }
    }

    /**
     * 本机能否跑端侧推理：只看 ABI 是不是 arm64-v8a。
     *
     * 推理库在非 arm64 上加载的是通用回退库，`System.loadLibrary` 会直接抛
     * `UnsatisfiedLinkError`——那是 Error 不是 Exception，让调用方自己挡不现实，
     * 所以必须在下载前、加载前用这一句统一挡掉。
     */
    fun isSupportedOnThisDevice(): Boolean =
        Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"

    /**
     * 下载并安装模型，返回正式文件；已就绪且 [force] 为 false 时直接返回。
     *
     * @param force true 表示强制重下（用户点「重试」/换源），会覆盖现有文件
     * @param onState 进度回调：Downloading → Verifying → Ready，失败时 Failed
     * @throws IOException 空间不足、下载失败、校验不通过、落盘失败
     * @throws kotlinx.coroutines.CancellationException [cancel] 或调用方取消时原样抛出
     */
    suspend fun download(
        spec: LocalModelSpec,
        force: Boolean = false,
        onState: (LocalModelState) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        mutex.withLock {
            // withContext 自己带一层子 Job：cancel() 中断它不会连带取消调用方的 viewModelScope
            val job = currentCoroutineContext()[Job]
            activeJob = job
            try {
                install(spec, force, onState)
            } catch (e: CancellationException) {
                // 取消不是失败：半成品留着续传，也不要发 Failed 让界面切成「下载失败」
                FileLogger.i(TAG, "端侧模型下载被取消：${spec.id}")
                throw e
            } catch (e: Exception) {
                FileLogger.e(TAG, "端侧模型安装失败：${spec.displayName}", e)
                onState(
                    LocalModelState.Failed(
                        spec.id,
                        e.message ?: context.getString(R.string.settings_local_model_failed),
                    )
                )
                throw e
            } finally {
                if (activeJob === job) activeJob = null
            }
        }
    }

    /** 取消正在进行的下载；半成品保留（续传用）。没有下载在跑时什么也不做。 */
    fun cancel() {
        activeJob?.cancel()
        activeJob = null
    }

    /** 删除该模型的全部落盘数据（正式文件 + 半成品 + 空目录）。 */
    fun delete(id: String) {
        val dir = modelDir(id)
        if (dir.exists() && !dir.deleteRecursively()) {
            FileLogger.w(TAG, "删除端侧模型目录失败：$id")
        }
    }

    /** 目录：`filesDir/local_models/<id>/`。 */
    private fun modelDir(id: String): File = File(
        File(context.filesDir, LocalModels.MODEL_ROOT_DIR).apply { mkdirs() },
        id,
    )

    private suspend fun install(
        spec: LocalModelSpec,
        force: Boolean,
        onState: (LocalModelState) -> Unit,
    ): File {
        val target = modelFile(spec.id)
        if (!force && target.isFile && target.length() == spec.sizeBytes) {
            val verified = try { LocalModelFiles.verify(target, spec); true } catch (_: IOException) { false }
            if (verified) {
                onState(LocalModelState.Ready(spec.id))
                return target
            }
        }
        if (!isSupportedOnThisDevice()) {
            throw IOException(context.getString(R.string.settings_local_model_not_supported))
        }
        ensureFreeSpace(spec)

        val dir = modelDir(spec.id)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("无法创建模型目录：${dir.absolutePath}")
        }
        val part = File(dir, "${spec.fileName}.part")
        // 续传时进度不能从 0 开始，否则界面上一看「还在原地」
        onState(
            LocalModelState.Downloading(
                spec.id,
                progressOf(part.takeIf { it.isFile }?.length() ?: 0L, spec.sizeBytes),
            )
        )
        downloader.download(spec, part) { read, total ->
            onState(LocalModelState.Downloading(spec.id, progressOf(read, total)))
        }
        currentCoroutineContext().ensureActive()

        onState(LocalModelState.Verifying(spec.id))
        val actual = downloader.verifySha256(part)
        if (!actual.equals(spec.sha256, ignoreCase = true)) {
            // 半成品已经不可信，留着只会污染下一次续传
            part.delete()
            throw IOException(context.getString(R.string.settings_local_model_hash_mismatch))
        }
        currentCoroutineContext().ensureActive()
        java.nio.file.Files.move(part.toPath(), target.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        FileLogger.i(TAG, "端侧模型已就绪：${spec.id}（${formatBytes(spec.sizeBytes)}）")
        onState(LocalModelState.Ready(spec.id))
        return target
    }

    /** 空间预检：留 20% 余量，别下到 99% 才发现写不进去。 */
    private fun ensureFreeSpace(spec: LocalModelSpec) {
        val required = (spec.sizeBytes * DISK_HEADROOM).toLong()
        val available = StatFs(context.filesDir.path).availableBytes
        if (available < required) {
            throw IOException(
                context.getString(
                    R.string.settings_local_model_disk_low,
                    formatBytes(spec.sizeBytes),
                )
            )
        }
    }

    private fun progressOf(done: Long, total: Long): Float =
        if (total <= 0L) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

    /** 体积可读化：MB / GB 一位小数（失败提示里要用）。 */
    private fun formatBytes(bytes: Long): String = when {
        bytes >= GIB -> String.format(Locale.US, "%.1f GB", bytes / GIB.toDouble())
        bytes >= MIB -> String.format(Locale.US, "%.1f MB", bytes / MIB.toDouble())
        else -> String.format(Locale.US, "%.1f KB", bytes / KIB.toDouble())
    }

    private companion object {
        const val TAG = "LocalModelManager"

        /** 空间余量系数：权重之外还要留给 .part→正式文件那一刻的峰值与日志。 */
        const val DISK_HEADROOM = 1.2

        const val KIB = 1024L
        const val MIB = 1024L * 1024L
        const val GIB = 1024L * 1024L * 1024L
    }
}
