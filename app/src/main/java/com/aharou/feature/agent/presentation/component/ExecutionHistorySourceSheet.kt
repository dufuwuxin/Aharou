package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.feature.agent.domain.context.ContextLibraryPlanner
import com.aharou.feature.agent.presentation.ExecutionHistorySource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExecutionHistorySourceSheet(source: ExecutionHistorySource, onDismiss: () -> Unit) {
    val blocks = remember(source) {
        listOfNotNull(source.message.content, source.message.toolArgs, source.calls, source.message.reasoning)
            .filter(String::isNotBlank).map(ContextLibraryPlanner::safe).flatMap { it.chunked(2_000) }
    }
    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).testTag("execution_source_sheet")) {
            Text(stringResource(R.string.execution_tree_source_title), Modifier.padding(horizontal = Spacing.lg),
                style = MaterialTheme.typography.titleLarge)
            Text(source.message.id, Modifier.padding(horizontal = Spacing.lg), style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.conversation_map_close)) }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (blocks.isEmpty()) item { Text(stringResource(R.string.conversation_map_no_text)) }
                itemsIndexed(blocks) { _, block -> SelectionContainer { Text(block, style = MaterialTheme.typography.bodyMedium) } }
            }
        }
    }
}
