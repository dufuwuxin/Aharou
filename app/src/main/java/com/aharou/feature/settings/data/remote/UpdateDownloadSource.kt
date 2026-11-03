package com.aharou.feature.settings.data.remote

/**
 * 更新包下载源解析：把 tag + 资产名展开成**有序候选 URL**。
 *
 * 仅使用 Fork 的 GitHub 发布源（GitHub 文件反代），最后回退 GitHub 原链；
 * GitCode 已于 2026-10-08 按主人要求停用（同步也停了，那边不会再更新，不再作候选）。
 * 调用方逐个探测，第一个可达的即用于下载——任一来源失效都会自动降级，
 * 不需要联网失败重试逻辑。
 *
 * 纯字符串运算，无 Android 依赖，便于单测。
 */
object UpdateDownloadSource {

    const val GITHUB_OWNER = "dufuwuxin"
    const val GITHUB_REPO = "Aharou"

    const val GITHUB_RELEASES_API = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases"

    private const val GITHUB_BASE = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO"

    /**
     * GitHub 文件反代，用法是「前缀 + 完整 GitHub URL」。
     *
     * 顺序按 2026-09-27 本机实测排：gh-proxy.com ≈ 4 MB/s、v6.gh-proxy.org ≈ 1 MB/s、
     * ghfast.top 仅 ≈ 0.04 MB/s（与直连 GitHub 相当，已接近不可用，故放最后兜底）。
     */
    private val PROXIES = listOf(
        "https://gh-proxy.com/",
        "https://v6.gh-proxy.org/",
        "https://ghfast.top/",
    )

    /**
     * 从 release 的资产名里挑要下载的 APK。
     *
     * 仅支持 ARM64 设备，优先选 `Aharou-arm64-*`；universal 与无架构标识的包名仅作旧版本兜底。
     * 其他架构的包不选，避免下载无法安装的更新。
     */
    fun pickApkAsset(names: List<String>, supportsArm64: Boolean = true): String? {
        if (!supportsArm64) return null
        val apks = names.filter { it.endsWith(".apk", ignoreCase = true) }
        apks.firstOrNull { it.contains("arm64", ignoreCase = true) }?.let { return it }
        apks.firstOrNull { it.contains("universal", ignoreCase = true) }?.let { return it }
        if (apks.any { it.contains("x86", ignoreCase = true) || it.contains("armeabi", ignoreCase = true) }) return null
        // 没有架构标识的旧包名（如 Aharou-1.13.4.apk）：没有更可靠的判据，当作通用包
        return apks.firstOrNull()
    }

    /** 供下载的候选地址，按优先级从高到低。 */
    fun candidates(tag: String, assetName: String): List<String> {
        val github = "$GITHUB_BASE/releases/download/$tag/$assetName"
        return buildList {
            PROXIES.forEach { add(it + github) }
            add(github)
        }
    }

    /** release 页面地址：所有来源都失败时的兜底出口。 */
    fun releasePage(tag: String?): String =
        if (tag.isNullOrBlank()) "$GITHUB_BASE/releases/latest" else "$GITHUB_BASE/releases/tag/$tag"
}
