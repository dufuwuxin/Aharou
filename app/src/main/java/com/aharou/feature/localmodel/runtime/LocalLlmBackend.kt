package com.aharou.feature.localmodel.runtime

import io.shubham0204.smollm.SmolLM
import kotlinx.coroutines.flow.Flow

internal interface LocalLlmBackend {
    suspend fun load(modelPath: String, params: LocalGenParams)
    fun addSystemPrompt(text: String)
    fun addAssistantMessage(text: String)
    fun addUserMessage(text: String)
    fun getResponseAsFlow(text: String): Flow<String>
    fun stop()
    fun close()
}

internal class SmolLocalLlmBackend : LocalLlmBackend {
    private val native = SmolLM()
    override suspend fun load(modelPath: String, params: LocalGenParams) = native.load(modelPath,
        SmolLM.InferenceParams(temperature = params.temperature, topP = params.topP, topK = 40,
            repeatPenalty = 1.1f, storeChats = true, contextSize = null, chatTemplate = null,
            numThreads = params.numThreads, useMmap = true, useMlock = false))
    override fun addSystemPrompt(text: String) = native.addSystemPrompt(text)
    override fun addAssistantMessage(text: String) = native.addAssistantMessage(text)
    override fun addUserMessage(text: String) = native.addUserMessage(text)
    override fun getResponseAsFlow(text: String) = native.getResponseAsFlow(text)
    override fun stop() = native.stop()
    override fun close() = native.close()
}
