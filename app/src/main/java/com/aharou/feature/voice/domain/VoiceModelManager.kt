package com.aharou.feature.voice.domain

import android.content.Context
import com.aharou.R
import com.aharou.core.net.AppProxy
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/** 模型准备进度。 */
sealed interface VoiceModelState {
    data object Idle : VoiceModelState

    /** 下载中；[progress] 为 0f~1f，服务端没给总长度时恒为 0f。 */
    data class Downloading(val progress: Float) : VoiceModelState

    data object Extracting : VoiceModelState
    data object Ready : VoiceModelState
    data class Failed(val message: String) : VoiceModelState
}

/** 单个必需文件的状态。[actual] 为 null 表示文件不存在。 */
data class VoiceModelFileStatus(val name: String, val expected: Long, val actual: Long?) {
    val ok: Boolean get() = actual != null && actual == expected
}

/** 模型当前状态：哪个文件缺了、哪个字节数不对——设置页照这个显示，不用靠猜。 */
data class VoiceModelStatus(val files: List<VoiceModelFileStatus>) {
    val ready: Boolean get() = files.all { it.ok }

    /** 一个文件都没有：从未下载过（与「下载过但残缺」区分开，设置页文案不同）。 */
    val neverDownloaded: Boolean get() = files.all { it.actual == null }
}

/**
 * 离线语音模型的下载、解压与就绪判定。
 *
 * 目录布局：`filesDir/voice_models/<spec.dirName>/`，就绪判据是 [VoiceModelSpec.requiredFiles]
 * 全部存在**且字节数对得上**（见 [VoiceModelSpec.fileSizes]）——不写「已下载」标记文件，
 * 避免解压中断后标记与实体不一致。下载与解压都走临时目录，成功才落成正式目录，中途失败删干净。
 *
 * 压缩包只在解压期间占盘，随后即删；只拷 [VoiceModelSpec.requiredFiles] 列出的文件。
 */
@Singleton
class VoiceModelManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** 同一模型的并发准备串行化：两处同时触发下载会互相覆盖临时目录。 */
    private val mutex = Mutex()

    private val client by lazy {
        OkHttpClient.Builder()
            .proxyAuthenticator(AppProxy.okHttpAuthenticator)
            .build()
    }

    fun modelRoot(): File = File(context.filesDir, VoiceModels.MODEL_ROOT_DIR).apply { mkdirs() }

    fun modelDir(spec: VoiceModelSpec): File = File(modelRoot(), spec.dirName)

    /** 模型是否已就绪（运行所需文件齐全**且字节数对得上**）。 */
    fun isReady(spec: VoiceModelSpec): Boolean = status(spec).ready

    /** 当前模型状态：逐个文件核「在不在 + 字节数对不对」。 */
    fun status(spec: VoiceModelSpec): VoiceModelStatus {
        val dir = modelDir(spec)
        return VoiceModelStatus(
            spec.requiredFiles.map { name ->
                val file = File(dir, name)
                VoiceModelFileStatus(
                    name = name,
                    expected = spec.fileSizes[name] ?: 0L,
                    actual = file.takeIf { it.isFile }?.length(),
                )
            }
        )
    }

    /** 强制重新下载；新文件校验通过之前保留现有模型。 */
    suspend fun redownload(
        spec: VoiceModelSpec,
        onState: (VoiceModelState) -> Unit = {},
    ): File = prepareModel(spec, allowDownload = true, forceDownload = true, onState = onState)

    /** 全部模型的聚合状态（设置页那一行照它显示）。 */
    fun statusAll(): VoiceModelStatus = VoiceModelStatus(VoiceModels.ALL.flatMap { status(it).files })

    /** 已就绪的模型命中本地；重试只补齐缺失或损坏的模型。 */
    suspend fun ensureAll(onState: (VoiceModelState) -> Unit = {}): Boolean =
        downloadAll(forceDownload = false, onState = onState)

    /** 依次重新下载全部模型；保留失败原因，取消时立即终止后续模型。 */
    suspend fun redownloadAll(onState: (VoiceModelState) -> Unit = {}): Boolean =
        downloadAll(forceDownload = true, onState = onState)

    private suspend fun downloadAll(
        forceDownload: Boolean,
        onState: (VoiceModelState) -> Unit,
    ): Boolean {
        val failures = mutableListOf<String>()
        VoiceModels.ALL.forEach { spec ->
            currentCoroutineContext().ensureActive()
            try {
                prepareModel(spec, allowDownload = true, forceDownload = forceDownload) { state ->
                    // 单个模型就绪不代表整批安装结束，避免设置页提前解除下载锁。
                    if (state is VoiceModelState.Downloading || state is VoiceModelState.Extracting) {
                        onState(state)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += e.message ?: context.getString(R.string.settings_voice_model_download_failed)
            }
        }
        val ready = failures.isEmpty() && statusAll().ready
        onState(
            if (ready) VoiceModelState.Ready
            else VoiceModelState.Failed(failures.joinToString("\n"))
        )
        return ready
    }

    /**
     * 确保模型可用，返回模型目录。已就绪时直接返回。
     *
     * 未就绪时，只有 [allowDownload] 为 true 才会**联网下载**：从 [VoiceModelSpec.url] 拉 tar.bz2，
     * 只解出运行所需的文件，解压完把压缩包删掉。首次使用某个模型会有一次几百 MB 的下载，之后一直
     * 命中本地；[allowDownload] 为 false 时直接抛异常，由调用方提示用户去设置页手动下载。
     *
     * @throws IOException 下载失败、解压出的文件不完整，或 [allowDownload] 为 false 且模型未就绪
     */
    suspend fun ensureModel(
        spec: VoiceModelSpec,
        allowDownload: Boolean = true,
        onState: (VoiceModelState) -> Unit = {},
    ): File = prepareModel(spec, allowDownload, forceDownload = false, onState = onState)

    private suspend fun prepareModel(
        spec: VoiceModelSpec,
        allowDownload: Boolean,
        forceDownload: Boolean,
        onState: (VoiceModelState) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        mutex.withLock {
            val dir = modelDir(spec)
            val backup = File(modelRoot(), "${spec.dirName}.backup")
            // 进程可能在两次 rename 之间退出；先恢复上一份，再判断是否需要联网。
            if (backup.exists()) {
                if (isReady(spec)) {
                    backup.deleteRecursively()
                } else {
                    if (dir.exists()) dir.deleteRecursively()
                    if (!backup.renameTo(dir)) {
                        throw IOException(context.getString(R.string.settings_voice_model_install_failed))
                    }
                }
            }
            if (!forceDownload && isReady(spec)) {
                onState(VoiceModelState.Ready)
                return@withLock dir
            }
            if (!allowDownload) {
                // 调用方（语音会话）明确不下载：没下过就当不可用，让上层提示去设置页手动下，
                // 不在用户点麦克风时背地里拉几百 MB。
                throw IOException(context.getString(R.string.settings_voice_model_not_downloaded))
            }
            val staging = File(modelRoot(), "${spec.dirName}.staging")
            val archive = File(modelRoot(), "${spec.dirName}.tar.bz2")
            try {
                staging.deleteRecursively()
                if (!staging.mkdirs() && !staging.isDirectory) {
                    throw IOException(context.getString(R.string.settings_voice_model_install_failed))
                }
                onState(VoiceModelState.Downloading(0f))
                downloadArchive(spec, archive) { progress ->
                    onState(VoiceModelState.Downloading(progress))
                }
                onState(VoiceModelState.Extracting)
                extractArchive(spec, archive, staging)
                currentCoroutineContext().ensureActive()
                if (dir.exists() && !dir.renameTo(backup)) {
                    throw IOException(context.getString(R.string.settings_voice_model_install_failed))
                }
                if (!staging.renameTo(dir)) {
                    if (backup.exists()) backup.renameTo(dir)
                    throw IOException(context.getString(R.string.settings_voice_model_install_failed))
                }
                if (!isReady(spec)) {
                    dir.deleteRecursively()
                    if (backup.exists()) backup.renameTo(dir)
                    throw IOException(context.getString(R.string.settings_voice_model_invalid_archive))
                }
                backup.deleteRecursively()
                onState(VoiceModelState.Ready)
                dir
            } catch (e: Exception) {
                // CancellationException 也走这里：半成品必须清掉，否则下次启动会认为已就绪
                staging.deleteRecursively()
                if (!dir.exists() && backup.exists()) backup.renameTo(dir)
                if (e !is CancellationException) {
                    FileLogger.e(TAG, "下载语音模型失败：${spec.displayName}", e)
                    onState(VoiceModelState.Failed(
                        e.message ?: context.getString(R.string.settings_voice_model_download_failed)
                    ))
                }
                throw e
            } finally {
                archive.delete()
            }
        }
    }

    /**
     * 下载压缩包到 [dest]：按 [VoiceModelSpec.urls] 的顺序逐个尝试，前一个失败才换下一个。
     *
     * 失败的源只清掉半成品重来——包最大 240MB，换源续传要额外保证分片一致，而候选源本来就少，
     * 重下更简单。
     */
    private suspend fun downloadArchive(
        spec: VoiceModelSpec,
        dest: File,
        onProgress: (Float) -> Unit,
    ) {
        var lastError: Exception? = null
        for (url in spec.urls) {
            try {
                fetchArchive(url, dest, onProgress)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dest.delete()
                FileLogger.w(TAG, "下载源失败，换下一个：$url", e)
                lastError = e
            }
        }
        throw IOException(lastError?.message ?: context.getString(R.string.settings_voice_model_download_failed))
    }

    /**
     * 从单个地址拉压缩包。
     *
     * 走 [AppProxy] 的代理认证器，与容器镜像下载一致——国内直连 GitHub 不稳，用户配了代理就该用上。
     * 协程取消时同步 cancel 底层 call，半成品文件一并删掉。
     */
    private suspend fun fetchArchive(
        url: String,
        dest: File,
        onProgress: (Float) -> Unit,
    ) {
        val call = client.newCall(Request.Builder().url(url).build())
        val response = suspendCancellableCoroutine<Response> { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isCancelled) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    cont.resume(response) { _, unusedResponse, _ -> unusedResponse.close() }
                }
            })
        }
        // 写文件留在持锁协程内，取消后的网络回调不能再覆盖下一次安装的临时包。
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
            val body = it.body ?: throw IOException(
                context.getString(R.string.settings_voice_model_invalid_archive)
            )
            val total = body.contentLength()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read = 0L
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        read += n
                        if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (total >= 0 && read != total) {
                throw IOException(context.getString(R.string.settings_voice_model_invalid_archive))
            }
        }
    }

    /**
     * 从 tar.bz2 压缩包里挑出 [VoiceModelSpec.requiredFiles] 写进 [destDir]，缺一个就报错。
     *
     * 只按 basename 匹配：包内是 `<模型目录>/<文件名>` 的单层结构，取 basename 顺带挡掉
     * 压缩包里可能出现的路径穿越（`../`）。
     */
    private suspend fun extractArchive(spec: VoiceModelSpec, archive: File, destDir: File) {
        val wanted = spec.requiredFiles.toSet()
        val found = mutableSetOf<String>()
        BZip2CompressorInputStream(BufferedInputStream(archive.inputStream())).use { bz ->
            TarArchiveInputStream(bz).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    currentCoroutineContext().ensureActive()
                    val name = entry.name.substringAfterLast('/')
                    if (entry.isFile && name in wanted && found.add(name)) {
                        val expected = spec.fileSizes[name]
                        if (expected == null || entry.size != expected) {
                            throw IOException(context.getString(R.string.settings_voice_model_invalid_archive))
                        }
                        val target = File(destDir, name)
                        target.outputStream().use { out ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = tar.read(buffer)
                                if (count < 0) break
                                out.write(buffer, 0, count)
                            }
                        }
                        if (target.length() != expected) {
                            throw IOException(context.getString(R.string.settings_voice_model_invalid_archive))
                        }
                    }
                    entry = tar.nextEntry
                }
            }
        }
        val missing = wanted - found
        if (missing.isNotEmpty()) {
            throw IOException(context.getString(R.string.settings_voice_model_invalid_archive))
        }
    }

    private companion object {
        const val TAG = "VoiceModelManager"
    }
}
