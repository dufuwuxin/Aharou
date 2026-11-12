package com.aharou.feature.settings.data.account

import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.*
import org.junit.Test

class AccountProviderProtocolTest {
    private fun json(value: String) = JsonParser.parseString(value).asJsonObject

    @Test fun raccoonCatalogPreservesZeroAndMissingMultiplier() {
        val models = AccountProviderProtocol.models(AccountProviderKind.RACCOON, json("""{"data":{"categories":[{"type":"chat","models":[{"name":"a","billing_effective_multiplier":0},{"name":"b"},{"name":"hidden","visible":false}]}]}}"""))
        assertEquals(listOf("a", "b"), models.map { it.id })
        assertEquals("0", models[0].multiplier)
        assertNull(models[1].multiplier)
    }

    @Test fun workbuddyAndTraeMultipliersAreLiveOrNull() {
        val workbuddy = AccountProviderProtocol.models(AccountProviderKind.WORKBUDDY, json("""{"data":{"models":[{"id":"a","name":"A","credits":"x0 credits"},{"id":"b","credits":""}]}}"""))
        assertEquals("0", workbuddy[0].multiplier)
        assertNull(workbuddy[1].multiplier)
        val trae = AccountProviderProtocol.models(AccountProviderKind.TRAE, json("""{"data":{"config_info_list":[{"config_name":"a","display_contact_config":"{\"consumption_rate\":{\"data\":{\"rate\":0}}}"},{"config_name":"b"}]}}"""))
        assertEquals("0", trae[0].multiplier)
        assertNull(trae[1].multiplier)
    }

    @Test fun nativeStreamAggregatesNonStreamingToolsAndUsage() {
        val body = okhttp3.ResponseBody.create("text/event-stream".toMediaType(), "event: delta\ndata: {\"content\":\"hello\",\"tool_calls\":[{\"index\":0,\"id\":\"c\",\"function_call\":{\"name\":\"f\",\"arguments\":\"{}\"}}]}\n\nevent: token_usage\ndata: {\"usage\":{\"total_tokens\":3}}\n\nevent: response_done\ndata: {}\n\n")
        val result = json(TraeAccountProtocol.responseBody(body, "m", false).string())
        val choice = result.getAsJsonArray("choices")[0].asJsonObject
        assertEquals("hello", choice.getAsJsonObject("message").get("content").asString)
        assertEquals("tool_calls", choice.get("finish_reason").asString)
        assertEquals("c", choice.getAsJsonObject("message").getAsJsonArray("tool_calls")[0].asJsonObject.get("id").asString)
        assertEquals(3, result.getAsJsonObject("usage").get("total_tokens").asInt)
    }

    @Test fun missingBalanceIsNotZero() {
        for (kind in AccountProviderKind.entries) {
            assertNull(AccountProviderProtocol.balance(kind, json("{}")))
        }
        assertEquals("0 points", AccountProviderProtocol.balance(AccountProviderKind.RACCOON, json("""{"data":{"available_points":0}}""")))
    }

    @Test fun callbackRejectsWrongStateHostDuplicateAndExpired() {
        val login = AccountLogin("id", AccountProviderKind.RACCOON, "https://xiaohuanxiong.com/authorize", "office-raccoon://auth/callback", "nonce", 1, 1000, "verifier", "device")
        assertFalse(login.accept("office-raccoon://evil/callback?state=nonce&code=x", 100))
        assertFalse(login.accept("office-raccoon://auth/callback?state=wrong&code=x", 100))
        assertFalse(login.accept("office-raccoon://auth/callback?state=nonce&state=nonce&code=x", 100))
        assertTrue(login.accept("office-raccoon://auth/callback?state=nonce&code=x", 100))
        assertFalse(login.accept("office-raccoon://auth/callback?state=nonce&code=x", 100))
        val expired = AccountLogin("id", AccountProviderKind.RACCOON, "https://xiaohuanxiong.com/authorize", "office-raccoon://auth/callback", "nonce", 1, 50, "verifier", "device")
        assertFalse(expired.accept("office-raccoon://auth/callback?state=nonce&code=x", 100))
    }

    @Test fun traePayloadRetainsMatchedToolResultsAndSerializesSchema() {
        val route = AccountProviderModel("route", "Route", null, "chat", "route", "native")
        val result = TraeAccountProtocol.request(json("""{"model":"route","messages":[{"role":"assistant","content":null,"tool_calls":[{"id":"c","type":"function","function":{"name":"f","arguments":"{}"}}]},{"role":"tool","tool_call_id":"c","content":"result"}],"tools":[{"type":"function","function":{"name":"f","parameters":{"type":"object"}}}]}"""), route)
        val messages = result.getAsJsonArray("messages")
        assertTrue(messages[0].asJsonObject.getAsJsonArray("tool_calls")[0].asJsonObject.has("function_call"))
        assertEquals("c", messages[1].asJsonObject.get("tool_call_id").asString)
        assertTrue(result.getAsJsonArray("tools")[0].asJsonObject.getAsJsonObject("function").get("parameters").asJsonPrimitive.isString)
    }

    @Test fun traeErrorCannotBecomeEmptySuccessfulAnswer() {
        try {
            TraeAccountProtocol.convertEvent("error", """{"code":11128,"message":"channel rejected"}""", "m")
            fail("Business error must fail")
        } catch (expected: AccountProviderException) {
            assertEquals("upstream_error", expected.errorCode)
        }
    }

    @Test fun traeNativeToolAndReasoningBecomeOpenAiDelta() {
        val chunk = TraeAccountProtocol.convertEvent("delta", """{"delta":{"reasoning_content":"think","content":"hi","tool_calls":[{"index":0,"id":"c","function_call":{"name":"f","arguments":"{}"}}]}}""", "m")!!
        val delta = chunk.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("delta")
        assertEquals("hi", delta.get("content").asString)
        assertEquals("think", delta.get("reasoning_content").asString)
        assertTrue(delta.getAsJsonArray("tool_calls")[0].asJsonObject.has("function"))
    }
}
