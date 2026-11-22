package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.core.theme.semanticColors
import com.aharou.feature.agent.domain.model.AgentMode

@Composable
internal fun AgentModeSelector(mode: AgentMode, onSelect: (AgentMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val container = when (mode) {
        AgentMode.PLAN -> MaterialTheme.colorScheme.primaryContainer
        AgentMode.AUTO -> MaterialTheme.semanticColors.warningContainer
        AgentMode.ALLOW -> MaterialTheme.colorScheme.error
        AgentMode.BUILD -> MaterialTheme.semanticColors.success
    }
    val content = when (mode) {
        AgentMode.PLAN -> MaterialTheme.colorScheme.onPrimaryContainer
        AgentMode.AUTO -> MaterialTheme.semanticColors.onWarningContainer
        AgentMode.ALLOW -> MaterialTheme.colorScheme.onError
        AgentMode.BUILD -> MaterialTheme.semanticColors.onSuccess
    }
    Box {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(16.dp),
            color = container,
        ) {
            Text(mode.name, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = content))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(AgentMode.AUTO, AgentMode.ALLOW, AgentMode.PLAN, AgentMode.BUILD).forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(option.name, fontWeight = if (option == mode) FontWeight.Bold else FontWeight.Normal)
                            Text(stringResource(modeDescription(option)), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = { expanded = false; onSelect(option) },
                )
            }
        }
    }
}

private fun modeDescription(mode: AgentMode): Int = when (mode) {
    AgentMode.AUTO -> R.string.chat_mode_auto_description
    AgentMode.ALLOW -> R.string.chat_mode_allow_description
    AgentMode.PLAN -> R.string.chat_mode_plan_description
    AgentMode.BUILD -> R.string.chat_mode_build_description
}
