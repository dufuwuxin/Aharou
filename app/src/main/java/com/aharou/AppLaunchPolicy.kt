package com.aharou

import android.content.Intent
import androidx.compose.runtime.saveable.Saver
import com.aharou.feature.onboarding.data.OnboardingStatus
import com.aharou.feature.onboarding.domain.OnboardingStep
import com.aharou.feature.widget.WidgetActions

internal class AppLaunchPolicy(initialIntent: Intent?, private var suppressAutomatic: Boolean = isExplicit(initialIntent)) {
    private var launcherEntry = isLauncher(initialIntent)

    fun onboardingStep(status: OnboardingStatus?, active: Boolean, savedStep: Int): Int? {
        if (launcherEntry && status == OnboardingStatus.IN_PROGRESS && savedStep > OnboardingStep.ENTER_SETTINGS.persistedIndex) {
            suppressAutomatic = true
        }
        if (active || suppressAutomatic || (status != null && status != OnboardingStatus.IN_PROGRESS)) return null
        return savedStep
    }

    fun restart(): Int {
        suppressAutomatic = false
        launcherEntry = false
        return OnboardingStep.ENTER_SETTINGS.persistedIndex
    }

    fun consume(intent: Intent, stepRoute: String, pauseOnboarding: () -> Unit, openProjects: () -> Unit) {
        if (isExplicit(intent) || (isLauncher(intent) && stepRoute != "projects")) {
            suppressAutomatic = true
            pauseOnboarding()
        }
        if (isLauncher(intent)) openProjects()
    }

    companion object {
        val StateSaver = Saver<AppLaunchPolicy, Boolean>(save = { it.suppressAutomatic }, restore = { AppLaunchPolicy(null, it) })
        private val externalActions = setOf(Intent.ACTION_PROCESS_TEXT, "com.aharou.intent.NEW_CHAT",
            WidgetActions.ACTION_OPEN_CHAT, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_VIEW)
        fun isLauncher(intent: Intent?): Boolean = intent?.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)
        fun isExplicit(intent: Intent?): Boolean = intent?.action in externalActions
        fun accepts(intent: Intent): Boolean = isLauncher(intent) || isExplicit(intent)
    }
}
