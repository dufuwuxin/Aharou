package com.aharou.core.content

import android.app.ZygotePreload
import android.content.pm.ApplicationInfo
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import org.jsoup.Jsoup

@Keep
@RequiresApi(29)
class ContentZygotePreload : ZygotePreload {
    override fun doPreload(appInfo: ApplicationInfo) {
        // Only warm class/static parser state; no app context, threads or inherited descriptors.
        Jsoup.parse("", "")
    }
}
