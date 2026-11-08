package com.aharou.core.util

import android.os.Bundle

internal const val ACTIVITY_STATE_VERSION_KEY = "com.aharou.activityStateVersion"

// Saved Compose state can contain enums removed by an update; never deserialize another version's UI state.
internal fun activityStateForVersion(state: Bundle?, versionCode: Int): Bundle? =
    state?.takeIf { it.getInt(ACTIVITY_STATE_VERSION_KEY, -1) == versionCode }
