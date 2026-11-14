package com.aharou.core.translation

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.Keep
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class TranslationWebEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val models: TranslationModelFiles,
) {
    private val main = Handler(Looper.getMainLooper())
    private var view: WebView? = null
    private var preparedTarget: String? = null
    private val pending = mutableMapOf<String, CancellableContinuation<String>>()

    suspend fun prepare(target: String = "zh") = withContext(Dispatchers.Main) {
        require(target == "zh" || target == "en")
        if (preparedTarget == target) return@withContext
        withTimeout(180_000) {
            request { id ->
                if (view == null) createView(id, target)
                else view?.evaluateJavascript("window.prepareLocal(${JSONObject.quote(id)},${JSONObject.quote(target)})", null)
            }
        }
        preparedTarget = target
    }

    suspend fun translate(text: String, target: String = "zh"): String = withContext(Dispatchers.Main) {
        prepare(target)
        withTimeout(60_000) {
            request { id ->
                view?.evaluateJavascript("window.translateLocal(${JSONObject.quote(id)},${JSONObject.quote(text)},${JSONObject.quote(target)})", null)
                    ?: error("Translation runtime unavailable")
            }
        }
    }

    private suspend fun request(start: (String) -> Unit): String = suspendCancellableCoroutine { continuation ->
        val id = UUID.randomUUID().toString()
        pending[id] = continuation
        continuation.invokeOnCancellation {
            if (Looper.myLooper() == Looper.getMainLooper()) reset() else main.post { reset() }
        }
        try {
            start(id)
        } catch (error: Exception) {
            pending.remove(id)
            continuation.resumeWithException(error)
            reset()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createView(id: String, target: String) {
        view?.destroy()
        view = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            @Suppress("DEPRECATION")
            settings.databaseEnabled = false
            settings.blockNetworkLoads = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            addJavascriptInterface(ResultBridge(), "TranslationResult")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(webView: WebView?, request: WebResourceRequest?): Boolean = true
                override fun shouldInterceptRequest(webView: WebView?, request: WebResourceRequest?): WebResourceResponse {
                    return intercept(request)
                }
            }
            loadUrl("$ORIGIN/index.html?request=$id&target=$target")
        }
    }

    private fun intercept(request: WebResourceRequest?): WebResourceResponse {
        if (request == null || request.method != "GET") return blocked()
        val uri = request.url
        if (uri.scheme != "https" || uri.host != "translation.invalid" || uri.port != -1 || uri.userInfo != null) return blocked()
        val path = uri.path ?: return blocked()
        if (path != uri.encodedPath || path.contains("..")) return blocked()
        val asset = when (path) {
            "/index.html" -> "index.html"
            "/runtime/transformers.min.js" -> "transformers.min.js"
            "/runtime/ort-wasm-simd.wasm" -> "ort-wasm-simd.wasm"
            "/runtime/ort-wasm.wasm" -> "ort-wasm.wasm"
            else -> null
        }
        val stream = try {
            when {
                asset != null -> context.assets.open("translation/$asset")
                path.startsWith("/models/opus-mt-en-zh/") -> models.openArtifact(path.removePrefix("/models/opus-mt-en-zh/"), "zh")
                path.startsWith("/models/opus-mt-zh-en/") -> models.openArtifact(path.removePrefix("/models/opus-mt-zh-en/"), "en")
                else -> null
            }
        } catch (_: Exception) { null } ?: return blocked()
        val mime = when {
            path.endsWith(".html") -> "text/html"
            path.endsWith(".js") -> "application/javascript"
            path.endsWith(".wasm") -> "application/wasm"
            path.endsWith(".json") -> "application/json"
            else -> "application/octet-stream"
        }
        val headers = mapOf(
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
            "Content-Security-Policy" to "default-src 'none'; script-src 'self' 'unsafe-eval' 'sha256-GzRyERhsyjFFppGTllER8zFGGBQgfGNax59cdYyPpLI='; connect-src 'self'; worker-src 'none'; base-uri 'none'; form-action 'none'",
        )
        return WebResourceResponse(mime, "UTF-8", 200, "OK", headers, stream)
    }

    private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun reset() {
        preparedTarget = null
        view?.removeJavascriptInterface("TranslationResult")
        view?.stopLoading()
        view?.destroy()
        view = null
        val remaining = pending.values.toList()
        pending.clear()
        remaining.forEach { if (it.isActive) it.resumeWithException(IllegalStateException("Translation runtime reset")) }
    }

    @Keep
    private inner class ResultBridge {
        @JavascriptInterface
        fun finish(id: String, success: Boolean, result: String) {
            main.post {
                val continuation = pending.remove(id) ?: return@post
                if (!continuation.isActive) return@post
                if (success) continuation.resume(result) else {
                    continuation.resumeWithException(IllegalStateException("Offline translation failed"))
                    reset()
                }
            }
        }
    }

    private companion object {
        const val ORIGIN = "https://translation.invalid"
    }
}
