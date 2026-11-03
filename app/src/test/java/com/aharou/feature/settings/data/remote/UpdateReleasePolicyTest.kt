package com.aharou.feature.settings.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateReleasePolicyTest {
    private val installed = "abcdef0123456789abcdef0123456789abcdef01"
    private fun release(tag: String, pre: Boolean = false, asset: String? = "Aharou-arm64.apk") =
        UpdateReleasePolicy.Candidate(tag, pre, asset)
    private fun select(vararg releases: UpdateReleasePolicy.Candidate, current: String = "2.51.0-fork.3", latest: Boolean = false, commit: String = installed) =
        UpdateReleasePolicy.select(releases.toList(), current, commit, latest)

    @Test fun stableMustBeStrictlyHigher() {
        assertTrue(select(release("v2.51.0-fork.3"), release("v2.50.0"), release("v2.51.0-fork.2")).isEmpty())
        assertEquals(listOf("v2.52.0", "v2.51.0-fork.4"),
            select(release("v2.51.0-fork.4"), release("v2.52.0")).map { it.tag })
    }
    @Test fun unknownInstalledVersionDoesNotInventUpdate() {
        listOf("unknown", "", "beta-latest", "garbage", "2.x.0").forEach {
            assertTrue(select(release("v99.0.0"), current = it).isEmpty())
        }
    }
    @Test fun malformedReleaseIsNotANewStableVersion() {
        assertTrue(select(release("unknown"), release("v999oops"), release("beta-latest")).isEmpty())
    }
    @Test fun stableChannelExcludesPrereleases() {
        assertTrue(select(release("v99.0.0-rc1", true), release("beta-latest", true)).isEmpty())
    }
    @Test fun noArm64AssetDoesNotAdvertiseUpdate() {
        assertTrue(select(release("v99.0.0", asset = null)).isEmpty())
    }
    @Test fun sameInstalledBetaIsUpToDateEvenWithHistoricalRc() {
        assertTrue(select(release("beta-latest", true, "Aharou-arm64-beta-abcdef0.apk"),
            release("v1.0.0-rc1", true), latest = true).isEmpty())
    }
    @Test fun newAssetUnderSameBetaTagIsNewBuild() {
        assertEquals("beta-latest", select(release("beta-latest", true, "Aharou-arm64-beta-1234567.apk"), latest = true).single().tag)
    }
    @Test fun betaRequiresRealInstalledCommitAndRecognizedAsset() {
        assertTrue(select(release("beta-latest", true, "Aharou-arm64-beta-1234567.apk"), latest = true, commit = "").isEmpty())
        assertTrue(select(release("beta-latest", true, "Aharou-arm64.apk"), latest = true).isEmpty())
    }
    @Test fun betaDoesNotGuessInstalledCommitFromVersionMetadata() {
        assertEquals(1, select(release("beta-latest", true, "Aharou-arm64-beta-1234567.apk"),
            current = "2.51.0-dev.1+1234567", latest = true).size)
    }
    @Test fun oldRcAndUnversionedPrereleasesCannotReplaceNewFork() {
        assertTrue(select(release("v2.50.0-rc9", true), release("v2.51.0-rc99", true),
            release("random", true), release("v3.0.0-dev.1", true), latest = true).isEmpty())
    }
    @Test fun sameBaseRcCannotReplaceStable() {
        assertTrue(select(release("v2.51.0-rc99", true), current = "2.51.0", latest = true).isEmpty())
    }
    @Test fun rcOrderingIsNumericAndNotApiOrder() {
        assertEquals("v2.52.0-rc10", select(release("v2.52.0-rc9", true),
            release("v2.52.0-rc10", true), latest = true).single().tag)
        assertTrue(select(release("v2.52.0-rc10", true), current = "2.52.0-rc10", latest = true).isEmpty())
    }
    @Test fun notificationIdentityTracksAssetAndDigestWithoutChangingTag() {
        val old = UpdateReleasePolicy.notificationIdentity("beta-latest", "old.apk", "sha1")
        assertEquals(old, UpdateReleasePolicy.notificationIdentity("beta-latest", "old.apk", "sha1"))
        assertNotEquals(old, UpdateReleasePolicy.notificationIdentity("beta-latest", "new.apk", "sha1"))
        assertNotEquals(old, UpdateReleasePolicy.notificationIdentity("beta-latest", "old.apk", "sha2"))
    }
}
