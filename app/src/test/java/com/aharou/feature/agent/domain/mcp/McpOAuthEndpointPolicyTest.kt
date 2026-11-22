package com.aharou.feature.agent.domain.mcp

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class McpOAuthEndpointPolicyTest {
    @Test fun rejectsPublicHttpAndCredentialBearingEndpoints() {
        assertFalse(isSecureOAuthEndpoint("http://auth.example/token"))
        assertFalse(isSecureOAuthEndpoint("https://user:pass@auth.example/token"))
        assertFalse(isSecureOAuthEndpoint("https://auth.example/token#fragment"))
    }

    @Test fun allowsHttpsAndExplicitLoopbackHttpEndpoints() {
        assertTrue(isSecureOAuthEndpoint("https://auth.example/token"))
        assertTrue(isSecureOAuthEndpoint("http://localhost:8080/token"))
        assertTrue(isSecureOAuthEndpoint("http://127.0.0.1:8080/token"))
        assertTrue(isSecureOAuthEndpoint("http://127.0.0.2:8080/token"))
        assertFalse(isSecureOAuthEndpoint("http://127.attacker.example/token"))
        assertFalse(isSecureOAuthEndpoint("http://127.999.0.1/token"))
    }

    @Test fun duplicateCallbackParametersAreRejected() {
        assertEquals("one", singleOAuthQueryParameter(Uri.parse("aharou-mcp://oauth/callback?code=one"), "code"))
        assertEquals(null, singleOAuthQueryParameter(Uri.parse("aharou-mcp://oauth/callback"), "code"))
        assertEquals(null, singleOAuthQueryParameter(Uri.parse("aharou-mcp://oauth/callback?code=one&code=two"), "code"))
        assertEquals(null, singleOAuthQueryParameter(Uri.parse("aharou-mcp://oauth/callback?error=denied&error=other"), "error"))
    }

    @Test fun requiresPresentMatchingState() {
        assertTrue(matchesOAuthState("expected", "expected"))
        assertFalse(matchesOAuthState("expected", null))
        assertFalse(matchesOAuthState("expected", ""))
        assertFalse(matchesOAuthState("expected", "attacker"))
        assertFalse(matchesOAuthState("", ""))
    }

    @Test fun callbackMustMatchStructuredOriginAndPath() {
        assertTrue(matchesOAuthRedirect("aharou-mcp://oauth/callback", "aharou-mcp://oauth/callback?code=x&state=y"))
        assertFalse(matchesOAuthRedirect("aharou-mcp://oauth/callback", "aharou-mcp://oauth/callback.attacker?code=x"))
        assertFalse(matchesOAuthRedirect("aharou-mcp://oauth/callback", "aharou-mcp://oauth/callback/extra?code=x"))
        assertFalse(matchesOAuthRedirect("aharou-mcp://oauth/callback", "aharou-mcp://user@oauth/callback?code=x"))
        assertFalse(matchesOAuthRedirect("aharou-mcp://oauth/callback", "aharou-mcp://oauth/callback#fragment"))
    }
}
