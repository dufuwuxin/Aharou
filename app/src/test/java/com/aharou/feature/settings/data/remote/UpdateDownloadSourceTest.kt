package com.aharou.feature.settings.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 更新包下载源解析：资产选择与候选地址顺序。 */
class UpdateDownloadSourceTest {

    @Test
    fun pickApkAsset_prefersArm64Package() {
        val names = listOf(
            "Aharou-arm64-v1.13.5.apk",
            "Aharou-universal-v1.13.5.apk",
            "Aharou-universal-v1.13.5-mapping.txt.gz",
        )
        // 优先单架构包，旧通用包仅作兜底。
        assertEquals("Aharou-arm64-v1.13.5.apk", UpdateDownloadSource.pickApkAsset(names))
    }

    @Test
    fun pickApkAsset_nonArm64DeviceRejectsAllPackages() {
        val names = listOf("Aharou-arm64-v1.13.5.apk", "Aharou-x86_64-v1.13.5.apk")
        assertNull(UpdateDownloadSource.pickApkAsset(names, supportsArm64 = false))
        assertNull(UpdateDownloadSource.pickApkAsset(listOf("Aharou-universal.apk"), supportsArm64 = false))
        assertNull(UpdateDownloadSource.pickApkAsset(listOf("Aharou-1.13.4.apk"), supportsArm64 = false))
    }

    @Test
    fun pickApkAsset_skipsOtherArchitectureOnly() {
        // 只有其他架构的包：下下来也装不上，宁可返回 null 不上报更新
        assertNull(UpdateDownloadSource.pickApkAsset(listOf("Aharou-arm64-v1.13.5.apk"), supportsArm64 = false))
        assertNull(UpdateDownloadSource.pickApkAsset(listOf("Aharou-x86_64-v1.13.5.apk"), supportsArm64 = true))
    }

    @Test
    fun pickApkAsset_rejectsArmV7AndX86Packages() {
        assertNull(UpdateDownloadSource.pickApkAsset(listOf("Aharou-armeabi-v7a.apk")))
        assertNull(UpdateDownloadSource.pickApkAsset(listOf("Aharou-x86.apk")))
    }

    @Test
    fun pickApkAsset_fallsBackToUniversal() {
        val names = listOf("Aharou-x86_64-v1.13.5.apk", "Aharou-universal-v1.13.5.apk")
        // universal 兼容所有设备，作为旧版本的兜底
        assertEquals("Aharou-universal-v1.13.5.apk", UpdateDownloadSource.pickApkAsset(names))
    }

    @Test
    fun pickApkAsset_fallsBackToAnyApk() {
        val names = listOf("Aharou-1.13.4.apk", "notes.txt")
        assertEquals("Aharou-1.13.4.apk", UpdateDownloadSource.pickApkAsset(names))
    }

    @Test
    fun pickApkAsset_ignoresNonApkAssets() {
        val names = listOf("Aharou-universal-v1.13.5-mapping.txt.gz", "README.md")
        assertNull(UpdateDownloadSource.pickApkAsset(names))
    }

    @Test
    fun pickApkAsset_x86DeviceReturnsNullWhenNoUsablePackage() {
        // 列表里根本没有可用的 apk
        assertNull(UpdateDownloadSource.pickApkAsset(listOf("README.md"), supportsArm64 = false))
    }

    @Test
    fun pickApkAsset_returnsNullForEmptyList() {
        assertNull(UpdateDownloadSource.pickApkAsset(emptyList()))
    }

    @Test
    fun candidates_areOrderedForkProxiesThenGitHub() {
        val list = UpdateDownloadSource.candidates("v1.13.5", "Aharou-universal-v1.13.5.apk")
        val github = "https://github.com/dufuwuxin/Aharou/releases/download/" +
            "v1.13.5/Aharou-universal-v1.13.5.apk"

        assertTrue(list.none { it.contains("huxiangli") || it.contains("cnb.cool") })
        assertEquals("GitHub 原链应排最后", github, list.last())
        list.dropLast(1).forEach { assertTrue(it.endsWith(github)) }
        assertEquals(list.size, list.distinct().size)
    }

    @Test
    fun candidates_carryTagAndAssetName() {
        val list = UpdateDownloadSource.candidates("v1.13.5", "Aharou-arm64-v1.13.5.apk")
        assertTrue(list.all { it.contains("/v1.13.5/Aharou-arm64-v1.13.5.apk") })
    }

    @Test
    fun apiUsesSameForkAsDownloads() {
        assertEquals("https://api.github.com/repos/dufuwuxin/Aharou/releases", UpdateDownloadSource.GITHUB_RELEASES_API)
    }

    @Test
    fun releasePage_pointsAtTheGivenTag() {
        assertEquals(
            "https://github.com/dufuwuxin/Aharou/releases/tag/v1.13.5",
            UpdateDownloadSource.releasePage("v1.13.5")
        )
        assertEquals(
            "https://github.com/dufuwuxin/Aharou/releases/latest",
            UpdateDownloadSource.releasePage(null)
        )
    }
}
