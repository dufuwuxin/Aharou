package com.aharou.feature.settings.data.account

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class DoubaoWebsiteCatalogTest {
    private val uid = "123456789"
    private val bot = "7338286299411103781"

    private fun router(): JsonObject = JsonParser.parseString("""{
        "loaderData":{"chat_layout":{"chat_layout":{
            "accountInfo":{"data":{"user_id":"123456789","error_code":0}},
            "actionBarBriefList":{"request":{"bot_id":"7338286299411103781"},"modeSelectData":{
                "menuConfV2":{"mode_list":{"item_list":[
                    {"agent_mode":2,"model_list":{"item_list":[
                        {"model_item_key":"7","name":"Fixture { chat }"},
                        {"model_item_key":"8","name":"Disabled","disabled":true},
                        {"model_item_key":"9","name":"Upgrade","subscribe_config":{"need_upgrade":true}}
                    ]}},
                    {"agent_mode":1,"model_list":{"item_list":[{"model_item_key":"10","name":"Work"}]}}
                ]}}
            }}
        }}}
    }""").asJsonObject

    private fun html(value: JsonObject) = "<html><script>var unrelated = 'not executed';</script>" +
        "<script nonce=\"fixture\">window._ROUTER_DATA = $value;</script></html>"

    private fun reject(expected: String, value: String, expectedUid: String = uid, expectedBot: String = bot) {
        try { DoubaoWebsiteCatalog.config(value, expectedUid, expectedBot); fail("Unverified website accepted") }
        catch (error: AccountProviderException) {
            assertEquals("web_catalog_$expected", error.errorCode)
            assertFalse(error.message!!.contains(uid))
            assertFalse(error.message!!.contains(value))
        }
    }

    @Test fun onlyAccountBoundWebsiteModelsReachExistingQualificationFilter() {
        val config = DoubaoWebsiteCatalog.config(html(router()), uid, bot)
        val payload = JsonObject().apply {
            addProperty("code", 0)
            add("data", JsonParser.parseString("""{"entry_list":[{"action_bar_key":"coco_deep_thinking"}]}""").asJsonObject.apply {
                array("entry_list")[0].asJsonObject.add("active_switch_conf", config)
            })
        }
        assertEquals(listOf("7"), DoubaoAccountProtocol.models(payload).map { it.id })
    }

    @Test fun identityAndBotMustMatchWithoutUsingOtherSessionFields() {
        reject("identity", html(router()), "987654321")
        reject("identity", html(router()), "")
        reject("missing", html(router()), expectedBot = "different-bot")
        val visitor = router()
        visitor.obj("loaderData")!!.obj("chat_layout")!!.obj("chat_layout")!!.obj("accountInfo")!!.obj("data")!!
            .addProperty("is_visitor_account", true)
        reject("identity", html(visitor))
    }

    @Test fun malformedMultipleAndExecutableAssignmentsCannotBecomeDirectoryData() {
        reject("missing", "<script>window.defaultModels = [7];</script>")
        reject("format", "<script>window._ROUTER_DATA = function(){return ${router()}};</script>")
        reject("format", "<script>window._ROUTER_DATA = ${router()}; console.log('extra');</script>")
        reject("format", html(router()) + html(router()))
        reject("format", html(router()).replace("\"loaderData\"", "'loaderData'"))
        reject("format", "<script>window._ROUTER_DATA = {/*comment*/\"x\":0};</script>")
        reject("format", "<script>window._ROUTER_DATA = {\"broken\":true;")
    }

    @Test fun pageSizeAndJsonNestingAreBounded() {
        reject("format", "x".repeat(AccountProviderManager.MAX_JSON + 1))
        reject("format", "<script>window._ROUTER_DATA = {\"x\":" + "[".repeat(129) + "0" + "]".repeat(129) + "};</script>")
        reject("format", "<script>window._ROUTER_DATA = {\"x\":" + "[/*]*/".repeat(129) + "0" + "/*[*/]".repeat(129) + "};</script>")
    }
}
