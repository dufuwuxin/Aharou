package com.aharou.feature.settings.data.remote

import android.content.Context
import android.os.Build
import com.aharou.BuildConfig
import com.aharou.R
import com.aharou.feature.settings.data.repository.UpdateChannel
import com.aharou.feature.settings.presentation.component.parseVersionTag
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/** 单个版本的更新日志。 */
data class VersionUpdate(
    val tag: String,
    val changelog: String
)

/** 拉取到的更新信息：最新版本号 + 从当前版本到最新的更新日志 + 可下载的安装包资产名。 */
data class UpdateInfo(
    val latestTag: String,
    val changelog: String,
    val updates: List<VersionUpdate>,
    /** 最新版里挑出的 APK 资产名（无 apk 资产时为 null）。 */
    val apkAssetName: String? = null,
    /** 该资产的字节数，用于校验下载完整性；0 表示未知。 */
    val apkAssetSize: Long = 0L,
    /** 该资产的 sha256（已剥掉 `sha256:` 前缀）；null 表示来源不给，跳过校验。 */
    val apkAssetDigest: String? = null
)

/** 检查更新结果。 */
sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class NewVersion(val info: UpdateInfo) : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

/**
 * 拉取版本信息并生成更新日志。
 *
 * 仅使用 Fork 的 GitHub Releases；反代只转发同一 GitHub API，禁止上游 CNB 混入。
 *
 * 两个通道各收各的：正式版通道只出正式版（且需严格高于当前版本），测试版通道只出预发布
 * （beta 与 RC）。更新日志按版本从高到低拼接（版本号 + 正文，轻量清理 markdown 标题/粗体）。
 * 结果不落盘，由调用方按需展示。
 */
@Singleton
class UpdateCheckService @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    suspend fun checkForUpdate(currentVersion: String, channel: UpdateChannel): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            // 正式版发两个单架构包，按设备架构挑对应的那个（装错架构的包起不来）
            val supportsArm64 = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
            runCatching {
                val releases = fetchGithubProxiedReleases()?.takeIf { it.isNotEmpty() }
                    ?: fetchGithubReleases()?.takeIf { it.isNotEmpty() }
                    ?: return@withContext UpdateCheckResult.Error(
                        context.getString(R.string.about_network_error)
                    )

                val selected = UpdateReleasePolicy.select(
                    releases.map {
                        UpdateReleasePolicy.Candidate(
                            it.rawTag, it.prerelease,
                            UpdateDownloadSource.pickApkAsset(it.assets.map { a -> a.name }, supportsArm64)
                        )
                    }, currentVersion, BuildConfig.GIT_COMMIT, channel == UpdateChannel.LATEST
                )
                val updates = selected.mapNotNull { candidate -> releases.firstOrNull { it.rawTag == candidate.tag } }

                if (updates.isEmpty()) {
                    UpdateCheckResult.UpToDate
                } else {
                    val versionUpdates = updates.map { toVersionUpdate(it) }
                    val latest = updates.first()
                    val apkAsset = latest.assets.firstOrNull {
                        it.name == UpdateDownloadSource.pickApkAsset(latest.assets.map { a -> a.name }, supportsArm64)
                    }
                    UpdateCheckResult.NewVersion(
                        UpdateInfo(
                            latestTag = versionUpdates.first().tag,
                            changelog = versionUpdates.joinToString("\n\n") { "${it.tag}\n${it.changelog}" },
                            updates = versionUpdates,
                            apkAssetName = apkAsset?.name,
                            apkAssetSize = apkAsset?.size ?: 0L,
                            apkAssetDigest = apkAsset?.digest
                        )
                    )
                }
            }.getOrElse { UpdateCheckResult.Error(it.message ?: context.getString(R.string.about_network_error)) }
        }

    /** 二级源：反代转发的 GitHub API——数据与直连同源，用于直连被限速（403 / 429）时兜底。 */
    private fun fetchGithubProxiedReleases(): List<ReleaseInfo>? = runCatching {
        val req = Request.Builder()
            .url(GITHUB_PROXY_API + GITHUB_RELEASES_API + "?per_page=50")
            .header("Accept", "application/vnd.github+json")
            .build()
        SHARED_CLIENT.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            parseReleases(resp.body?.string()) { githubRelease(it) }
        }
    }.getOrNull()

    /**
     * 末位兜底：GitHub Releases API 直连。
     *
     * 拿不到（网络不通、限流、解析失败）返回 null，由调用方换源；不在这里区分失败原因——
     * 所有源都失败时对用户来说都是「网络不通」。
     */
    private fun fetchGithubReleases(): List<ReleaseInfo>? = runCatching {
        val req = Request.Builder()
            .url("$GITHUB_RELEASES_API?per_page=50")
            .header("Accept", "application/vnd.github+json")
            .build()
        SHARED_CLIENT.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            parseReleases(resp.body?.string()) { githubRelease(it) }
        }
    }.getOrNull()

    /** 把响应体解析成 release 列表；不是 JSON 数组或整体解析失败时返回 null。 */
    private fun parseReleases(body: String?, mapper: (JsonObject) -> ReleaseInfo?): List<ReleaseInfo>? =
        runCatching { JsonParser.parseString(body.orEmpty()).asJsonArray }
            .getOrNull()
            ?.mapNotNull { el -> runCatching { mapper(el.asJsonObject) }.getOrNull() }

    private fun githubRelease(obj: JsonObject): ReleaseInfo? {
        val tag = obj.get("tag_name")?.asString ?: return null
        val version = parseVersionTag(tag) ?: return null
        return ReleaseInfo(
            version = version,
            rawTag = tag,
            notes = obj.get("body")?.asString.orEmpty(),
            prerelease = obj.get("prerelease")?.asBoolean ?: false,
            assets = obj.getAsJsonArray("assets")?.mapNotNull { a ->
                val asset = a.asJsonObject
                val name = asset.get("name")?.asString ?: return@mapNotNull null
                ReleaseAsset(
                    name = name,
                    size = asset.get("size")?.asLong ?: 0L,
                    // GitHub 给每个资产带 digest（形如 sha256:xxxx），它来自 GitHub 自己的存储层，
                    // 与反代下载源不同源，所以能当下载完整性校验的信任锚。
                    digest = asset.get("digest")?.asString
                        ?.removePrefix("sha256:")
                        ?.takeIf { it.isNotBlank() }
                )
            } ?: emptyList()
        )
    }

    private fun toVersionUpdate(release: ReleaseInfo): VersionUpdate {
        val notes = release.notes.trim()
        return VersionUpdate(
            tag = release.rawTag,
            changelog = if (notes.isEmpty()) {
                context.getString(R.string.about_no_changelog)
            } else {
                cleanMarkdown(notes)
            }
        )
    }

    /** 轻量清理 markdown：去掉行首标题符与粗体标记，并删除 GitHub 自动生成的标题/链接行，保留纯文本便于直接阅读。 */
    private fun cleanMarkdown(text: String): String = text
        .lines()
        .map { it.trimEnd().replace("**", "") }
        .map { it.replace(Regex("^#{1,6}\\s+"), "") }
        .filterNot { line ->
            val t = line.trim()
            t.startsWith("What's Changed") ||
                t.startsWith("What's New") ||
                t.startsWith("Full Changelog:")
        }
        .joinToString("\n")

    private data class ReleaseInfo(
        val version: String,
        val rawTag: String,
        val notes: String,
        val prerelease: Boolean,
        val assets: List<ReleaseAsset> = emptyList()
    )

    /** release 里的单个资产（只关心发布包本身）。 */
    private data class ReleaseAsset(val name: String, val size: Long, val digest: String? = null)

    private companion object {
        const val GITHUB_RELEASES_API = UpdateDownloadSource.GITHUB_RELEASES_API

        /** GitHub API 反代前缀：直连被限速时用它拿同一份数据。 */
        const val GITHUB_PROXY_API = "https://gh-proxy.com/"

        val SHARED_CLIENT by lazy {
            okhttp3.OkHttpClient.Builder()
                .proxyAuthenticator(com.aharou.core.net.AppProxy.okHttpAuthenticator)
                .build()
        }
    }
}
