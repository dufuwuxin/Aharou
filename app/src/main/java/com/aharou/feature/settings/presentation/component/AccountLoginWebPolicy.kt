package com.aharou.feature.settings.presentation.component

import android.graphics.Bitmap
import android.os.Message
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.net.URI

internal object AccountLoginWebPolicy {
    const val VIEWPORT_WIDTH = 1280
    const val DESKTOP_USER_AGENT = com.aharou.feature.settings.data.account.AccountProviderProtocol.WEBSITE_USER_AGENT
    internal val viewportScript = """
        (function(){function apply(){if(!document.documentElement)return;
        var m=document.querySelector('meta[name="viewport"]');
        if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}
        if(m.content!=='width=$VIEWPORT_WIDTH')m.content='width=$VIEWPORT_WIDTH';}
        apply();if(!document.head){var observer=new MutationObserver(function(){apply();if(document.head)observer.disconnect();});
        observer.observe(document,{childList:true,subtree:true});}})();
    """.trimIndent()

    fun isCallback(target: String, callback: String?): Boolean = try {
        val actual = URI(target)
        val expected = callback?.let(::URI)
        expected != null && actual.scheme == expected.scheme && actual.host == expected.host &&
            actual.port == expected.port && actual.rawPath == expected.rawPath &&
            actual.userInfo == null && actual.fragment == null && actual.rawQuery != null
    } catch (_: Exception) { false }

    fun isHttps(target: String): Boolean = try {
        val uri = URI(target)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.port in setOf(-1, 443)
    } catch (_: Exception) { false }

    fun applyInsets(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(view)
    }

    fun configure(view: WebView, navigate: (String) -> Boolean, loading: (Boolean) -> Unit, failed: () -> Unit) {
        with(view.settings) {
            userAgentString = DESKTOP_USER_AGENT
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptEnabled = true
            javaScriptCanOpenWindowsAutomatically = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            setSupportMultipleWindows(true)
        }
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(view, viewportScript, setOf("*"))
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.isForMainFrame && navigate(request.url.toString())
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { loading(true) }
            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(viewportScript, null)
                loading(false)
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) { loading(false); failed() }
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(owner: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                // Async authorization redirects may lose WebView's gesture flag; keep them in the pending login.
                val popup = WebView(owner.context)
                configure(popup, navigate, loading, failed)
                var released = false
                lateinit var detach: View.OnAttachStateChangeListener
                fun release() {
                    if (released) return
                    released = true
                    owner.removeOnAttachStateChangeListener(detach)
                    popup.stopLoading()
                    popup.destroy()
                }
                detach = object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) = Unit
                    override fun onViewDetachedFromWindow(v: View) { release() }
                }
                owner.addOnAttachStateChangeListener(detach)
                popup.webViewClient = object : WebViewClient() {
                    private var routed = false
                    private fun route(target: String): Boolean {
                        if (target == "about:blank") return false
                        if (routed) return true
                        routed = true
                        if (!navigate(target)) owner.loadUrl(target)
                        owner.post { release() }
                        return true
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.isForMainFrame && route(request.url.toString())
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { url?.let { route(it) } }
                }
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: run { release(); return false }
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }
    }
}
