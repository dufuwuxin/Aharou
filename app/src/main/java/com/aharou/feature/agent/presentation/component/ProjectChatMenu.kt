package com.aharou.feature.agent.presentation.component

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.aharou.R
import com.aharou.feature.agent.presentation.StewardPanelViewModel

internal val LocalProjectDiagnostics = staticCompositionLocalOf<(() -> Unit)?> { null }

internal val LocalProjectChatMenu = staticCompositionLocalOf<@Composable (() -> Unit) -> Unit> { {} }

@Composable
internal fun ProjectChatMenuHost(
    sessionId: String?,
    onTerminal: () -> Unit, onGit: () -> Unit, onBrowser: () -> Unit,
    onSandbox: () -> Unit, onShared: () -> Unit, onDiagnostics: () -> Unit,
    inputTokens: Int, outputTokens: Int,
    contextStatus: @Composable () -> Unit = {},
    unifiedDiagnostics: Boolean = false,
    content: @Composable () -> Unit
) {
    var stewardOpen by remember(sessionId) { mutableStateOf(false) }
    val stewardViewModel: StewardPanelViewModel = hiltViewModel()
    val menu: @Composable (() -> Unit) -> Unit = { dismiss ->
        HorizontalDivider()
        DropdownMenuItem(text = { Text(stringResource(R.string.steward_entry_title)) },
            enabled = sessionId != null, onClick = { dismiss(); stewardOpen = true })
        HorizontalDivider()
        listOf(
            R.string.chat_open_sandbox to onSandbox,
            R.string.chat_open_shared to onShared,
            R.string.session_diagnostics_title to onDiagnostics,
            R.string.chat_open_git to onGit,
            R.string.chat_open_terminal to onTerminal,
            R.string.chat_open_browser to onBrowser
        ).filterNot { unifiedDiagnostics && it.first == R.string.session_diagnostics_title }
            .forEach { (label, action) ->
            DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { dismiss(); action() })
        }
        ScheduledTaskPill(sessionId = sessionId)
    }
    CompositionLocalProvider(LocalProjectChatMenu provides menu, LocalProjectDiagnostics provides onDiagnostics.takeIf { unifiedDiagnostics }) {
        content()
    }
    key(sessionId) {
        if (stewardOpen && sessionId != null) StewardSheet(
            sessionId, stewardViewModel
        ) { stewardOpen = false }
    }
}
