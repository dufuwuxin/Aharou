package com.aharou

import android.content.Intent
import androidx.compose.runtime.saveable.SaverScope
import com.aharou.feature.onboarding.data.OnboardingStatus
import com.aharou.feature.onboarding.domain.OnboardingStep
import com.aharou.feature.widget.WidgetActions
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@ConscryptMode(ConscryptMode.Mode.OFF)
class AppLaunchPolicyTest {
    private fun launcher() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    private fun recreate(policy: AppLaunchPolicy): AppLaunchPolicy {
        val scope = object : SaverScope { override fun canBeSaved(value: Any): Boolean = true }
        val saved = with(AppLaunchPolicy.StateSaver) { scope.save(policy) }!!
        return AppLaunchPolicy.StateSaver.restore(saved)!!
    }

    @Test fun firstLaunchAndLauncherReentryOpenProjects() {
        assertEquals("projects", OnboardingStep.ENTER_SETTINGS.route)
        val policy = AppLaunchPolicy(launcher())
        assertEquals(1, policy.onboardingStep(null, false, 1))
        assertNull(policy.onboardingStep(OnboardingStatus.COMPLETED, false, 1))
        var route = "chat"
        var paused = false
        policy.consume(launcher(), "chat", { paused = true }, { route = "projects" })
        assertTrue(paused)
        assertEquals("projects", route)
        assertNull(policy.onboardingStep(OnboardingStatus.IN_PROGRESS, false, 8))
    }

    @Test fun explicitColdAndWarmEntriesCannotBeStolenByOnboarding() {
        for (action in listOf(Intent.ACTION_PROCESS_TEXT, WidgetActions.ACTION_OPEN_CHAT,
            "com.aharou.intent.NEW_CHAT", Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_VIEW)) {
            val intent = Intent(action)
            val policy = AppLaunchPolicy(intent)
            assertNull(action, policy.onboardingStep(null, false, 8))
            var paused = false
            policy.consume(intent, "config", { paused = true }, { fail("explicit entry opened projects") })
            assertTrue(action, paused)
            assertNull(policy.onboardingStep(OnboardingStatus.COMPLETED, false, 1))
            assertNull(policy.onboardingStep(OnboardingStatus.IN_PROGRESS, false, 8))
            assertEquals(2, policy.restart())
            assertEquals(2, policy.onboardingStep(OnboardingStatus.IN_PROGRESS, false, 2))
        }
        assertFalse(AppLaunchPolicy.accepts(Intent(Intent.ACTION_MAIN)))
    }

    @Test fun recreationResumesSavedProgressButLauncherDoesNotOverwriteIt() {
        val restored = AppLaunchPolicy(null)
        assertEquals(8, restored.onboardingStep(OnboardingStatus.IN_PROGRESS, false, 8))
        assertEquals(8, recreate(restored).onboardingStep(OnboardingStatus.IN_PROGRESS, false, 8))
        val launcher = AppLaunchPolicy(launcher())
        assertNull(launcher.onboardingStep(OnboardingStatus.IN_PROGRESS, false, 8))
        val recreated = recreate(launcher)
        assertNull(recreated.onboardingStep(OnboardingStatus.IN_PROGRESS, false, 8))
        assertEquals(2, recreated.restart())
        assertEquals(2, recreated.onboardingStep(OnboardingStatus.IN_PROGRESS, false, 2))
    }

    @Test fun delayedPersistenceAndRecreationCannotReactivateOnboardingAfterExternalEntry() {
        val policy = AppLaunchPolicy(launcher())
        assertEquals(1, policy.onboardingStep(null, false, 1))
        var route = "chat"
        var active = true
        policy.consume(Intent(Intent.ACTION_PROCESS_TEXT), "config", { active = false }, { route = "projects" })
        repeat(3) { assertNull(policy.onboardingStep(OnboardingStatus.IN_PROGRESS, active, 8)) }
        val recreated = recreate(policy)
        assertNull(recreated.onboardingStep(OnboardingStatus.IN_PROGRESS, false, 8))
        assertEquals("chat", route)
        assertFalse(active)
    }

    @Suppress("UNCHECKED_CAST")
    @Test fun activityIntentReceiverQueuesLauncherAndPreservesExplicitIntents() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val pending = MainActivity::class.java.getDeclaredField("pendingExternalIntent").apply { isAccessible = true }
            .get(activity) as MutableStateFlow<Intent?>
        val cold = MainActivity::class.java.getDeclaredMethod("handleLaunchIntent", Intent::class.java).apply { isAccessible = true }
        val warm = MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java).apply { isAccessible = true }
        for (intent in listOf(launcher(), Intent(Intent.ACTION_PROCESS_TEXT).putExtra(Intent.EXTRA_PROCESS_TEXT, "text"),
            Intent(WidgetActions.ACTION_OPEN_CHAT), Intent("com.aharou.intent.NEW_CHAT"))) {
            cold.invoke(activity, intent)
            assertSame(intent, pending.value)
            pending.value = null
            warm.invoke(activity, intent)
            assertSame(intent, pending.value)
            assertSame(intent, activity.intent)
        }
    }
}
