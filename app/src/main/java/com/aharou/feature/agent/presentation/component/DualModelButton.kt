package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppSwitch
import com.aharou.feature.agent.data.repository.DualModelSettingsRepository
import com.aharou.feature.agent.domain.model.AgentMode
import com.aharou.feature.agent.domain.session.DualModelSettings
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.FallbackTarget
import com.aharou.feature.settings.domain.model.ModelMetadata
import com.aharou.feature.settings.presentation.component.ModelSelectionSheet
import com.aharou.feature.settings.presentation.component.SettingsGroup
import com.aharou.feature.settings.presentation.component.SettingsRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DualModelViewModel @Inject constructor(val repository: DualModelSettingsRepository) : ViewModel() {
    val reload = kotlinx.coroutines.flow.MutableStateFlow(0)
    fun retry() { reload.value += 1 }
}

private data class DualModelReadState(val settings: DualModelSettings? = null, val failed: Boolean = false)

@Composable
private fun dualModelState(sessionId: String?, viewModel: DualModelViewModel): DualModelReadState {
    val reload by viewModel.reload.collectAsStateWithLifecycle()
    val flow = remember(sessionId, viewModel, reload) {
        if (sessionId == null) flowOf(DualModelReadState())
        else viewModel.repository.observe(sessionId).map { DualModelReadState(it) }
            .catch { emit(DualModelReadState(failed = true)) }
    }
    return key(sessionId) { flow.collectAsStateWithLifecycle(initialValue = DualModelReadState()).value }
}

@Composable
internal fun dualModelProvider(
    sessionId: String?,
    mode: AgentMode,
    providers: List<AIProviderConfig>,
    fallback: AIProviderConfig?,
    viewModel: DualModelViewModel = hiltViewModel()
): AIProviderConfig? {
    if (sessionId == null) return fallback
    val settings = dualModelState(sessionId, viewModel).settings ?: return null
    if (!settings.enabled) return fallback
    val target = if (mode == AgentMode.PLAN) settings.planning else settings.execution
    return target?.let { selected -> providers.firstOrNull { it.id == selected.providerId }
        ?.copy(selectedModel = selected.model) }
}

@Composable
internal fun dualModelSelectionHandler(
    sessionId: String?,
    mode: AgentMode,
    running: Boolean,
    onDefaultSelect: (String, String) -> Unit,
    viewModel: DualModelViewModel = hiltViewModel()
): (String, String) -> Unit {
    val state = dualModelState(sessionId, viewModel)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return { providerId, model ->
        val settings = state.settings
        when {
            sessionId == null || settings?.enabled == false -> onDefaultSelect(providerId, model)
            settings == null || state.failed || running -> android.widget.Toast.makeText(context,
                context.getString(if (running) R.string.dual_model_running else R.string.dual_model_error), android.widget.Toast.LENGTH_LONG).show()
            else -> scope.launch {
                try {
                    viewModel.repository.update(sessionId) { latest ->
                        check(latest == settings) { "Dual model settings changed while selecting" }
                        val target = FallbackTarget(providerId, model)
                        if (mode == AgentMode.PLAN) latest.copy(planning = target) else latest.copy(execution = target)
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { android.widget.Toast.makeText(context,
                    context.getString(R.string.dual_model_error), android.widget.Toast.LENGTH_LONG).show() }
            }
        }
    }
}

@Composable
internal fun ChatSessionModelControls(
    ghostMode: Boolean,
    onGhostChange: (Boolean) -> Unit,
    sessionId: String?,
    mode: AgentMode,
    providers: List<AIProviderConfig>,
    metadata: Map<String, ModelMetadata>,
    running: Boolean,
    onEnterPlan: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        GhostModeIndicator(isGhost = ghostMode, onToggle = onGhostChange)
        DualModelButton(sessionId, mode, providers, metadata, running, onEnterPlan)
    }
}

@Composable
internal fun DualModelButton(
    sessionId: String?,
    mode: AgentMode,
    providers: List<AIProviderConfig>,
    metadata: Map<String, ModelMetadata>,
    running: Boolean,
    onEnterPlan: () -> Unit,
    viewModel: DualModelViewModel = hiltViewModel()
) {
    if (sessionId == null) return
    key(sessionId) {
        val state = dualModelState(sessionId, viewModel)
        var open by remember { mutableStateOf(false) }
        TextButton(onClick = { if (state.failed) viewModel.retry(); open = true }) {
            Text(stringResource(when {
                state.failed -> R.string.dual_model_read_error
                state.settings == null -> R.string.dual_model_loading
                state.settings.enabled -> R.string.dual_model_on
                else -> R.string.dual_model_title
            }))
        }
        if (open) DualModelSheet(sessionId, mode, providers, metadata, running, onEnterPlan, viewModel) { open = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DualModelSheet(
    sessionId: String,
    mode: AgentMode,
    providers: List<AIProviderConfig>,
    metadata: Map<String, ModelMetadata>,
    running: Boolean,
    onEnterPlan: () -> Unit,
    viewModel: DualModelViewModel,
    onDismiss: () -> Unit
) {
    val state = dualModelState(sessionId, viewModel)
    var draft by remember { mutableStateOf<DualModelSettings?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var original by remember { mutableStateOf<DualModelSettings?>(null) }
    var selectingPlanning by remember { mutableStateOf<Boolean?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.settings) { if (!dirty) { draft = state.settings; original = state.settings } }
    val settings = draft
    val editable = !running && !busy && settings != null && !state.failed
    val selectable = providers.filter { it.hasUsableApiKey }
    fun label(target: FallbackTarget?): String? = target?.let {
        "${providers.firstOrNull { provider -> provider.id == it.providerId }?.name ?: it.providerId} / ${it.model}"
    }
    if (selectingPlanning == null) {
        AdaptiveModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }) {
            Column(
                Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                Text(stringResource(R.string.dual_model_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.dual_model_hint), style = MaterialTheme.typography.bodyMedium)
                SettingsGroup {
                    SettingsRow(title = stringResource(R.string.dual_model_enable), trailing = {
                        AppSwitch(
                            checked = settings?.enabled == true,
                            enabled = editable && (settings?.enabled == true || settings?.isConfigured == true),
                            onCheckedChange = { enabled -> draft = settings?.copy(enabled = enabled); dirty = true }
                        )
                    })
                    SettingsRow(
                        title = stringResource(R.string.dual_model_planning),
                        subtitle = label(settings?.planning) ?: stringResource(R.string.dual_model_unselected),
                        enabled = editable, onClick = { selectingPlanning = true }
                    )
                    SettingsRow(
                        title = stringResource(R.string.dual_model_execution),
                        subtitle = label(settings?.execution) ?: stringResource(R.string.dual_model_unselected),
                        enabled = editable, onClick = { selectingPlanning = false }
                    )
                }
                if (settings != null && !settings.isConfigured) Text(stringResource(R.string.dual_model_configure_first))
                if (running) Text(stringResource(R.string.dual_model_running))
                if (state.failed || failed) Text(stringResource(R.string.dual_model_error), color = MaterialTheme.colorScheme.error)
                TextButton(enabled = editable && dirty, onClick = {
                    val saved = settings ?: return@TextButton
                    val expected = original
                    scope.launch {
                        busy = true
                        failed = false
                        try {
                            viewModel.repository.update(sessionId) { latest ->
                                check(latest == expected) { "Dual model settings changed while editing" }
                                saved
                            }
                            dirty = false
                            onDismiss()
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { failed = true }
                        finally { busy = false }
                    }
                }) { Text(stringResource(R.string.common_save)) }
                if (state.settings?.enabled == true && !dirty && mode != AgentMode.PLAN) {
                    TextButton(enabled = editable, onClick = { onEnterPlan(); onDismiss() }) {
                        Text(stringResource(R.string.dual_model_enter_plan))
                    }
                }
            }
        }
    } else {
        val planning = selectingPlanning == true
        val selected = if (planning) settings?.planning else settings?.execution
        ModelSelectionSheet(
            title = stringResource(if (planning) R.string.dual_model_planning else R.string.dual_model_execution),
            noModelsText = stringResource(R.string.dual_model_no_models),
            providers = selectable,
            currentProviderId = selected?.providerId.orEmpty(), currentModel = selected?.model.orEmpty(),
            modelMetadata = metadata,
            onSelect = { providerId, model ->
                if (!running && !busy) {
                    val target = FallbackTarget(providerId, model)
                    draft = if (planning) settings?.copy(planning = target) else settings?.copy(execution = target)
                    dirty = true
                }
                selectingPlanning = null
            },
            onClear = null, onDismiss = { selectingPlanning = null }
        )
    }
}
