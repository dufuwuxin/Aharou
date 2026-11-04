package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.feature.settings.data.repository.WorkspaceModelChainRepository
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.FallbackTarget
import com.aharou.feature.settings.domain.model.ModelMetadata
import com.aharou.feature.settings.presentation.component.ModelSelectionSheet
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WorkspaceModelChainViewModel @Inject constructor(val repository: WorkspaceModelChainRepository) : ViewModel()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspaceModelChainButton(
    workspace: String,
    providers: List<AIProviderConfig>,
    metadata: Map<String, ModelMetadata>,
    viewModel: WorkspaceModelChainViewModel = hiltViewModel()
) {
    var open by remember(workspace) { mutableStateOf(false) }
    var adding by remember(workspace) { mutableStateOf(false) }
    var chain by remember(workspace) { mutableStateOf(emptyList<FallbackTarget>()) }
    var busy by remember(workspace) { mutableStateOf(false) }
    var failed by remember(workspace) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    TextButton(enabled = workspace.isNotBlank() && !busy, onClick = {
        scope.launch {
            busy = true
            failed = false
            try { chain = viewModel.repository.get(workspace); open = true }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { failed = true }
            finally { busy = false }
        }
    }) { Text(stringResource(R.string.workspace_model_chain_short)) }
    if (failed && !open) Text(stringResource(R.string.workspace_model_chain_error), color = MaterialTheme.colorScheme.error)
    if (open && !adding) {
        AdaptiveModalBottomSheet(onDismissRequest = { if (!busy) open = false }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(stringResource(R.string.workspace_model_chain_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.workspace_model_chain_hint))
                chain.forEachIndexed { index, target ->
                    Text("${index + 1}. ${providers.firstOrNull { it.id == target.providerId }?.name ?: target.providerId} / ${target.model}")
                    Row {
                        TextButton(enabled = !busy && index > 0, onClick = {
                            chain = chain.toMutableList().apply { add(index - 1, removeAt(index)) }
                        }) { Text(stringResource(R.string.workspace_model_chain_up)) }
                        TextButton(enabled = !busy && index < chain.lastIndex, onClick = {
                            chain = chain.toMutableList().apply { add(index + 1, removeAt(index)) }
                        }) { Text(stringResource(R.string.workspace_model_chain_down)) }
                        TextButton(enabled = !busy, onClick = { chain = chain.filterIndexed { i, _ -> i != index } }) {
                            Text(stringResource(R.string.common_delete))
                        }
                    }
                }
                if (failed) Text(stringResource(R.string.workspace_model_chain_error), color = MaterialTheme.colorScheme.error)
                TextButton(enabled = !busy, onClick = { adding = true }) { Text(stringResource(R.string.workspace_model_chain_add)) }
                TextButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        failed = false
                        try { viewModel.repository.set(workspace, chain); open = false }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { failed = true }
                        finally { busy = false }
                    }
                }) { Text(stringResource(R.string.common_save)) }
            }
        }
    }
    if (open && adding) {
        ModelSelectionSheet(
            title = stringResource(R.string.workspace_model_chain_add),
            noModelsText = stringResource(R.string.workspace_model_chain_empty),
            providers = providers, currentProviderId = "", currentModel = "", modelMetadata = metadata,
            onSelect = { provider, model -> chain = (chain + FallbackTarget(provider, model)).distinct(); adding = false },
            onClear = null, onDismiss = { adding = false }
        )
    }
}
