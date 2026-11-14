package com.aharou.core.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlSafetyPolicyTest {
    @Test fun uniqueLocalIpv6IsBlocked() {
        listOf("fc00::1", "fd00::1", "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", "[FD12::1]")
            .forEach { assertTrue(it, UrlSafetyPolicy.isBlockedHost(it)) }
    }

    @Test fun publicIpv6AndExistingPrivateRangesKeepTheirPolicy() {
        listOf("2001:4860:4860::8888", "fbff::1", "8.8.8.8")
            .forEach { assertFalse(it, UrlSafetyPolicy.isBlockedHost(it)) }
        listOf("127.0.0.1", "10.1.2.3", "::1", "fe80::1", "::ffff:127.0.0.1")
            .forEach { assertTrue(it, UrlSafetyPolicy.isBlockedHost(it)) }
    }
}
