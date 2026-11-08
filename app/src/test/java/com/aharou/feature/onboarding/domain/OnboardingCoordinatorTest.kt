package com.aharou.feature.onboarding.domain

import com.aharou.feature.onboarding.data.OnboardingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingCoordinatorTest {

    private val testScope = CoroutineScope(Dispatchers.Unconfined)

    @Test
    fun `initialize with IN_PROGRESS starts stateHolder`() {
        val stateHolder = OnboardingStateHolder()
        val persisted = mutableListOf<OnboardingStatus>()
        val coordinator = OnboardingCoordinator(
            stateHolder = stateHolder,
            onPersist = { persisted.add(it) },
            scope = testScope
        )

        coordinator.initialize(OnboardingStatus.IN_PROGRESS)
        assertTrue(stateHolder.isActive)
        assertEquals(OnboardingStep.ENTER_SETTINGS, stateHolder.state.value.step)
    }

    @Test
    fun `initialize with COMPLETED does not start stateHolder`() {
        val stateHolder = OnboardingStateHolder()
        val persisted = mutableListOf<OnboardingStatus>()
        val coordinator = OnboardingCoordinator(
            stateHolder = stateHolder,
            onPersist = { persisted.add(it) },
            scope = testScope
        )

        coordinator.initialize(OnboardingStatus.COMPLETED)
        assertFalse(stateHolder.isActive)
    }

    @Test
    fun `nextStep through to end persists completed`() {
        val stateHolder = OnboardingStateHolder()
        val persisted = mutableListOf<OnboardingStatus>()
        val coordinator = OnboardingCoordinator(
            stateHolder = stateHolder,
            onPersist = { persisted.add(it) },
            scope = testScope
        )

        coordinator.initialize(OnboardingStatus.IN_PROGRESS)
        // 依次推进所有步骤直到完成
        while (stateHolder.isActive) {
            coordinator.nextStep()
        }

        assertFalse(stateHolder.isActive)
        assertEquals(OnboardingStatus.COMPLETED, stateHolder.state.value.status)
        assertTrue(persisted.contains(OnboardingStatus.COMPLETED))
    }

    @Test
    fun `skip deactivates and persists skipped`() {
        val stateHolder = OnboardingStateHolder()
        val persisted = mutableListOf<OnboardingStatus>()
        val coordinator = OnboardingCoordinator(
            stateHolder = stateHolder,
            onPersist = { persisted.add(it) },
            scope = testScope
        )

        coordinator.initialize(OnboardingStatus.IN_PROGRESS)
        coordinator.skip()

        assertFalse(stateHolder.isActive)
        assertEquals(OnboardingStatus.SKIPPED, stateHolder.state.value.status)
        assertEquals(listOf(OnboardingStatus.SKIPPED), persisted)
    }

    @Test
    fun `resetAndStart activates and persists IN_PROGRESS`() {
        val stateHolder = OnboardingStateHolder()
        val persisted = mutableListOf<OnboardingStatus>()
        val coordinator = OnboardingCoordinator(
            stateHolder = stateHolder,
            onPersist = { persisted.add(it) },
            scope = testScope
        )

        coordinator.resetAndStart()

        assertTrue(stateHolder.isActive)
        assertEquals(OnboardingStep.ENTER_SETTINGS, stateHolder.state.value.step)
        assertEquals(listOf(OnboardingStatus.IN_PROGRESS), persisted)
    }

    @Test fun savedProgressRetainsThePreviousStableStepIds() {
        val expected = listOf(OnboardingStep.ENTER_SETTINGS, OnboardingStep.CONFIG_PROVIDER,
            OnboardingStep.PROVIDER_ADD, OnboardingStep.PROVIDER_CONFIG_INFO,
            OnboardingStep.PROVIDER_FETCH_MODELS, OnboardingStep.SIMULATE_FETCH_DIALOG,
            OnboardingStep.OPEN_MODEL_PICKER, OnboardingStep.SIMULATE_CHOOSE_MODEL, OnboardingStep.SEND_MESSAGE)
        expected.forEachIndexed { offset, step ->
            val state = OnboardingStateHolder()
            OnboardingCoordinator(state, {}, testScope).initialize(OnboardingStatus.IN_PROGRESS, offset + 2)
            assertEquals(step, state.state.value.step)
        }
    }

    @Test fun removedFirstStepAndUnknownProgressStartAtTheConfigurationEntry() {
        for (saved in listOf(0, 1, Int.MAX_VALUE)) {
            val state = OnboardingStateHolder()
            OnboardingCoordinator(state, {}, testScope).initialize(OnboardingStatus.IN_PROGRESS, saved)
            assertEquals(OnboardingStep.ENTER_SETTINGS, state.state.value.step)
        }
    }

    @Test fun progressPersistenceDoesNotUseTheNewDisplayOrdinal() {
        val state = OnboardingStateHolder()
        val steps = mutableListOf<Int>()
        val coordinator = OnboardingCoordinator(state, {}, testScope, { steps += it })
        coordinator.initialize(OnboardingStatus.IN_PROGRESS, 2)
        coordinator.nextStep()
        assertEquals(OnboardingStep.CONFIG_PROVIDER, state.state.value.step)
        assertEquals(2, state.state.value.step.stepIndex)
        assertEquals(listOf(3), steps)
    }

}
