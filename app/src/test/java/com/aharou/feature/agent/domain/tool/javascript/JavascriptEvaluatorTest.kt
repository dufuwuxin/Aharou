package com.aharou.feature.agent.domain.tool.javascript

import com.aharou.feature.agent.domain.tool.ToolPermissionPolicy
import com.aharou.feature.agent.domain.tool.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.BeforeClass
import java.nio.file.Files

// These tests deliberately use the real JNI engine: a fake evaluator cannot prove interruption.
class JavascriptEvaluatorTest {
    companion object {
        @JvmStatic @BeforeClass fun prepareRealHostNativeRuntime() {
            val os = requireNotNull(System.getProperty("os.name")).lowercase()
            val arch = requireNotNull(System.getProperty("os.arch")).lowercase()
            val platform = when {
                os.contains("linux") && arch in listOf("aarch64", "arm64") -> "linux_aarch64"
                os.contains("linux") && arch in listOf("amd64", "x86_64") -> "linux_x64"
                else -> error("No verified QuickJS host JNI fixture for $os/$arch")
            }
            val directory = Files.createTempDirectory("aharou-quickjs-host-").toFile().apply { deleteOnExit() }
            val native = directory.resolve("libquickjs.so").apply { deleteOnExit() }
            val resource = requireNotNull(JavascriptEvaluatorTest::class.java.getResourceAsStream("/jni/$platform/libquickjs.so"))
            resource.use { input -> native.outputStream().use { input.copyTo(it) } }
            System.setProperty("com.dokar.quickjs.library.path", directory.absolutePath)
            System.setProperty("com.dokar.quickjs.library.name", native.name)
        }
    }

    private val evaluator = JavascriptEvaluator()
    private val tool = JavascriptTool(evaluator)

    private suspend fun success(code: String) =
        (evaluator.evaluate(code) as ToolResult.Success).data.jsonObject

    private suspend fun error(code: String, expected: String, timeout: Long = 1_000L) {
        val result = evaluator.evaluate(code, timeout)
        assertTrue("Expected $expected, got $result", result is ToolResult.Error)
        assertEquals(expected, (result as ToolResult.Error).code)
    }

    @Test fun returnsLastArithmeticExpression() = runBlocking {
        assertEquals(JsonPrimitive(42), success("const x = 6; x * 7")["result"])
    }

    @Test fun returnsObjectsAsJson() = runBlocking {
        assertEquals(Json.parseToJsonElement("{\"answer\":42,\"items\":[true,null]}"),
            success("({answer:42, items:[true,null]})")["result"])
    }

    @Test fun returnsConsoleOutputAndUndefinedAsNull() = runBlocking {
        val result = success("console.log('answer', {value:42}); console.warn('done')")
        assertEquals(Json.parseToJsonElement("null"), result["result"])
        assertEquals(listOf(JsonPrimitive("answer {\"value\":42}"), JsonPrimitive("done")),
            result["logs"]!!.jsonArray.toList())
    }

    @Test fun reportsSyntaxErrors() = runBlocking {
        error("let = ;", "JS_EVALUATION_ERROR")
    }

    @Test fun interruptsInfiniteLoop() = runBlocking {
        withTimeout(5_000) { error("while(true) {}", "JS_TIMEOUT", 50) }
    }

    @Test fun interruptsResultToJson() = runBlocking {
        withTimeout(5_000) { error("({toJSON(){ while(true){} }})", "JS_TIMEOUT", 50) }
    }

    @Test fun interruptsConsoleToJson() = runBlocking {
        withTimeout(5_000) {
            error("console.log({toJSON(){ while(true){} }})", "JS_TIMEOUT", 50)
        }
    }

    @Test fun rejectsLargeResults() = runBlocking {
        error("'x'.repeat(40000)", "JS_OUTPUT_LIMIT")
    }

    @Test fun rejectsLargeConsoleOutput() = runBlocking {
        error("console.log('x'.repeat(9000))", "JS_LOG_LIMIT")
    }

    @Test fun reentrantConsoleFormattingCannotAppendA65thLog() = runBlocking {
        error("console.log({toJSON(){for(let i=0;i<64;i++) console.log(i); return 'outer'}})", "JS_LOG_LIMIT")
    }

    @Test fun caughtConsoleOverflowStillFails() = runBlocking {
        error("for(let i=0;i<100;i++){try{console.log(i)}catch(e){}}; 42", "JS_LOG_LIMIT")
    }

    @Test fun caughtConsoleOverflowInsideResultToJsonStillFails() = runBlocking {
        error("({toJSON(){try{console.log('x'.repeat(9000))}catch(e){} return 42}})", "JS_LOG_LIMIT")
    }

    @Test fun hostBridgesAreAbsent() = runBlocking {
        val result = success("[typeof window,typeof document,typeof require,typeof process," +
            "typeof Java,typeof Packages,typeof fetch,typeof XMLHttpRequest,typeof readFile," +
            "typeof std,typeof os,typeof Deno,typeof Bun].every(x => x === 'undefined')")
        assertEquals(JsonPrimitive(true), result["result"])
        error("Java.type('java.lang.Runtime').getRuntime().exec('echo blocked')", "JS_EVALUATION_ERROR")
    }

    @Test fun limitsInputsWithoutStartingEngine() = runBlocking {
        assertEquals("INVALID_ARGS", (evaluator.evaluate(" ") as ToolResult.Error).code)
        assertEquals("INVALID_ARGS", (evaluator.evaluate("x".repeat(65_537)) as ToolResult.Error).code)
        assertEquals("INVALID_ARGS", (evaluator.evaluate("1", 0) as ToolResult.Error).code)
        assertEquals("INVALID_ARGS", (evaluator.evaluate("1", 5_001) as ToolResult.Error).code)
        assertEquals("INVALID_ARGS", (tool.execute(mapOf("code" to JsonPrimitive(1))) as ToolResult.Error).code)
        assertEquals("INVALID_ARGS", (tool.execute(mapOf("code" to JsonPrimitive("1"),
            "timeout_ms" to JsonPrimitive("50"))) as ToolResult.Error).code)
        assertEquals("INVALID_ARGS", (tool.execute(mapOf("code" to JsonPrimitive("1"),
            "timeout_ms" to JsonPrimitive(1.5))) as ToolResult.Error).code)
    }

    @Test fun schemaIsDiscoverableButNotDefaultInjected() {
        assertEquals("eval_javascript", tool.name)
        assertTrue(tool.deferredLoading)
        assertEquals(ToolPermissionPolicy.ASK, tool.permissionPolicy)
        assertTrue(tool.capabilities.isEmpty())
        assertFalse(tool.acceptsSecrets)
        val schema = tool.toJsonSchema()
        assertTrue((schema["required"] as List<*>).contains("code"))
        assertFalse((schema["required"] as List<*>).contains("timeout_ms"))
    }

    @Test fun rejectsPromisesAndCircularJson() = runBlocking {
        error("Promise.resolve(42)", "JS_EVALUATION_ERROR")
        error("const x={}; x.self=x; x", "JS_EVALUATION_ERROR")
    }

    @Test fun eachCallHasAFreshGlobalScope() = runBlocking {
        success("globalThis.saved = 42")
        assertEquals(JsonPrimitive("undefined"), success("typeof saved")["result"])
    }

    @Test fun memoryAndStackExhaustionAreErrors() = runBlocking {
        error("const xs=[]; for(let i=0;i<10000;i++) xs.push(new Array(10000).fill(i)); xs.length",
            "JS_EVALUATION_ERROR")
        error("function recurse(){return 1+recurse()}; recurse()", "JS_EVALUATION_ERROR")
    }

    @Test fun cancellationIsNotSuccessfulAndReleasesEngine() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val evaluation = async(Dispatchers.Default) {
            entered.complete(Unit)
            evaluator.evaluate("while(true){}", 5_000)
        }
        entered.await()
        delay(100)
        evaluation.cancel()
        withTimeout(2_000) { evaluation.join() }
        assertTrue(evaluation.isCancelled)
        assertEquals(JsonPrimitive(2), success("1+1")["result"])
    }
}
