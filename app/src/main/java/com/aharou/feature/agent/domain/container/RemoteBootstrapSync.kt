package com.aharou.feature.agent.domain.container

import android.content.Context
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class RemoteBootstrapSync @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val connection: RemoteSshConnection
) {
    private val mutex = Mutex()
    private var target: RemoteSshConnection.BootstrapTarget? = null
    private val completed = mutableSetOf<String>()

    suspend fun syncIfNeeded() = mutex.withLock {
        val current = connection.bootstrapTarget() ?: return@withLock false
        if (target !== current) { target = current; completed.clear() }
        var pulled = false
        suspend fun stage(name: String, action: suspend () -> Unit) {
            if (name in completed || !connection.isCurrentBootstrap(current)) return
            try {
                action()
                if (connection.isCurrentBootstrap(current)) completed += name
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { FileLogger.w(TAG, "远程资源同步失败：$name", failure) }
        }
        stage("docs") {
            val docs = collectBuiltinAssets(context.assets, "docs")
            check(docs.isNotEmpty()) { "MISSING_BUILTIN_DOCS" }
            connection.uploadDocs(docs, current)
        }
        for (directory in listOf("agents", "teams", "skills")) stage(directory) {
            connection.uploadBuiltinFiles(directory, collectBuiltinAssets(context.assets, directory), target = current)
        }
        stage("shared-push") {
            val files = collectSharedFiles()
            if (files.isNotEmpty()) connection.uploadBuiltinFiles("shared", files, rootDir = "", target = current)
        }
        // A pull retry must never replay an already completed push of stale local files.
        stage("shared-pull") { sharedDir()?.let {
            check(connection.pullSharedFiles(it, current)) { "SHARED_PULL_FAILED" }
            pulled = true
        } }
        pulled
    }

    suspend fun pullShared() {
        try { sharedDir()?.let { connection.pullSharedFiles(it) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { FileLogger.w(TAG, "回拉共享区失败", failure) }
    }

    private fun collectSharedFiles(): Map<String, ByteArray> {
        val root = sharedDir() ?: return emptyMap()
        val files = linkedMapOf<String, ByteArray>()
        root.walkTopDown().onEnter { it.name != ".git" }.filter { it.isFile && it.length() in 1..SHARED_MAX_BYTES }.forEach {
            files[it.relativeTo(root).invariantSeparatorsPath] = it.readBytes()
        }
        return files
    }

    private fun sharedDir(): File? = File(context.filesDir, "aharou-global/shared").takeIf { it.isDirectory }

    private companion object {
        const val TAG = "RemoteBootstrapSync"
        const val SHARED_MAX_BYTES = 1L * 1024 * 1024
    }
}
