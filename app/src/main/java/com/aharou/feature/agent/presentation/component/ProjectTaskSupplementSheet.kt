package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppTextField
import com.aharou.feature.agent.domain.task.ProjectTask

/** Long-press menu shared by tree rows and map nodes; a null task stands for the project root. */
@Composable
internal fun ProjectTaskNodeMenu(expanded: Boolean, onDismiss: () -> Unit, onSupplement: () -> Unit, onAddChild: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(stringResource(R.string.project_node_supplement)) }, onClick = onSupplement,
            modifier = Modifier.testTag("project_node_supplement"))
        DropdownMenuItem(text = { Text(stringResource(R.string.project_node_add_child)) }, onClick = onAddChild,
            modifier = Modifier.testTag("project_node_add_child"))
    }
}

/** Free-text supplement for one task; the AI decides which tasks it affects and the flow continues by itself. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectTaskSupplementSheet(task: ProjectTask, busy: Boolean, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AdaptiveModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(Spacing.lg)
            .testTag("project_supplement_sheet"), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(stringResource(R.string.project_node_supplement), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.project_supplement_target, task.title), maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium)
            Text(stringResource(R.string.project_supplement_hint), style = MaterialTheme.typography.bodySmall)
            AppTextField(text, { text = it }, label = stringResource(R.string.project_supplement_label), minLines = 4, singleLine = false)
            Button(onClick = { onSubmit(text.trim()) }, enabled = text.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("project_supplement_submit")) {
                Text(stringResource(R.string.project_supplement_submit))
            }
        }
    }
}
