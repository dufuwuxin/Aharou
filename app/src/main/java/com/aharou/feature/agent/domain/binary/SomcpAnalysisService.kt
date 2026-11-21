// SPDX-License-Identifier: AGPL-3.0-only
package com.aharou.feature.agent.domain.binary

import com.aharou.feature.workspace.domain.FileAccessProvider
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Shared by the selected-file preview and the built-in tool. Serializes parsing to bound peak memory. */
@Singleton
class SomcpAnalysisService @Inject constructor(private val files: FileAccessProvider) {
    private val mutex = Mutex()
    private val analyzer = SomcpAnalyzer()

    suspend fun analyzePath(workspace: String, path: String, action: String = "overview", offset: Int = 0,
        limit: Int = 50, query: String = ""): JsonObject = mutex.withLock {
        runInterruptible(Dispatchers.IO) {
            require(path.isNotBlank()) { "MISSING_PATH" }
            val access = files.forWorkspace(workspace).checked()
            require(access.isFile(path)) { "ELF_FILE_REQUIRED" }
            require(access.fileSize(path) <= SomcpAnalyzer.MAX_BYTES) { "ELF_SIZE_LIMIT" }
            val bytes = access.readBytesBounded(path, SomcpAnalyzer.MAX_BYTES)
            JsonObject(analyzer.analyze(bytes, action, offset, limit, query) + ("path" to JsonPrimitive(path)))
        }
    }

    suspend fun inspectPath(workspace: String, path: String, action: String = "overview", offset: Int = 0,
        limit: Int = 50, query: String = ""): JsonObject = mutex.withLock {
        runInterruptible(Dispatchers.IO) {
            require(path.isNotBlank()) { "MISSING_PATH" }
            val access = files.forWorkspace(workspace).checked()
            require(access.isFile(path)) { "FILE_REQUIRED" }
            require(access.fileSize(path) <= SomcpAnalyzer.MAX_BYTES) { "FILE_ANALYSIS_SIZE_LIMIT" }
            GeneralFileInspector.inspect(access.readBytesBounded(path, SomcpAnalyzer.MAX_BYTES), path, action, offset, limit, query)
        }
    }

    suspend fun inspectSelectedFile(file: File, action: String = "overview", offset: Int = 0,
        limit: Int = 50, query: String = ""): JsonObject = mutex.withLock {
        runInterruptible(Dispatchers.IO) {
            require(file.isFile) { "FILE_REQUIRED" }
            require(file.length() <= SomcpAnalyzer.MAX_BYTES) { "FILE_ANALYSIS_SIZE_LIMIT" }
            GeneralFileInspector.inspect(file.inputStream().use { BoundedBinaryReader.read(it) }, file.name, action, offset, limit, query)
        }
    }

    /** Only called for the exact File explicitly selected in the native file browser. */
    suspend fun analyzeSelectedFile(file: File, action: String = "overview", offset: Int = 0,
        limit: Int = 50, query: String = ""): JsonObject = mutex.withLock {
        runInterruptible(Dispatchers.IO) {
            require(file.isFile) { "ELF_FILE_REQUIRED" }
            require(file.length() <= SomcpAnalyzer.MAX_BYTES) { "ELF_SIZE_LIMIT" }
            val bytes = file.inputStream().use { BoundedBinaryReader.read(it) }
            JsonObject(analyzer.analyze(bytes, action, offset, limit, query) + ("path" to JsonPrimitive(file.name)))
        }
    }
}
