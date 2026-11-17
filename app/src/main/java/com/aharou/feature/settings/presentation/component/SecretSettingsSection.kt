package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.feature.vault.presentation.VaultSection

@Composable
internal fun SecretSettingsSection(initialEnvironment: Boolean = false) {
    var environment by rememberSaveable(initialEnvironment) { mutableStateOf(initialEnvironment) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = Spacing.lg)) {
            FilterChip(selected = !environment, onClick = { environment = false },
                label = { Text(stringResource(R.string.vault_values_tab)) },
                modifier = Modifier.testTag("secret_vault_tab"))
            FilterChip(selected = environment, onClick = { environment = true },
                label = { Text(stringResource(R.string.envvars_title)) },
                modifier = Modifier.testTag("secret_env_tab"))
        }
        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
            if (environment) EnvVarsSection() else VaultSection()
        }
    }
}
