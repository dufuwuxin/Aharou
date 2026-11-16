package com.aharou.feature.settings.data.account

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import java.io.StringReader

internal object DoubaoWebsiteCatalog {
    fun config(html: String, expectedUserId: String, expectedBotId: String): JsonObject {
        if (html.length > AccountProviderManager.MAX_JSON) failure("format")
        val assignment = Regex("^\\s*window\\._ROUTER_DATA\\s*=\\s*")
        val scripts = Regex("<script\\b[^>]*>", RegexOption.IGNORE_CASE)
        var router: JsonObject? = null
        var cursor = 0
        while (cursor < html.length) {
            val tag = scripts.find(html, cursor) ?: break
            val start = tag.range.last + 1
            val end = html.indexOf("</script", start, ignoreCase = true)
            if (end < 0) failure("format")
            cursor = html.indexOf('>', end).takeIf { it >= 0 }?.plus(1) ?: failure("format")
            val script = html.substring(start, end)
            val match = assignment.find(script) ?: continue
            if (router != null) failure("format")
            router = try {
                JsonReader(StringReader(objectLiteral(script, match.range.last + 1))).use { reader ->
                    reader.strictness = Strictness.STRICT
                    JsonParser.parseReader(reader).asJsonObject
                }
            } catch (_: RuntimeException) { failure("format") }
        }
        val layout = router?.obj("loaderData")?.obj("chat_layout")?.obj("chat_layout") ?: failure("missing")
        val account = layout.obj("accountInfo")?.obj("data") ?: failure("identity")
        val userId = account.text("user_id_str") ?: account.text("user_id")
        if (!expectedUserId.matches(Regex("[1-9][0-9]{5,24}")) || userId != expectedUserId ||
            account.text("error_code") != "0" || account.text("is_visitor_account") in setOf("true", "1")) failure("identity")
        val directory = layout.obj("actionBarBriefList") ?: failure("missing")
        if (directory.obj("request")?.text("bot_id") != expectedBotId) failure("missing")
        val modes = directory.obj("modeSelectData") ?: failure("missing")
        val v2 = modes.obj("menuConfV2")
        val legacy = modes.obj("modeSelectConfig")?.array("menu_item_list")
        if (v2 == null && (legacy == null || legacy.isEmpty)) failure("empty")
        return JsonObject().apply {
            if (v2 != null) add("menu_conf_v2", v2.deepCopy())
            if (legacy != null) add("menu_conf", JsonObject().apply { add("item_list", legacy.deepCopy()) })
        }
    }

    private fun objectLiteral(script: String, start: Int): String {
        if (script.getOrNull(start) != '{') failure("format")
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until script.length) {
            val char = script[index]
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; if (depth > 128) failure("format") }
                '}', ']' -> {
                    depth--
                    if (depth == 0) {
                        if (script.substring(index + 1).trim().let { it.isNotEmpty() && it != ";" }) failure("format")
                        return script.substring(start, index + 1)
                    }
                    if (depth < 0) failure("format")
                }
            }
        }
        failure("format")
    }

    private fun failure(reason: String): Nothing = throw AccountProviderException(
        "web_catalog_$reason", "Doubao authenticated website directory could not be verified", 502,
    )
}
