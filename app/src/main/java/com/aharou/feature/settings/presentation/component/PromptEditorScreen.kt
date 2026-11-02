package com.aharou.feature.settings.presentation.component

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppSwitch
import com.aharou.core.ui.AppTextField
import com.aharou.feature.agent.domain.prompt.PromptFragment
import com.aharou.feature.agent.domain.prompt.PromptFragmentSource
import com.aharou.feature.agent.domain.subagent.InjectPart
import com.aharou.feature.agent.domain.subagent.KeywordInjection
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft

/**
 * 提示词片段新建/编辑页：自带顶栏（返回 + 右上角保存），与 [SubAgentEditorScreen] 一致，
 * 不能嵌进设置页的 Scaffold。
 *
 * 编号即身份，决定片段在系统提示词里的位置；可改。作用域选项目或全局，决定覆盖写到哪一层。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PromptEditorScreen(
    isNew: Boolean,
    initialNumber: Int,
    initialTitle: String,
    initialDescription: String,
    initialContent: String,
    initialScope: PromptFragmentSource,
    initialKeywordInjections: List<KeywordInjection> = emptyList(),
    initialInjectParts: Set<InjectPart> = emptySet(),
    hasWorkspace: Boolean,
    onSave: (
        number: Int,
        title: String,
        scope: PromptFragmentSource,
        content: String,
        keywordInjections: List<KeywordInjection>,
        injectParts: Set<InjectPart>
    ) -> Unit,
    onNavigateBack: () -> Unit
) {
    var numberText by rememberSaveable { mutableStateOf(if (isNew) "" else "%02d".format(initialNumber)) }
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    var description by rememberSaveable { mutableStateOf(initialDescription) }
    var content by rememberSaveable { mutableStateOf(initialContent) }
    var scopeIsProject by rememberSaveable { mutableStateOf(initialScope == PromptFragmentSource.PROJECT) }
    // 关键词注入：前端存成「触发词1,触发词2|正文\n...」的明文，save 时拆开成 KeywordInjection。
    var keywordInjectionsRaw by rememberSaveable {
        mutableStateOf(initialKeywordInjections.joinToString("\n") { inj ->
            inj.keywords.joinToString(",") + "|" + inj.injectContent
        })
    }
    // 成员注入：Set<InjectPart> 进不了 Bundle，存 token 字符串集合；切页或旋转后仍能还原。
    var injectTokens by rememberSaveable {
        mutableStateOf(initialInjectParts.map { it.token })
    }

    val number = numberText.toIntOrNull()
    val numberValid = number != null && number in 0..99
    val canSave = numberValid && title.isNotBlank()

    BackHandler { onNavigateBack() }

    Scaffold(
        containerColor = settingsPageBackground(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (isNew) R.string.prompts_editor_new else R.string.prompts_editor_edit
                        )
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = settingsPageBackground(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    TextButton(
                        enabled = canSave,
                        onClick = {
                            val kwInjs = keywordInjectionsRaw.trim().split("\n").mapNotNull { line ->
                                val pipe = line.indexOf('|')
                                if (pipe < 0) return@mapNotNull null
                                val kws = line.substring(0, pipe).split(',').map { it.trim() }.filter { it.isNotEmpty() }
                                val text = line.substring(pipe + 1).trim()
                                if (kws.isEmpty() || text.isEmpty()) return@mapNotNull null
                                KeywordInjection(kws, text)
                            }
                            val injParts = injectTokens.mapNotNull { InjectPart.fromToken(it) }.toSet()
                            onSave(
                                number ?: 0,
                                title.trim(),
                                if (scopeIsProject) PromptFragmentSource.PROJECT else PromptFragmentSource.GLOBAL,
                                PromptFragment.composeContent(description, content),
                                kwInjs,
                                injParts
                            )
                        }
                    ) {
                        Text(stringResource(R.string.common_save))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            SettingsGroupHeader(text = stringResource(R.string.prompts_field_basic))
            SettingsGroup {
                Column(
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    AppTextField(
                        value = numberText,
                        onValueChange = { new -> numberText = new.filter { it.isDigit() }.take(2) },
                        label = stringResource(R.string.prompts_field_number),
                        placeholder = stringResource(R.string.prompts_field_number_placeholder),
                        singleLine = true
                    )
                    AppTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = stringResource(R.string.prompts_field_title),
                        placeholder = stringResource(R.string.prompts_field_title_placeholder),
                        singleLine = true
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        Text(
                            text = stringResource(R.string.subagent_editor_scope),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilterChip(
                            selected = scopeIsProject,
                            enabled = hasWorkspace,
                            onClick = { scopeIsProject = true },
                            label = { Text(stringResource(R.string.subagent_scope_project)) }
                        )
                        FilterChip(
                            selected = !scopeIsProject,
                            onClick = { scopeIsProject = false },
                            label = { Text(stringResource(R.string.subagent_scope_global)) }
                        )
                    }
                }
            }

            SettingsGroupHeader(text = stringResource(R.string.prompts_field_summary))
            SettingsGroup {
                AppTextField(
                    value = description,
                    onValueChange = { description = it },
                    placeholder = stringResource(R.string.prompts_field_summary_placeholder),
                    singleLine = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = 12.dp)
                )
            }

            SettingsGroupHeader(text = stringResource(R.string.prompts_field_content))
            SettingsGroup {
                AppTextField(
                    value = content,
                    onValueChange = { content = it },
                    placeholder = stringResource(R.string.prompts_field_content_placeholder),
                    singleLine = false,
                    minLines = 8,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = 12.dp)
                )
            }

            // 关键词注入：触发词与正文一换行一条；格式「触发词1,触发词2|正文」
            SettingsGroupHeader(text = stringResource(R.string.subagent_editor_keyword_inject))
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.subagent_editor_keyword_inject),
                    subtitle = stringResource(R.string.subagent_editor_keyword_inject_hint),
                )
                AppTextField(
                    value = keywordInjectionsRaw,
                    onValueChange = { keywordInjectionsRaw = it },
                    label = stringResource(R.string.subagent_editor_keyword_inject_label),
                    singleLine = false,
                    minLines = 5,
                    maxLines = 20,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
                )
            }

            // 成员注入：与 [SubAgentEditorScreen] 的注入分组风格保持一致（每行一个 part 带 hint + 开关）。
            SettingsGroupHeader(text = stringResource(R.string.subagent_inject))
            SettingsGroup {
                InjectPart.entries.forEachIndexed { index, part ->
                    if (index > 0) SettingsDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(part.labelRes()),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(part.hintRes()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        AppSwitch(
                            checked = part.token in injectTokens,
                            onCheckedChange = { checked ->
                                injectTokens = if (checked) {
                                    injectTokens + part.token
                                } else {
                                    injectTokens - part.token
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

// InjectPart 的 label/hint 字符串映射（与 SubAgentEditorScreen 保持一致）
private fun InjectPart.labelRes(): Int = when (this) {
    InjectPart.BASE -> R.string.subagent_inject_base
    InjectPart.MAIN_RULES -> R.string.subagent_inject_main_rules
    InjectPart.SKILLS -> R.string.subagent_inject_skills
    InjectPart.MEMORY -> R.string.subagent_inject_memory
    InjectPart.PROJECT_RULES -> R.string.subagent_inject_project_rules
}

private fun InjectPart.hintRes(): Int = when (this) {
    InjectPart.BASE -> R.string.subagent_inject_base_hint
    InjectPart.MAIN_RULES -> R.string.subagent_inject_main_rules_hint
    InjectPart.SKILLS -> R.string.subagent_inject_skills_hint
    InjectPart.MEMORY -> R.string.subagent_inject_memory_hint
    InjectPart.PROJECT_RULES -> R.string.subagent_inject_project_rules_hint
}
