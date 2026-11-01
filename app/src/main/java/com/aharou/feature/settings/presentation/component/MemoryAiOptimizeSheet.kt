package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.memory.AharouMemoryStore
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppTextField
import com.aharou.feature.agent.domain.workflow.AgentWorkflow
import com.aharou.feature.settings.data.repository.MemoryModelSettingsRepository
import com.aharou.feature.settings.domain.model.ModelScene
import com.aharou.feature.settings.domain.service.ModelSceneResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 角色记忆 AI 优化弹窗：一次性对话，不保持角色记忆，只用来优化当前这段文本。
 * 使用「记忆模型」设置的专用模型（provider + model），不复用压缩/默认模型。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemoryAiOptimizeSheet(
    roleKey: String,
    currentContent: String,
    store: AharouMemoryStore,
    memoryModelRepo: MemoryModelSettingsRepository,
    modelSceneResolver: ModelSceneResolver,
    agentWorkflow: AgentWorkflow,
    onDone: () -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("") }
    var optimizing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

    val systemPrompt = "你是一个记忆整理助手。用户会给你一段角色记忆，请按照以下规则优化：\n" +
        "1. 保留所有关键事实、凭据与硬约束；\n" +
        "2. 删除重复、过时、噪声；\n" +
        "3. 结构化：每条结论单独成段，带编号或项目符号；\n" +
        "4. 不要输出任何与用户请求无关的内容。\n" +
        "用户可能还会输入一条补充说明，描述想保留/强调的部分；请在此基础上优化。"

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = stringResource(R.string.memory_ai_optimize_title, roleKey),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.memory_ai_optimize_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AppTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = stringResource(R.string.memory_ai_optimize_prompt_label),
                singleLine = false,
                minLines = 2,
                maxLines = 4
            )
            if (result != null) {
                Text(
                    text = result!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = Spacing.xs)
                )
            }
            Button(
                onClick = {
                    if (optimizing) return@Button
                    optimizing = true
                    result = null
                    scope.launch {
                        result = withContext(Dispatchers.IO) {
                            val binding = modelSceneResolver.resolve(ModelScene.MEMORY)
                            if (binding !is com.aharou.feature.settings.domain.model.SceneResolution.Bound) {
                                "未配置记忆模型（当前：${binding}）。请在设置 → 默认模型 → 记忆整理模型 中绑定。"
                            } else {
                                agentWorkflow.completeOnce(systemPrompt, currentContent + "\n\n用户指令：${prompt.trim().ifEmpty { "请优化以上内容" }}")
                                    ?: "调用失败：未返回结果"
                            }
                        }
                        optimizing = false
                    }
                },
                enabled = !optimizing,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (optimizing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.memory_ai_optimize_run))
                }
            }
            if (result != null) {
                Button(
                    onClick = {
                        store.writeRoleMemory(roleKey, result!!)
                        onDone()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.memory_ai_optimize_accept))
                }
                TextButton(onClick = { result = null }) {
                    Text(stringResource(R.string.common_retry))
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    }
}
