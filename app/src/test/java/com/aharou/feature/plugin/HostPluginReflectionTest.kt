package com.aharou.feature.plugin

import com.aharou.feature.plugin.runtime.HostPluginReflection
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class HostPluginReflectionTest {
    class Fixture {
        private var value: String = "before"
        private fun append(suffix: String): String = value + suffix
    }

    private val reflection = HostPluginReflection { Fixture() }
    private fun call(operation: String, vararg args: Pair<String, JsonElement>): JsonElement =
        Json.parseToJsonElement(reflection.call("reflect.$operation", JsonObject(args.toMap())))

    @Test fun privateHostFieldAndMethodAreActuallyMutable() {
        val host = call("context")
        assertEquals(JsonPrimitive("before"), call("get", "target" to host, "name" to JsonPrimitive("value")))
        call("set", "target" to host, "name" to JsonPrimitive("value"), "value" to JsonPrimitive("after"))
        val value = call("invoke", "target" to host, "name" to JsonPrimitive("append"),
            "parameterTypes" to JsonArray(listOf(JsonPrimitive("java.lang.String"))),
            "arguments" to JsonArray(listOf(JsonPrimitive("_called"))))
        assertEquals(JsonPrimitive("after_called"), value)
    }

    @Test fun constructsClassesAndInvokesWithExactPrimitiveTypes() {
        val type = call("class", "name" to JsonPrimitive("java.lang.StringBuilder"))
        val instance = call("new", "target" to type)
        call("invoke", "target" to instance, "name" to JsonPrimitive("append"),
            "parameterTypes" to JsonArray(listOf(JsonPrimitive("int"))), "arguments" to JsonArray(listOf(JsonPrimitive(42))))
        assertEquals(JsonPrimitive("42"), call("invoke", "target" to instance, "name" to JsonPrimitive("toString")))
    }

    @Test fun invalidPrimitiveTypesNullAndArityDoNotPoisonHandles() {
        val type = call("class", "name" to JsonPrimitive("java.lang.StringBuilder"))
        val target = call("new", "target" to type)
        for (values in listOf(listOf(JsonPrimitive("not_int")), listOf(JsonPrimitive("2147483648")), listOf(JsonNull), emptyList())) {
            assertTrue(runCatching {
                call("invoke", "target" to target, "name" to JsonPrimitive("append"),
                    "parameterTypes" to JsonArray(listOf(JsonPrimitive("int"))), "arguments" to JsonArray(values))
            }.isFailure)
            assertEquals(JsonPrimitive(""), call("invoke", "target" to target, "name" to JsonPrimitive("toString")))
        }
        call("invoke", "target" to target, "name" to JsonPrimitive("append"),
            "parameterTypes" to JsonArray(listOf(JsonPrimitive("int"))), "arguments" to JsonArray(listOf(JsonPrimitive(7))))
        assertEquals(JsonPrimitive("7"), call("invoke", "target" to target, "name" to JsonPrimitive("toString")))
    }

    class BlockingFixture {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        fun block(): String { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); return "late" }
    }

    @Test fun closingWhileAnInvocationIsBlockedDoesNotWaitOrPublishItsResult() {
        val fixture = BlockingFixture()
        val bridge = HostPluginReflection { fixture }
        val handle = Json.parseToJsonElement(bridge.call("reflect.context", JsonObject(emptyMap())))
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val invoke = pool.submit<Boolean> {
                runCatching { bridge.call("reflect.invoke", buildJsonObject { put("target", handle); put("name", "block") }) }.isFailure
            }
            assertTrue(fixture.entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            pool.submit<Unit> { bridge.close() }.get(1, java.util.concurrent.TimeUnit.SECONDS)
            fixture.release.countDown()
            assertTrue(invoke.get(2, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(runCatching { bridge.call("reflect.context", JsonObject(emptyMap())) }.isFailure)
        } finally { fixture.release.countDown(); pool.shutdownNow() }
    }

    @Test fun foreignHandlesAreRejectedAndCloseReleasesAllHandles() {
        val handle = call("context")
        val other = HostPluginReflection { Fixture() }
        val args = buildJsonObject { put("target", handle); put("name", "value") }
        assertTrue(runCatching { other.call("reflect.get", args) }.isFailure)
        call("release", "target" to handle)
        assertTrue(runCatching { reflection.call("reflect.get", args) }.isFailure)
        call("context")
        reflection.close()
        assertTrue(runCatching { call("context") }.isFailure)
    }
}
