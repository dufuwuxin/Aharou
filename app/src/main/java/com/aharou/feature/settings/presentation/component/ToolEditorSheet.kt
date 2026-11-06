package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppTextField
import com.aharou.core.ui.SegmentedTabs
import com.aharou.feature.agent.domain.tool.custom.CustomCommandTemplate
import com.aharou.feature.agent.domain.tool.custom.CustomToolDefinition
import com.aharou.feature.agent.domain.tool.custom.ToolSchemaOverride
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.aharou.feature.agent.domain.tool.custom.CustomToolParameter
import com.aharou.feature.agent.domain.tool.custom.ToolInjectionMode
import com.aharou.feature.settings.presentation.ToolSource
import com.aharou.feature.settings.presentation.ToolUiEntry
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 编辑器里那份参数 JSON 的宽容解析器：忽略未知字段，手写多余键不会报错。 */
private val parametersJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    prettyPrint = true
}

private const val PARAMETERS_EXAMPLE = """[
  {
    "name": "path",
    "type": "string",
    "description": "要处理的文件路径",
    "required": true
  }
]"""

/**
 * 工具详情 / 编辑面板。
 *
 * - [entry] 为 null：新建自定义工具（名称可改）；
 * - [entry] 是自定义工具：名称锁定（模型按名字调用，改名等于换一个工具），其余可改；
 * - [entry] 是内置 / MCP 工具：schema 只可改元数据，不能改变执行参数结构。
 *
 * 版式照 `SkinEditorSheet`：`AdaptiveModalBottomSheet` + `AppTextField`，长表单外层加 `imePadding()`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolEditorSheet(
    entry: ToolUiEntry?,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSaveCustom: (
        name: String,
        description: String,
        parameters: List<CustomToolParameter>,
        command: String,
        timeoutSeconds: Int,
        mode: ToolInjectionMode,
        keywords: List<String>
    ) -> Unit,
    onSaveSettings: (name: String, description: String?, mode: ToolInjectionMode, keywords: List<String>, schemaJson: String?) -> Unit,
    template: CustomToolDefinition? = null,
    onSelectTemplate: (String) -> Unit
) {
    var choosingTemplate by remember { mutableStateOf(false) }
    val isNew = entry == null
    val isCustom = isNew || entry?.source == ToolSource.CUSTOM

    var name by remember(entry, template) { mutableStateOf(entry?.name ?: template?.name.orEmpty()) }
    var description by remember(entry, template) { mutableStateOf(entry?.description ?: template?.description.orEmpty()) }
    var parametersText by remember(entry, template) { mutableStateOf(entry?.parametersJson ?: template?.let { Json.encodeToString(it.parameters) }.orEmpty()) }
    var command by remember(entry, template) { mutableStateOf(entry?.command ?: template?.command.orEmpty()) }
    var timeoutText by remember(entry, template) {
        mutableStateOf((entry?.timeoutSeconds ?: template?.timeoutSeconds ?: CustomToolDefinition.DEFAULT_TIMEOUT_SECONDS).toString())
    }
    var mode by remember(entry, template) { mutableStateOf(entry?.injectionMode ?: template?.injectionMode ?: ToolInjectionMode.EVERY_ROUND) }
    var keywordsText by remember(entry, template) { mutableStateOf((entry?.keywords ?: template?.keywords).orEmpty().joinToString(", ")) }

    var schemaText by remember(entry, template) { mutableStateOf(entry?.schemaJson.orEmpty()) }
    val originalSchema = remember(entry, template) {
        runCatching {
            Gson().fromJson<Map<String, Any>>(entry?.originalSchemaJson.orEmpty(), object : TypeToken<Map<String, Any>>() {}.type)
        }.getOrNull()
    }
    val schemaValid = isCustom || (originalSchema != null && ToolSchemaOverride.isCompatible(originalSchema, schemaText))

    val parsedParameters = remember(parametersText) { parseParameters(parametersText) }
    val parametersError = isCustom && parametersText.isNotBlank() && parsedParameters == null
    val nameError = isCustom && name.isNotBlank() && !CustomToolDefinition.NAME_REGEX.matches(name.trim())
    val commandError = if (isCustom && command.isNotBlank()) commandProblemOrNull(command, parsedParameters) else null
    // 固定命令是合法的，但参数表非空而模板一个占位符都没用上时，参数一定不会生效——给个警告而不是静默。
    val commandWarning = if (
        isCustom &&
        commandError == null &&
        command.isNotBlank() &&
        !parsedParameters.orEmpty().isEmpty() &&
        CustomCommandTemplate.placeholders(command).isEmpty()
    ) {
        R.string.tools_command_no_placeholder
    } else {
        null
    }
    val canSave = !saving && schemaValid &&
        (!isCustom || (name.isNotBlank() && !nameError && !parametersError && commandProblemOrNull(command, parsedParameters) == null))

    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text(
                text = stringResource(
                    when {
                        isNew -> R.string.tools_editor_create_title
                        isCustom -> R.string.tools_editor_edit_title
                        else -> R.string.tools_editor_settings_title
                    }
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            if (isNew) {
                TextButton(onClick = { choosingTemplate = !choosingTemplate }, enabled = !saving) {
                    Text(stringResource(R.string.tools_templates_title))
                }
                if (choosingTemplate) {
                    Text(stringResource(R.string.tools_templates_hint), style = MaterialTheme.typography.bodySmall)
                    com.aharou.feature.agent.domain.tool.custom.PracticalToolTemplates.all.forEach { preset ->
                        TextButton(onClick = {
                            choosingTemplate = false
                            onSelectTemplate(preset.name)
                        }, enabled = !saving) { Text(preset.name) }
                    }
                }
            }
            if (isCustom) {
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.tools_name),
                    enabled = isNew && !saving,
                    isError = nameError,
                    supportingText = {
                        Text(
                            when {
                                nameError -> stringResource(R.string.tools_name_invalid)
                                !isNew -> stringResource(R.string.tools_name_locked)
                                else -> stringResource(R.string.tools_name_hint)
                            }
                        )
                    }
                )
                AppTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = stringResource(R.string.tools_description),
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4,
                    enabled = !saving,
                    supportingText = { Text(stringResource(R.string.tools_description_hint)) }
                )
                ParametersEditor(
                    text = parametersText,
                    onTextChange = { parametersText = it },
                    error = parametersError,
                    enabled = !saving
                )
                AppTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = stringResource(R.string.tools_command),
                    singleLine = false,
                    minLines = 3,
                    maxLines = 8,
                    enabled = !saving,
                    isError = commandError != null,
                    supportingText = {
                        when {
                            commandError != null -> Text(stringResource(commandError))
                            commandWarning != null -> Text(
                                text = stringResource(commandWarning),
                                color = MaterialTheme.semanticColors.warning
                            )
                            else -> Text(stringResource(R.string.tools_command_hint))
                        }
                    },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
                AppTextField(
                    value = timeoutText,
                    onValueChange = { timeoutText = it.filter { ch -> ch.isDigit() } },
                    label = stringResource(R.string.tools_timeout),
                    enabled = !saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = { Text(stringResource(R.string.tools_timeout_hint)) }
                )
            } else {
                Text(stringResource(R.string.tools_schema_original), style = MaterialTheme.typography.labelLarge)
                SchemaViewer(schemaJson = entry?.originalSchemaJson.orEmpty())
                AppTextField(
                    value = schemaText,
                    onValueChange = { schemaText = it },
                    label = stringResource(R.string.tools_schema_edit),
                    singleLine = false,
                    minLines = 6,
                    maxLines = 14,
                    enabled = !saving,
                    isError = !schemaValid,
                    supportingText = { Text(stringResource(if (schemaValid) R.string.tools_schema_metadata_hint else R.string.tools_schema_invalid)) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
                TextButton(onClick = { schemaText = entry?.originalSchemaJson.orEmpty() }, enabled = !saving) {
                    Text(stringResource(R.string.tools_schema_reset))
                }
                AppTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = stringResource(R.string.tools_description),
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4,
                    enabled = !saving,
                    supportingText = { Text(stringResource(R.string.tools_description_override_hint)) }
                )
            }

            InjectionPicker(mode = mode, onSelect = { mode = it })

            AppTextField(
                value = keywordsText,
                onValueChange = { keywordsText = it },
                label = stringResource(R.string.tools_keywords),
                enabled = !saving,
                supportingText = { Text(stringResource(R.string.tools_keywords_hint)) }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss, enabled = !saving) {
                    Text(stringResource(R.string.common_cancel))
                }
                TextButton(
                    enabled = canSave,
                    onClick = {
                        val keywords = parseKeywords(keywordsText)
                        if (isCustom) {
                            val timeout = timeoutText.toIntOrNull()
                                ?.coerceIn(CustomToolDefinition.MIN_TIMEOUT_SECONDS, CustomToolDefinition.MAX_TIMEOUT_SECONDS)
                                ?: CustomToolDefinition.DEFAULT_TIMEOUT_SECONDS
                            onSaveCustom(
                                name.trim(),
                                description,
                                parsedParameters.orEmpty(),
                                command,
                                timeout,
                                mode,
                                keywords
                            )
                        } else {
                            entry?.let { onSaveSettings(it.name, description.trim().takeIf { text -> text.isNotEmpty() }, mode, keywords,
                                schemaText.takeUnless { text -> text == it.originalSchemaJson }) }
                        }
                    }
                ) {
                    Text(
                        text = stringResource(R.string.common_save),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/** 参数 JSON 编辑区：实时校验 + 一键插入示例。 */
@Composable
private fun ParametersEditor(
    text: String,
    onTextChange: (String) -> Unit,
    error: Boolean,
    enabled: Boolean
) {
    Column {
        AppTextField(
            value = text,
            onValueChange = onTextChange,
            label = stringResource(R.string.tools_parameters),
            singleLine = false,
            minLines = 4,
            maxLines = 12,
            enabled = enabled,
            isError = error,
            supportingText = {
                Text(
                    if (error) {
                        stringResource(R.string.tools_parameters_invalid)
                    } else {
                        stringResource(R.string.tools_parameters_hint)
                    }
                )
            },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onTextChange(PARAMETERS_EXAMPLE) }, enabled = enabled) {
                Text(stringResource(R.string.tools_parameters_example))
            }
        }
    }
}

/** 内置 / MCP 工具的 schema：只读、等宽、可上下左右滚动。 */
@Composable
private fun SchemaViewer(schemaJson: String) {
    Column {
        Text(
            text = stringResource(R.string.tools_schema),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .clip(RoundedCornerShape(Radius.mdLarge))
                .background(MaterialTheme.semanticColors.mutedSurface)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(Spacing.sm)
        ) {
            Text(
                text = schemaJson.ifBlank { "{}" },
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = stringResource(R.string.tools_schema_hint),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = MaterialTheme.semanticColors.subtleText
        )
    }
}

/** 注入方式三态选择器（每轮 / 关键词 / 关闭）。 */
@Composable
private fun InjectionPicker(mode: ToolInjectionMode, onSelect: (ToolInjectionMode) -> Unit) {
    Column {
        Text(
            text = stringResource(R.string.tools_injection),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        SegmentedTabs(
            selected = INJECTION_ORDER.indexOf(mode).coerceAtLeast(0),
            labels = listOf(
                stringResource(R.string.tools_inject_every_round),
                stringResource(R.string.tools_inject_keyword),
                stringResource(R.string.tools_inject_off)
            ),
            onSelect = { index -> INJECTION_ORDER.getOrNull(index)?.let(onSelect) }
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = stringResource(R.string.tools_injection_hint),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = MaterialTheme.semanticColors.subtleText
        )
    }
}

private val INJECTION_ORDER = listOf(
    ToolInjectionMode.EVERY_ROUND,
    ToolInjectionMode.KEYWORD,
    ToolInjectionMode.OFF
)

/** 参数 JSON → 参数表；解析失败返回 null（界面据此报错，不静默当成空参数）。 */
private fun parseParameters(raw: String): List<CustomToolParameter>? {
    if (raw.isBlank()) return emptyList()
    return runCatching {
        parametersJson.decodeFromString(ListSerializer(CustomToolParameter.serializer()), raw)
    }.getOrNull()
}

/** 关键词输入框 → 列表：「逗号或空格分隔」，中英文逗号都认。 */
private fun parseKeywords(raw: String): List<String> =
    raw.split(',', '，', ' ', '\n', '\t')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

/** 命令模板的问题（返回文案资源），没问题返回 null。 */
private fun commandProblemOrNull(command: String, parameters: List<CustomToolParameter>?): Int? {
    if (command.isBlank()) return R.string.tools_command_empty
    // 一个占位符都没有是合法的（固定命令，如 `df -h`），不当错误拦：
    // 只是参数一定不会生效，由调用处的 tools_command_no_placeholder 警告提示。
    val used = CustomCommandTemplate.placeholders(command)
    val declared = parameters.orEmpty().map { it.name }.toSet()
    return if (used.any { it !in declared }) R.string.tools_command_unknown_placeholder else null
}
