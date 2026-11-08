package com.aharou.feature.settings.presentation.component

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.feature.agent.domain.context.ContextCategory
import com.aharou.feature.agent.domain.context.ContextEntry
import com.aharou.feature.agent.domain.context.ContextKind
import com.aharou.feature.agent.domain.context.ContextLoad
import com.aharou.feature.agent.domain.memory.MemoryScope
import compose.icons.FeatherIcons
import compose.icons.feathericons.BookOpen
import compose.icons.feathericons.FileText

@StringRes
internal fun ContextLoad.label(): Int = when (this) {
    ContextLoad.FULL -> R.string.context_load_full
    ContextLoad.INDEX -> R.string.context_load_index
    ContextLoad.SEARCH -> R.string.context_load_search
    ContextLoad.OFF -> R.string.context_load_off
}

@StringRes
internal fun ContextLoad.explanation(): Int = when (this) {
    ContextLoad.FULL -> R.string.context_load_full_help
    ContextLoad.INDEX -> R.string.context_load_index_help
    ContextLoad.SEARCH -> R.string.context_load_search_help
    ContextLoad.OFF -> R.string.context_load_off_help
}

@StringRes
internal fun ContextKind.label(): Int = when (this) {
    ContextKind.INSTRUCTION -> R.string.context_kind_instruction
    ContextKind.REFERENCE -> R.string.context_kind_reference
}

@StringRes
internal fun ContextCategory.label(): Int = when (this) {
    ContextCategory.RULE -> R.string.context_category_rule
    ContextCategory.TEMPORARY -> R.string.context_category_temporary
    ContextCategory.LONG_TERM -> R.string.context_category_long_term
    ContextCategory.INJECTED -> R.string.context_category_injected
}

@StringRes
internal fun ContextCategory.explanation(): Int = when (this) {
    ContextCategory.RULE -> R.string.context_category_rule_help
    ContextCategory.TEMPORARY -> R.string.context_category_temporary_help
    ContextCategory.LONG_TERM -> R.string.context_category_long_term_help
    ContextCategory.INJECTED -> R.string.context_category_injected_help
}

@StringRes
internal fun MemoryScope.label(): Int = when (this) {
    MemoryScope.GLOBAL -> R.string.context_scope_global
    MemoryScope.PROJECT -> R.string.context_scope_project
}

@Composable
internal fun ContextOverview(entries: List<ContextEntry>) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(stringResource(R.string.context_workspace_heading), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.context_workspace_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(shape = RoundedCornerShape(Radius.mdLarge), color = MaterialTheme.colorScheme.primaryContainer) {
                Text(entries.size.toString(), modifier = Modifier.padding(Spacing.lg), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        BoxWithConstraints {
            val columns = if (maxWidth >= 600.dp) 4 else 2
            val modes = ContextCategory.entries
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                modes.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        row.forEach { mode ->
                            Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(Radius.mdLarge), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                                Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                    Text(entries.count { it.category == mode }.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    Text(stringResource(mode.label()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
        val pending = entries.count { it.pending }
        if (pending > 0) ContextNotice(stringResource(R.string.context_pending_count, pending))
    }
}

@Composable
internal fun ContextNotice(text: String, error: Boolean = false) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.mdLarge),
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
    ) {
        Text(text, modifier = Modifier.padding(Spacing.md), style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
internal fun ContextChoiceRow(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, enabled: Boolean = true) {
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        labels.forEachIndexed { index, label ->
            FilterChip(selected = selected == index, onClick = { onSelect(index) }, enabled = enabled, label = { Text(label) })
        }
    }
}

@Composable
internal fun ContextEntryCard(entry: ContextEntry, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.mdLarge),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Icon(if (entry.kind == ContextKind.INSTRUCTION) FeatherIcons.FileText else FeatherIcons.BookOpen,
                    contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text(com.aharou.core.translation.rememberTranslatedText(entry.title), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(com.aharou.core.translation.rememberTranslatedText(entry.description.ifBlank { entry.content.lineSequence().firstOrNull().orEmpty() }),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Surface(shape = RoundedCornerShape(Radius.sm), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)) {
                    Text(stringResource(entry.loading.label()), modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Text(stringResource(R.string.context_entry_metadata, stringResource(entry.scope.label()), stringResource(entry.kind.label())),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (entry.pending) Text(stringResource(R.string.context_pending), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
