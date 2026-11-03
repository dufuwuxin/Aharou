package com.aharou.feature.settings.data.remote

import com.aharou.feature.settings.presentation.component.compareVersions
import com.aharou.feature.settings.presentation.component.parseVersionTag

/** Pure release selection shared by the checker and its offline regression tests. */
internal object UpdateReleasePolicy {
    data class Candidate(val tag: String, val prerelease: Boolean, val apkAssetName: String?)

    private val version = Regex("""\d+\.\d+\.\d+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?(?:\+[0-9A-Za-z.-]+)?""")
    private val rc = Regex("""\d+\.\d+\.\d+-rc\.?\d+(?:\+[0-9A-Za-z.-]+)?""")
    private val betaAsset = Regex("""Aharou-arm64-beta-([0-9a-f]{7})\.apk""")

    fun select(
        releases: List<Candidate>, currentVersion: String, installedCommit: String, latestChannel: Boolean
    ): List<Candidate> {
        if (latestChannel) {
            // A fixed beta tag identifies a moving build, not a semantic version.
            releases.firstOrNull { it.prerelease && it.tag == "beta-latest" }?.let { beta ->
                val sha = betaAsset.matchEntire(beta.apkAssetName.orEmpty())?.groupValues?.get(1)
                    ?: return emptyList()
                if (!installedCommit.matches(Regex("[0-9a-f]{7,40}"))) return emptyList()
                return if (sha == installedCommit.take(7)) emptyList() else listOf(beta)
            }
        }
        val current = parseVersionTag(currentVersion) ?: return emptyList()
        if (!version.matches(current)) return emptyList()
        // Fork's published stable tags carry a fork suffix, not an RC maturity level.
        val comparisonVersion = if (latestChannel && current.contains("-fork.")) current.substringBefore('-') else current
        val newer = releases.filter { release ->
            val candidate = parseVersionTag(release.tag).orEmpty()
            release.apkAssetName != null && release.prerelease == latestChannel &&
                version.matches(candidate) && (!latestChannel || rc.matches(candidate)) &&
                compareVersions(candidate, comparisonVersion) > 0
        }.sortedWith { a, b ->
            compareVersions(parseVersionTag(b.tag).orEmpty(), parseVersionTag(a.tag).orEmpty())
        }
        return if (latestChannel) newer.take(1) else newer
    }

    fun notificationIdentity(tag: String, assetName: String?, digest: String?): String =
        listOf(tag, assetName.orEmpty(), digest.orEmpty()).joinToString("\n")
}
