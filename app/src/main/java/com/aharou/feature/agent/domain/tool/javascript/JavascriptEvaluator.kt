package com.aharou.feature.agent.domain.tool.javascript

import com.aharou.feature.agent.domain.tool.ToolResult
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.coroutineContext

@Singleton
class JavascriptEvaluator @Inject constructor() {
    private val evaluations = Semaphore(1)

    suspend fun evaluate(code: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ToolResult {
        if (code.isBlank() || code.length > MAX_CODE_CHARS) {
            return ToolResult.Error("code must contain 1..$MAX_CODE_CHARS characters", "INVALID_ARGS")
        }
        if (timeoutMs !in 1..MAX_TIMEOUT_MS) {
            return ToolResult.Error("timeout_ms must be in 1..$MAX_TIMEOUT_MS", "INVALID_ARGS")
        }
        return evaluations.withPermit {
            withContext(Dispatchers.Default) {
                coroutineContext.ensureActive()
                try {
                    val engine = QuickJs.create(Dispatchers.Default)
                    try {
                        engine.memoryLimit = MEMORY_LIMIT_BYTES
                        engine.maxStackSize = STACK_LIMIT_BYTES
                        engine.evaluationTimeoutMillis = timeoutMs
                        // The completion value, its toJSON hooks and console formatting all run
                        // inside this one native deadline, never in a second evaluation.
                        val encoded = engine.evaluate<String>(script(code), "eval_javascript.js")
                        coroutineContext.ensureActive()
                        if (encoded.length > MAX_OUTPUT_CHARS) {
                            ToolResult.Error("JavaScript output limit exceeded", "JS_OUTPUT_LIMIT")
                        } else {
                            ToolResult.Success(Json.parseToJsonElement(encoded))
                        }
                    } finally {
                        engine.close()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (interrupted: QuickJsInterruptedException) {
                    ToolResult.Error("JavaScript execution timed out", "JS_TIMEOUT")
                } catch (error: QuickJsException) {
                    val message = error.message.orEmpty().take(MAX_ERROR_CHARS)
                    val code = when {
                        "JS_LOG_LIMIT" in message -> "JS_LOG_LIMIT"
                        "JS_OUTPUT_LIMIT" in message -> "JS_OUTPUT_LIMIT"
                        else -> "JS_EVALUATION_ERROR"
                    }
                    ToolResult.Error(message.ifBlank { "JavaScript evaluation failed" }, code)
                } catch (error: LinkageError) {
                    ToolResult.Error("QuickJS native runtime is unavailable or incompatible", "JS_ENGINE_UNAVAILABLE")
                }
            }
        }
    }

    private fun script(code: String): String {
        val source = JsonPrimitive(code).toString()
        return """
            (() => {
                const stringify = JSON.stringify;
                const text = String;
                const LimitError = RangeError;
                const PromiseType = Promise;
                const create = Object.create;
                const logs = [];
                Object.setPrototypeOf(logs, null);
                let logChars = 0;
                let logLimit = false;
                const write = (...args) => {
                    if (logLimit || logs.length >= $MAX_LOG_ENTRIES) {
                        logLimit = true;
                        throw new LimitError('JS_LOG_LIMIT');
                    }
                    let line = '';
                    for (let i = 0; i < args.length; i++) {
                        const value = args[i];
                        const part = typeof value === 'string' ? value :
                            (value !== null && typeof value === 'object' ? text(stringify(value)) : text(value));
                        if (i > 0) line += ' ';
                        if (line.length + part.length + logChars > $MAX_LOG_CHARS) {
                            logLimit = true;
                            throw new LimitError('JS_LOG_LIMIT');
                        }
                        line += part;
                    }
                    if (logLimit || logs.length >= $MAX_LOG_ENTRIES || logChars + line.length > $MAX_LOG_CHARS) {
                        logLimit = true;
                        throw new LimitError('JS_LOG_LIMIT');
                    }
                    logChars += line.length;
                    logs[logs.length] = line;
                };
                const consoleApi = create(null);
                consoleApi.log = write;
                consoleApi.info = write;
                consoleApi.warn = write;
                consoleApi.error = write;
                consoleApi.debug = write;
                Object.defineProperty(globalThis, 'console', {
                    value: Object.freeze(consoleApi), writable: false, configurable: false
                });
                const value = (0, eval)($source);
                if (logLimit) throw new LimitError('JS_LOG_LIMIT');
                if (value instanceof PromiseType) throw new TypeError('Async results are not supported');
                const response = create(null);
                response.result = value === undefined ? null : value;
                response.logs = logs;
                const output = stringify(response);
                if (logLimit) throw new LimitError('JS_LOG_LIMIT');
                if (output.length > $MAX_OUTPUT_CHARS) throw new LimitError('JS_OUTPUT_LIMIT');
                return output;
            })()
        """.trimIndent()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 1_000L
        const val MAX_TIMEOUT_MS = 5_000L
        const val MAX_CODE_CHARS = 65_536
        const val MAX_OUTPUT_CHARS = 32_768
        const val MAX_LOG_ENTRIES = 64
        const val MAX_LOG_CHARS = 8_192
        const val MEMORY_LIMIT_BYTES = 16L * 1024 * 1024
        const val STACK_LIMIT_BYTES = 256L * 1024
        private const val MAX_ERROR_CHARS = 1_024
    }
}
