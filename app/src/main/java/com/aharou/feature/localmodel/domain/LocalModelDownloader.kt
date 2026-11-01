package com.aharou.feature.localmodel.domain

import android.content.Context
import com.aharou.core.net.AppProxy
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 端侧模型权重的下载器：只管「把字节拉到一个文件里」，不做校验、不做落盘。
 *
 * 拆分理由：断点续传 + 多源回落 + 取消要立刻生效，这几件事凑在一个函数里就已经够长了，
 * 再混进 sha256 与原子替换会看不清哪一步出错。校验与安装交给 [LocalModelManager]。
 *
 * 续传约定：调用方传进来的 `partFile` 就是现场——
 * - 已存在则带 `Range: bytes=<len>-` 请求，服务端回 206 就继续追加；
 * - 回 200 说明这个源不支持 Range（或 URL 被重定向掉了），截断重写，避免半成品叠在旧数据后面；
 * - 回 416 说明服务端认为区间已满足，视为下完，交给上层按字节数核对。
 */
class LocalModelDownloader @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /**
     * 走 [AppProxy] 的代理认证器，与容器镜像、语音模型下载一致——用户配了代理就该用上，
     * 不然 ModelScope 在部分网络下连不上。
     */
    private val client by lazy {
        OkHttpClient.Builder()
            .proxyAuthenticator(AppProxy.okHttpAuthenticator)
            .build()
    }

    /**
     * 把 [spec] 下载到 [partFile]，支持断点续传，返回同一个 [partFile]。
     *
     * 不负责改名或校验：成功返回时只保证 `partFile.length() == spec.sizeBytes`。
     * 失败时会保留半成品（那正是续传的价值），只有服务端明确不给续传时才截断重写。
     *
     * @param onProgress 已写入的总字节数与目标字节数（含续传起点），用于算百分比
     * @throws IOException 全部下载源都失败，或下完但字节数对不上
     */
    suspend fun download(
        spec: LocalModelSpec,
        partFile: File,
        onProgress: (read: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        var lastError: IOException? = null
        for (url in spec.urls) {
            currentCoroutineContext().ensureActive()
            try {
                fetch(url, spec, partFile, onProgress)
                lastError = null
                break
            } catch (e: CancellationException) {
                // 取消要原样抛：半成品留着，下次从断点续
                throw e
            } catch (e: IOException) {
                FileLogger.w(TAG, "模型下载源失败，换下一个：$url", e)
                lastError = e
            }
        }
        if (lastError != null || !partFile.isFile) {
            throw IOException(
                "模型下载失败：${spec.displayName}（${lastError?.message ?: "无可用下载源"}）",
                lastError,
            )
        }
        val actual = partFile.length()
        if (actual != spec.sizeBytes) {
            throw IOException(
                "模型下载不完整：${spec.displayName}，期望 ${spec.sizeBytes} 字节，实际 $actual 字节",
            )
        }
        partFile
    }

    /** 从单个地址拉 [spec] 到 [partFile]，按 [partFile] 现有长度续传。 */
    private suspend fun fetch(
        url: String,
        spec: LocalModelSpec,
        partFile: File,
        onProgress: (read: Long, total: Long) -> Unit,
    ) {
        var offset = if (partFile.isFile) partFile.length() else 0L
        val request = Request.Builder()
            .url(url)
            .apply { if (offset > 0L) header("Range", "bytes=$offset-") }
            .build()
        val call = client.newCall(request)
        val response = suspendCancellableCoroutine<Response> { cont ->
            // 取消时同步 cancel 底层 call，别让连接继续读几百 MB
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
        response.use { res ->
            when (res.code) {
                HTTP_PARTIAL_CONTENT -> Unit
                HTTP_OK -> {
                    // 服务端没理会 Range：从头写，否则整包会追加在旧半成品后面
                    offset = 0L
                }
                HTTP_RANGE_NOT_SATISFIABLE -> {
                    // 服务端认为已经下完了，交给上层按字节数核对
                    FileLogger.i(TAG, "续传区间已满足，按已下完处理：${spec.id}")
                    onProgress(partFile.length(), spec.sizeBytes)
                    return
                }
                else -> if (!res.isSuccessful) {
                    throw IOException("HTTP ${res.code}")
                }
            }
            val body = res.body ?: throw IOException("响应体为空")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var written = offset
            onProgress(written, spec.sizeBytes)
            body.byteStream().use { input ->
                FileOutputStream(partFile, offset > 0L).use { output ->
                    while (true) {
                        // 每块都查一次取消：写完整个文件才响应取消，用户会以为按钮坏了
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        written += count
                        onProgress(written, spec.sizeBytes)
                    }
                    output.flush()
                }
            }
        }
    }

    /**
     * 流式算 sha256，返回小写十六进制。
     *
     * 8KB 缓冲逐块喂给 [MessageDigest]：1.5GB 的权重一次读进内存必然 OOM。
     */
    fun verifySha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }

    private companion object {
        const val TAG = "LocalModelDownloader"

        /** 206 Partial Content：续传命中。 */
        const val HTTP_PARTIAL_CONTENT = 206

        /** 200 OK：服务端整包返回，忽略 Range。 */
        const val HTTP_OK = 200

        /** 416 Range Not Satisfiable：请求的起点已经越过文件末尾。 */
        const val HTTP_RANGE_NOT_SATISFIABLE = 416

        /** 算 sha256 的读缓冲。 */
        const val HASH_BUFFER_SIZE = 8 * 1024
    }
}
