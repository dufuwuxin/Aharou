package com.aharou.feature.settings.presentation

import com.aharou.feature.settings.presentation.component.AccountLoginWebPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountLoginWebPolicyTest {
    @Test fun desktopPolicyHasRealWideViewportAndDesktopPlatform() {
        assertTrue(AccountLoginWebPolicy.VIEWPORT_WIDTH >= 980)
        assertTrue(AccountLoginWebPolicy.DESKTOP_USER_AGENT.contains("X11; Linux x86_64"))
        assertFalse(AccountLoginWebPolicy.DESKTOP_USER_AGENT.contains("Android"))
        assertFalse(AccountLoginWebPolicy.DESKTOP_USER_AGENT.contains("Mobile"))
        assertTrue(AccountLoginWebPolicy.viewportScript.contains("width=1280"))
    }

    @Test fun callbackRoutingRequiresExactPendingEndpointButLeavesStateValidationToManager() {
        val callback = "http://127.0.0.1:34567/authorize"
        assertTrue(AccountLoginWebPolicy.isCallback("$callback?state=fixture&code=fixture", callback))
        for (target in listOf("http://127.0.0.1:34568/authorize?code=x", "http://127.0.0.1:34567/authorize/extra?code=x",
            "http://127.0.0.1.evil:34567/authorize?code=x", "http://evil@127.0.0.1:34567/authorize?code=x",
            "$callback?code=x#fragment", callback, "trae://authorize?code=x")) {
            assertFalse(target, AccountLoginWebPolicy.isCallback(target, callback))
        }
        assertFalse(AccountLoginWebPolicy.isCallback("$callback?code=x", null))
    }

    @Test fun normalHttpsNavigationIsNotConfusedWithArbitraryDeepLinks() {
        assertTrue(AccountLoginWebPolicy.isHttps("https://www.trae.cn/authorization?state=fixture"))
        assertTrue(AccountLoginWebPolicy.isHttps("https://www.doubao.com/chat/fixture?from=login"))
        for (target in listOf("intent://external", "trae://external", "http://127.0.0.1:34567/other", "file:///tmp/x",
            "javascript:alert(1)", "https://user@www.trae.cn/", "https://www.trae.cn:444/")) {
            assertFalse(target, AccountLoginWebPolicy.isHttps(target))
        }
    }
}
