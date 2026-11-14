package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.config.TeamEditCapability
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppSwitch
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SpecialPermissionsViewModel @Inject constructor(private val workspaces: WorkspaceRepository) : ViewModel() {
    val address = TeamEditCapability.address
    fun toggle(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        if (enabled) TeamEditCapability.issue(workspaces.currentPath()) else TeamEditCapability.revoke()
    }
}

@Composable
internal fun SpecialPermissionsSection(onOpen: (SettingsSection) -> Unit, viewModel: SpecialPermissionsViewModel = hiltViewModel()) {
    val address by viewModel.address.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        SettingsGroup {
            SettingsRow(title = stringResource(R.string.vd_settings_title), onClick = { onOpen(SettingsSection.ShadowScreen) })
            SettingsDivider()
            SettingsRow(title = stringResource(R.string.config_audit_title), onClick = { onOpen(SettingsSection.ConfigAudit) })
            SettingsDivider()
            SettingsRow(title = stringResource(R.string.settings_app_permissions), onClick = { onOpen(SettingsSection.AppPermissions) })
        }
        SettingsGroup {
            SettingsRow(title = stringResource(R.string.team_capability_title), subtitle = stringResource(R.string.team_capability_scope),
                trailing = { AppSwitch(checked = address != null, onCheckedChange = { viewModel.toggle(it) }) })
            if (address != null) {
                Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(stringResource(R.string.team_capability_usage), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString("config action=get path=" + address))
                    }) { Text(stringResource(R.string.team_capability_copy)) }
                    Text(address.orEmpty(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
