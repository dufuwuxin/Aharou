package com.aharou.feature.browser

import kotlinx.coroutines.CompletableDeferred
import java.util.UUID

/** One pending bridge result; stale callbacks and cleanup cannot affect its successor. */
internal class BrowserAsyncResult {
    internal class Request {
        val id: String = UUID.randomUUID().toString()
        val result = CompletableDeferred<String>()

        fun wrap(expression: String, nullAsString: Boolean = false): String = """
            (async function(){
                try {
                    var __v__ = await ($expression);
                    if (__v__ === undefined || __v__ === null) {
                        __aharou__.resolve('$id', ${if (nullAsString) "'null'" else "String(__v__)"});
                    } else if (typeof __v__ === 'object') {
                        __aharou__.resolve('$id', JSON.stringify(__v__));
                    } else {
                        __aharou__.resolve('$id', String(__v__));
                    }
                } catch(e) {
                    __aharou__.reject('$id', e && e.message ? e.message : String(e));
                }
            })();
        """.trimIndent()
    }

    private var pending: Request? = null

    @Synchronized fun begin(): Request = Request().also { pending = it }

    @Synchronized fun complete(requestId: String, payload: String): Boolean {
        val request = pending?.takeIf { it.id == requestId } ?: return false
        pending = null
        return request.result.complete(payload)
    }

    @Synchronized fun clear(request: Request) {
        if (pending === request) pending = null
    }
}
