package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aharou.R
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.memory.MemoryProposal
import com.aharou.feature.agent.domain.memory.MemoryProposalOutcome
import com.aharou.feature.settings.presentation.MemorySettingsController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@HiltViewModel
class MemoryProposalViewModel @Inject constructor(private val controller: MemorySettingsController) : ViewModel() {
    val proposals get() = controller.proposals
    var applying by mutableStateOf(false)
        private set
    var outcome by mutableStateOf<MemoryProposalOutcome?>(null)
        private set

    fun reject(id: String) = controller.rejectProposal(id)
    fun approve(proposal: MemoryProposal) {
        if (applying) return
        applying = true
        viewModelScope.launch {
            try {
                outcome = controller.approveProposal(proposal.id, proposal.revision)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Do not log the exception body: filesystem errors can carry private paths or draft text.
                FileLogger.w("MemoryProposal", "Approval failed (${e.javaClass.simpleName})")
                outcome = MemoryProposalOutcome.FAILED
            } finally {
                applying = false
            }
        }
    }
}

/** Reuses the settings maintenance area without modifying the chat panel or its ViewModel. */
@Composable
fun MemoryProposalEntry(viewModel: MemoryProposalViewModel = hiltViewModel()) {
    val proposals by viewModel.proposals.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<MemoryProposal?>(null) }
    Column {
        TextButton(onClick = { selected = proposals.firstOrNull() }, enabled = proposals.isNotEmpty()) {
            Text(stringResource(R.string.memory_proposals_pending, proposals.size))
        }
        viewModel.outcome?.let { outcome ->
            Text(stringResource(when (outcome) {
                MemoryProposalOutcome.APPLIED -> R.string.memory_proposal_applied
                MemoryProposalOutcome.STALE -> R.string.memory_proposal_stale
                MemoryProposalOutcome.MISSING -> R.string.memory_proposal_missing
                MemoryProposalOutcome.FAILED -> R.string.memory_proposal_failed
            }))
        }
    }
    selected?.let { draft ->
        AlertDialog(
            onDismissRequest = { if (!viewModel.applying) selected = null },
            title = { Text(stringResource(R.string.memory_proposal_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.memory_proposal_notice))
                    Text(stringResource(R.string.memory_proposal_before))
                    draft.inputs.forEach { input ->
                        Text("${input.scope} ${input.file?.absolutePath}\n${input.name} [${input.version}]\n${input.description}\n${input.content}")
                    }
                    Text(stringResource(R.string.memory_proposal_after))
                    Text(draft.payload)
                }
            },
            confirmButton = {
                TextButton(enabled = !viewModel.applying, onClick = { viewModel.approve(draft); selected = null }) {
                    Text(stringResource(R.string.memory_proposal_approve))
                }
            },
            dismissButton = {
                TextButton(enabled = !viewModel.applying, onClick = { viewModel.reject(draft.id); selected = null }) {
                    Text(stringResource(R.string.memory_proposal_reject))
                }
            },
        )
    }
}
