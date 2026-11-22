package com.aharou.core.content

import android.content.Context
import android.os.Build
import com.aharou.feature.settings.data.ExperimentSettings

/** Restart-scoped policy: later preference changes only affect the next application process. */
object ContentProcessPolicy {
    data class Snapshot(val isolatedProcess: Boolean, val appZygote: Boolean)

    const val ISOLATED_PROCESS = "content.isolated_process"
    const val APP_ZYGOTE = "content.app_zygote"

    @Volatile private var snapshot: Snapshot? = null

    fun capture(context: Context): Snapshot = snapshot ?: synchronized(this) {
        snapshot ?: ExperimentSettings(context).let { settings ->
            resolve(settings.value(ISOLATED_PROCESS), settings.value(APP_ZYGOTE), Build.VERSION.SDK_INT)
                .also { snapshot = it }
        }
    }

    internal fun resolve(isolated: String?, zygote: String?, sdk: Int): Snapshot = Snapshot(
        isolatedProcess = isolated == ExperimentSettings.ON || zygote == ExperimentSettings.ON,
        appZygote = zygote == ExperimentSettings.ON && sdk >= Build.VERSION_CODES.Q
    )
}
