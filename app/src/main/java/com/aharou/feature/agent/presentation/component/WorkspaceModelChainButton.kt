package com.aharou.feature.agent.presentation.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.aharou.R
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

@Composable
internal fun WorkspaceModelChainSheet(
    workspace: String,
    providers: List<AIProviderConfig>,
    metadata: Map<String, ModelMetadata>,
    currentProviderId: String,
    currentModel: String,
    onSelectModel: (String, String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: WorkspaceModelChainViewModel = hiltViewModel()
) {
    key(workspace) {
        var chain by remember { mutableStateOf(emptyList<FallbackTarget>()) }
        var loading by remember { mutableStateOf(true) }
        var busy by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(workspace) {
            try { chain = viewModel.repository.get(workspace) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { loading = false }
        }
        ModelSelectionSheet(
            title = stringResource(R.string.workspace_model_chain_title),
            noModelsText = stringResource(R.string.chat_no_models_hint),
            providers = providers, currentProviderId = currentProviderId,
            currentModel = currentModel, modelMetadata = metadata,
            orderedSelection = chain.map { it.providerId to it.model },
            selectionEnabled = !loading && !busy && !failed,
            onSelect = { provider, model ->
                val target = FallbackTarget(provider, model)
                chain = if (target in chain) chain - target else chain + target
            },
            onClear = if (!loading && !busy && !failed) ({ chain = emptyList() }) else null,
            onDismiss = { if (!busy) onDismiss() },
            footer = {
                Text(stringResource(R.string.workspace_model_chain_select_hint), style = MaterialTheme.typography.bodySmall)
                if (failed) Text(stringResource(R.string.workspace_model_chain_error), color = MaterialTheme.colorScheme.error)
                TextButton(enabled = !loading && !busy && !failed, onClick = {
                    scope.launch {
                        busy = true
                        try {
                            viewModel.repository.set(workspace, chain)
                            chain.firstOrNull()?.let { onSelectModel(it.providerId, it.model) }
                            onDismiss()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { failed = true }
                        finally { busy = false }
                    }
                }) { Text(stringResource(if (loading) R.string.workspace_model_chain_loading else R.string.common_save)) }
            }
        )
    }
}
