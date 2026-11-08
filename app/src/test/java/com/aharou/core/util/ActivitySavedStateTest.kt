package com.aharou.core.util

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ActivitySavedStateTest {
    @Test fun coldStartHasNoRestoredState() {
        assertNull(activityStateForVersion(null, 1584))
    }

    @Test fun legacyStateWithoutVersionIsDiscarded() {
        val legacy = Bundle().apply { putString("screen", "retired-page") }
        assertNull(activityStateForVersion(legacy, 1584))
        assertEquals("retired-page", legacy.getString("screen"))
    }

    @Test fun anotherVersionIsDiscarded() {
        val previous = Bundle().apply { putInt(ACTIVITY_STATE_VERSION_KEY, 1583) }
        assertNull(activityStateForVersion(previous, 1584))
        assertNull(activityStateForVersion(previous, 1582))
    }

    @Test fun sameVersionRecreationPreservesState() {
        val current = Bundle().apply {
            putInt(ACTIVITY_STATE_VERSION_KEY, 1584)
            putString("draft", "keep")
        }
        assertSame(current, activityStateForVersion(current, 1584))
        assertEquals("keep", activityStateForVersion(current, 1584)?.getString("draft"))
    }
}
