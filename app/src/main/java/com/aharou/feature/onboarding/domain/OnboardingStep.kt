package com.aharou.feature.onboarding.domain

import androidx.annotation.StringRes
import com.aharou.R

enum class OnboardingStep(
    val persistedIndex: Int,
    val route: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val descRes: Int
) {
    ENTER_SETTINGS(
        route = "projects",
        persistedIndex = 2,
        titleRes = R.string.onboarding_enter_settings_title,
        descRes = R.string.onboarding_enter_settings_desc
    ),
    CONFIG_PROVIDER(
        route = "config",
        persistedIndex = 3,
        titleRes = R.string.onboarding_config_provider_title,
        descRes = R.string.onboarding_config_provider_desc
    ),
    PROVIDER_ADD(
        route = "config",
        persistedIndex = 4,
        titleRes = R.string.onboarding_provider_add_title,
        descRes = R.string.onboarding_provider_add_desc
    ),
    PROVIDER_CONFIG_INFO(
        route = "config",
        persistedIndex = 5,
        titleRes = R.string.onboarding_provider_config_info_title,
        descRes = R.string.onboarding_provider_config_info_desc
    ),
    PROVIDER_FETCH_MODELS(
        route = "config",
        persistedIndex = 6,
        titleRes = R.string.onboarding_provider_fetch_models_title,
        descRes = R.string.onboarding_provider_fetch_models_desc
    ),
    SIMULATE_FETCH_DIALOG(
        route = "config",
        persistedIndex = 7,
        titleRes = R.string.onboarding_provider_select_fetched_model_title,
        descRes = R.string.onboarding_provider_select_fetched_model_desc
    ),
    OPEN_MODEL_PICKER(
        route = "chat",
        persistedIndex = 8,
        titleRes = R.string.onboarding_open_model_picker_title,
        descRes = R.string.onboarding_open_model_picker_desc
    ),
    SIMULATE_CHOOSE_MODEL(
        route = "chat",
        persistedIndex = 9,
        titleRes = R.string.onboarding_choose_model_in_sheet_title,
        descRes = R.string.onboarding_choose_model_in_sheet_desc
    ),
    SEND_MESSAGE(
        route = "chat",
        persistedIndex = 10,
        titleRes = R.string.onboarding_send_message_title,
        descRes = R.string.onboarding_send_message_desc
    );

    /** 当前步的序号（1-based）。 */
    val stepIndex: Int get() = ordinal + 1

    /** 是否是最后一步。 */
    val isLastStep: Boolean get() = this == entries.last()

    companion object {
        val totalSteps: Int get() = entries.size
        val all: List<OnboardingStep> = entries
    }
}
