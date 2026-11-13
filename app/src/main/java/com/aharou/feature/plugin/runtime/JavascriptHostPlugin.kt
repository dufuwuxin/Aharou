package com.aharou.feature.plugin.runtime

import com.aharou.plugin.api.AharouPlugin
import com.aharou.plugin.api.PluginHost
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.FunctionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

/** One persistent native QuickJS context per installed generation. */
class JavascriptHostPlugin(private val source: String) : AharouPlugin {
    private var engine: QuickJs? = null

    override fun onLoad(host: PluginHost) {
        val js = QuickJs.create(Dispatchers.Default)
        engine = js
        try {
            js.evaluationTimeoutMillis = 30_000
            js.defineBinding("__aharouHostCall", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    require(args.size == 2) { "host.call expects operation and JSON arguments" }
                    return host.call(args[0] as String, args[1] as String)
                }
            })
            evaluate("""
                globalThis.host = Object.freeze({
                    apiVersion: ${host.apiVersion()},
                    pluginDirectory: ${JsonPrimitive(host.pluginDirectory())},
                    dataDirectory: ${JsonPrimitive(host.dataDirectory())},
                    call: (operation, argsJson) => __aharouHostCall(operation, argsJson)
                });
                $source
                if (typeof plugin !== 'object' || plugin === null) throw new Error('Missing global plugin object');
                if (typeof plugin.onLoad === 'function') plugin.onLoad(host);
                'loaded';
            """.trimIndent())
        } catch (failure: Throwable) {
            js.close()
            engine = null
            throw failure
        }
    }

    private fun evaluate(script: String): String = runBlocking(Dispatchers.Default) {
        checkNotNull(engine) { "JavaScript plugin is not loaded" }.evaluate<String>(script, "host-plugin.js")
    }

    private fun invoke(method: String, fallback: String, vararg args: String): String {
        val arguments = args.joinToString(",") { JsonPrimitive(it).toString() }
        return evaluate("""
            (() => {
                if (typeof plugin.$method !== 'function') return ${JsonPrimitive(fallback)};
                const result = plugin.$method($arguments);
                if (typeof result !== 'string') throw new TypeError('$method must return a JSON string');
                return result;
            })()
        """.trimIndent())
    }

    override fun toolsJson(): String = invoke("toolsJson", "[]")
    override fun executeTool(name: String, argumentsJson: String, contextJson: String): String =
        invoke("executeTool", "null", name, argumentsJson, contextJson)
    override fun onModelRequest(requestJson: String): String = invoke("onModelRequest", requestJson, requestJson)
    override fun onModelEvent(eventJson: String): String = invoke("onModelEvent", eventJson, eventJson)

    override fun onUnload() {
        val js = engine ?: return
        try { evaluate("if (typeof plugin.onUnload === 'function') plugin.onUnload(); 'unloaded';") }
        finally { js.close(); engine = null }
    }
}
