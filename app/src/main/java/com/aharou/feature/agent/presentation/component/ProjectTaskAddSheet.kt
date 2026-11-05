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

/**
 * One-line task description; the AI decides placement, dependencies and members.
 * [parentTitle] is only displayed; the caller passes the chosen parent task to the planner.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectTaskAddSheet(parentTitle: String?, busy: Boolean, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val description = text.trim()
    AdaptiveModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(Spacing.lg)
            .testTag("project_add_task_sheet"), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Text(stringResource(if (parentTitle == null) R.string.project_add_task else R.string.project_node_add_child),
                style = MaterialTheme.typography.titleLarge)
            if (parentTitle != null) Text(stringResource(R.string.project_add_task_under, parentTitle), maxLines = 2,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
            Text(stringResource(R.string.project_add_task_hint), style = MaterialTheme.typography.bodySmall)
            AppTextField(text, { text = it }, label = stringResource(R.string.project_add_task_label), minLines = 2, singleLine = false)
            Button(onClick = { onSubmit(description) }, enabled = description.isNotEmpty() && !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("project_add_task_submit")) {
                Text(stringResource(R.string.project_add_task_submit))
            }
        }
    }
}
