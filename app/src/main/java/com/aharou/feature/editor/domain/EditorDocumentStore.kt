package com.aharou.feature.editor.domain

import com.aharou.feature.workspace.domain.transactionKey

import com.aharou.feature.agent.domain.tool.FileVersionRegistry
import com.aharou.feature.agent.domain.tool.WorkspaceFileLock
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.sha256Fingerprint
import java.util.concurrent.ConcurrentHashMap

/** Human document baselines are independent from AI session observations. */
class EditorDocumentStore(
    private val fileLock: WorkspaceFileLock,
    private val versions: FileVersionRegistry
) {
    private data class Document(val access: FileAccessProvider, val fingerprint: String)
    private val documents = ConcurrentHashMap<String, Document>()

    sealed interface WriteResult {
        data object Saved : WriteResult
        data class Conflict(val diskContent: String) : WriteResult
    }

    fun observe(path: String, bytes: ByteArray, access: FileAccessProvider, scope: String = "") {
        documents[documentKey(scope, path)] = Document(access, sha256Fingerprint(bytes))
    }

    fun forget(path: String, scope: String = "") { documents.remove(documentKey(scope, path)) }

    fun accessFor(path: String, scope: String = ""): FileAccessProvider? = documents[documentKey(scope, path)]?.access

    private fun documentKey(scope: String, path: String) = "$scope\u0000$path"

    suspend fun save(path: String, content: String, fallback: FileAccessProvider, force: Boolean = false, scope: String = ""): WriteResult {
        val access = documents[documentKey(scope, path)]?.access ?: fallback
        val key = access.transactionKey(path)
        return fileLock.withLock(key) {
            val baseline = documents[documentKey(scope, path)]?.fingerprint
            val current = if (force) null else access.fingerprint(path)
            if (!force && baseline != null && current != baseline) {
                WriteResult.Conflict(if (current == null) "" else access.readFile(path))
            } else {
                access.writeFile(path, content)
                val bytes = content.toByteArray(Charsets.UTF_8)
                observe(path, bytes, access, scope)
                versions.recordExternal(key, sha256Fingerprint(bytes))
                WriteResult.Saved
            }
        }
    }
}
