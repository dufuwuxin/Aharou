package com.aharou.feature.settings.data

import android.content.Context
import com.aharou.R
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.core.translation.OfflineTranslation
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfigTranslationFields @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val translation: OfflineTranslation,
) {
    fun registerInto(registry: ConfigRegistry) {
        registry.register(ClosureField(
            path = "translation.enabled",
            displayName = context.getString(R.string.translation_title),
            description = context.getString(R.string.translation_description),
            valueSchema = ConfigSchema.Bool,
            reader = { ConfigValue.Bool(translation.enabled.value) },
            writer = { translation.setEnabled((it as ConfigValue.Bool).value) },
        ))
        registry.register(ClosureField(
            path = "translation.download_model",
            displayName = context.getString(R.string.translation_download),
            description = context.getString(R.string.translation_download_description),
            valueSchema = ConfigSchema.Bool,
            reader = { ConfigValue.Bool(false) },
            writer = { if ((it as ConfigValue.Bool).value) translation.prepareModel(retry = true) },
        ))
    }
}
