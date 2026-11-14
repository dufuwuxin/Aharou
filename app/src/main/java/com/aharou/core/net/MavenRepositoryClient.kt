package com.aharou.core.net

import android.content.Context
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 从 Maven 仓库拉取 `maven-metadata.xml`。
 *
 * - 地址安全：[UrlSafetyPolicy] 拦截环回/私网（仓库 URL 来自可被 AI 改写的构建脚本，防被当内网探针）。
 * - 网络链：懒建 OkHttp，挂 [AppProxy.okHttpAuthenticator]，与 [RepoDataFetcher] 一致。
 * - 缓存：磁盘 TTL 12 小时（建在 `filesDir/maven_metadata_cache` 下），`refresh=true` 跳过有效期直连。
 * - 失败降级：网络不可用时回退磁盘旧缓存，仍无则返回 [MetadataResult.Failure]。
 */
@Singleton
class MavenRepositoryClient @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    sealed class MetadataResult {
        data class Success(
            val metadata: MavenMetadataParser.Metadata,
            val repository: String,
            val fromCache: Boolean
        ) : MetadataResult()

        /** 仓库可达但没有该坐标（HTTP 404）。 */
        data class NotFound(val repository: String) : MetadataResult()

        data class Failure(val error: String) : MetadataResult()
    }

    suspend fun fetch(
        repoUrl: String,
        group: String,
        artifact: String,
        refresh: Boolean
    ): MetadataResult = withContext(Dispatchers.IO) {
        val base = repoUrl.trim().trimEnd('/')
        if (base.isEmpty()) return@withContext MetadataResult.Failure("空仓库地址")

        val host = hostOf(base)
        if (host != null && UrlSafetyPolicy.isBlockedHost(host)) {
            return@withContext MetadataResult.Failure("拒绝访问内网/本机仓库地址：$host")
        }

        val url = "$base/${group.replace('.', '/')}/$artifact/maven-metadata.xml"
        val cacheFile = cacheFileFor(url)
        val now = System.currentTimeMillis()

        if (!refresh && cacheFile.isFile && now - cacheFile.lastModified() < CACHE_TTL_MS) {
            readCache(cacheFile)?.let { return@withContext MetadataResult.Success(it, base, true) }
        }

        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "aharou-dependency-checker")
                .get()
                .build()
            executeSafeMavenRequest(client, request).use { response ->
                when {
                    response.code == 404 -> MetadataResult.NotFound(base)

                    response.isSuccessful -> {
                        val body = response.body?.string().orEmpty()
                        val parsed = MavenMetadataParser.parse(body)
                        if (parsed == null) {
                            MetadataResult.Failure("元数据解析失败：$url")
                        } else {
                            runCatching {
                                cacheFile.parentFile?.mkdirs()
                                cacheFile.writeText(body)
                            }
                            MetadataResult.Success(parsed, base, false)
                        }
                    }

                    else -> MetadataResult.Failure("HTTP ${response.code}：$url")
                }
            }
        } catch (e: Exception) {
            FileLogger.d(TAG, "拉取失败 $url: ${e.message}")
            readCache(cacheFile)?.let { return@withContext MetadataResult.Success(it, base, true) }
            MetadataResult.Failure(e.message ?: "网络请求失败")
        }
    }

    private fun readCache(file: File): MavenMetadataParser.Metadata? {
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return MavenMetadataParser.parse(text)
    }

    private fun cacheFileFor(url: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) } + ".xml"
        return File(File(context.filesDir, CACHE_DIR_NAME), name)
    }

    private fun hostOf(url: String): String? =
        runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: Regex("^[a-z]+://([^/?#]+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.get(1)?.substringBefore(':')?.takeIf { it.isNotBlank() }

    private companion object {
        const val TAG = "MavenRepositoryClient"
        const val CACHE_DIR_NAME = "maven_metadata_cache"
        const val CACHE_TTL_MS = 12 * 60 * 60 * 1000L

        val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .proxyAuthenticator(AppProxy.okHttpAuthenticator)
                .build()
        }
    }
}

/** 自动跳转必须关闭，否则第二跳会先连接、后校验。 */
internal fun executeSafeMavenRequest(client: OkHttpClient, initial: Request): Response {
    require(!client.followRedirects && !client.followSslRedirects)
    var request = initial
    for (hop in 0..10) {
        if (UrlSafetyPolicy.isBlockedHost(request.url.host)) {
            throw IOException("拒绝访问内网/本机仓库地址：${request.url.host}")
        }
        val response = client.newCall(request).execute()
        if (response.code !in setOf(300, 301, 302, 303, 307, 308)) return response
        val next = response.use {
            if (hop == 10) throw IOException("仓库重定向次数过多")
            val location = it.header("Location") ?: throw IOException("仓库重定向缺少 Location")
            request.url.resolve(location) ?: throw IOException("仓库重定向地址无效")
        }
        request = request.newBuilder().url(next).build()
    }
    throw IOException("仓库重定向次数过多")
}
