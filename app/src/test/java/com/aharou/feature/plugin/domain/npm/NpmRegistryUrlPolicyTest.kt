package com.aharou.feature.plugin.domain.npm

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class NpmRegistryUrlPolicyTest {
    @Test fun scopedNameIsEncodedAsOneSegment() {
        val url = NpmRegistryUrlPolicy.metadata("@scope/package", "1.2.3-beta.1+build.2")
        assertEquals("https://registry.npmjs.org/@scope%2Fpackage/1.2.3-beta.1+build.2", url.toString())
        assertEquals(listOf("@scope/package", "1.2.3-beta.1+build.2"), url.pathSegments)
    }

    @Test fun searchEncodesTextWithoutAllowingQueryInjection() {
        val url = NpmRegistryUrlPolicy.search("hello &size=999 中文", 40, 20)
        assertEquals("registry.npmjs.org", url.host)
        assertEquals("/-/v1/search", url.encodedPath)
        assertEquals("hello &size=999 中文", url.queryParameter("text"))
        assertEquals(listOf("20"), url.queryParameterValues("size"))
        assertEquals("40", url.queryParameter("from"))
    }

    @Test fun searchBoundsAreInclusive() {
        NpmRegistryUrlPolicy.search("q".repeat(200), 1000, 50)
        NpmRegistryUrlPolicy.search("q", 0, 1)
        for (query in listOf("", "  ", "q".repeat(201), "q\n", "q\u0000")) {
            invalid { NpmRegistryUrlPolicy.search(query) }
        }
        for ((from, size) in listOf(-1 to 20, 1001 to 20, 0 to 0, 0 to 51)) {
            invalid { NpmRegistryUrlPolicy.search("q", from, size) }
        }
    }

    @Test fun packageNamesRejectUrlsTraversalAndPathInjection() {
        for (name in listOf("", "../pkg", ".", "..", "pkg/sub", "@scope", "@/pkg", "@s/p/x",
            "https://evil.test/pkg", "pkg%2fother", "pkg?x=1", "pkg#hash", "UPPER", "a".repeat(215))) {
            invalid { NpmRegistryUrlPolicy.metadata(name, "1.0.0") }
        }
        NpmRegistryUrlPolicy.metadata("a".repeat(214), "1.0.0")
        NpmRegistryUrlPolicy.metadata("@scope/pkg-name_2.js", "0.0.0")
    }

    @Test fun onlyExactSemverIsAccepted() {
        for (version in listOf("latest", "next", "*", "^1.0.0", "~1.0.0", "1", "1.0", "v1.0.0",
            "01.0.0", "1.0.0-01", "1.0.0/../evil", "1.0.0?x=1", "1.0.0-", "1.0.0+", "a".repeat(129))) {
            invalid { NpmRegistryUrlPolicy.metadata("pkg", version) }
        }
        NpmRegistryUrlPolicy.metadata("pkg", "1.0.0-0.alpha-1+001")
    }

    @Test fun originPolicyRejectsCredentialsAlternateHostsAndPorts() {
        assertTrue(NpmRegistryUrlPolicy.isRegistry("https://registry.npmjs.org/pkg/1.0.0".toHttpUrl()))
        for (url in listOf("http://registry.npmjs.org/pkg", "https://registry.npmjs.org.evil.test/pkg",
            "https://registry.npmjs.org:444/pkg", "https://user:pass@registry.npmjs.org/pkg",
            "https://evil.test/pkg")) {
            assertFalse(url, NpmRegistryUrlPolicy.isRegistry(url.toHttpUrl()))
        }
    }

    private fun invalid(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid_input")
        } catch (e: NpmRegistryException) {
            assertEquals(NpmRegistryError.INVALID_INPUT, e.error)
        }
    }
}
