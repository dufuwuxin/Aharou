package com.aharou.feature.editor.lsp

import com.aharou.core.util.shellQuote
import com.aharou.feature.agent.domain.container.RemoteSshConnection
import com.aharou.feature.agent.domain.mcp.McpStdioChannel
import io.github.rosemoe.sora.lsp.client.connection.StreamConnectionProvider
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** SSH channel owns its remote process; never pass its pid to the local process-tree reaper. */
class RemoteLspConnectionProvider(
    private val connection: RemoteSshConnection,
    private val target: RemoteSshConnection.BootstrapTarget,
    private val serverKey: String,
    private val projectRoot: String,
    private val program: String,
    private val args: List<String>,
    private val stillCurrent: () -> Boolean = { true }
) : StreamConnectionProvider {
    @Volatile private var process: Process? = null
    @Volatile private var closed = false
    private val slotHeld = AtomicBoolean(false)

    @Synchronized override fun start() {
        if (closed || process != null) return
        try {
            check(stillCurrent() && connection.isCurrentBootstrap(target)) { "SSH_CONNECTION_CHANGED" }
            McpStdioChannel.acquireSlot(serverKey)
            slotHeld.set(true)
            val resolvedProgram = if (program.startsWith("${VendoredLanguageServers.ROOT}/"))
                target.home.trimEnd('/') + "/.aharou/language-extensions/" + program.removePrefix("${VendoredLanguageServers.ROOT}/")
            else program
            val child = connection.startProcess(command(projectRoot, resolvedProgram, args), target)
            process = child
            check(stillCurrent() && connection.isCurrentBootstrap(target)) { "SSH_CONNECTION_CHANGED" }
            Thread({ runCatching { child.errorStream.use { it.copyTo(object : OutputStream() {
                override fun write(b: Int) = Unit
                override fun write(b: ByteArray, off: Int, len: Int) = Unit
            }) } } }, "lsp-ssh-stderr").apply { isDaemon = true; start() }
            Thread({ try { child.waitFor() } finally { close() } }, "lsp-ssh-reap").apply { isDaemon = true; start() }
        } catch (failure: Exception) {
            close()
            throw IOException("Remote language server failed to start", failure)
        }
    }
    override val inputStream: InputStream get() = process?.inputStream ?: throw IOException("LSP not started")
    override val outputStream: OutputStream get() = process?.outputStream ?: throw IOException("LSP not started")
    override val isClosed: Boolean get() {
        if (!closed && (!stillCurrent() || !connection.isCurrentBootstrap(target))) close()
        return closed
    }
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        val child = process
        process = null
        runCatching { child?.outputStream?.close() }
        runCatching { child?.destroy() }
        if (slotHeld.compareAndSet(true, false)) McpStdioChannel.releaseSlot()
    }
    companion object {
        internal fun command(root: String, program: String, args: List<String>): String =
            "cd ${shellQuote(root)} && exec " + (listOf(program) + args).joinToString(" ") { shellQuote(it) }
        internal fun remoteRoot(workspace: String, base: String): String {
            require(base.startsWith('/') && workspace.isNotBlank()) { "Missing remote workspace path" }
            val root = base.trimEnd('/')
            return if (workspace == root || workspace.startsWith("$root/")) workspace.trimEnd('/')
                else "$root/${workspace.trimEnd('/').substringAfterLast('/')}"
        }
        internal fun filePath(path: String, root: String, localWorkspace: String, home: String): String {
            val normalized = path.replace('\\', '/')
            for (prefix in listOf("~/workspace", "$home/workspace", localWorkspace)) {
                if (normalized == prefix) return root
                if (normalized.startsWith("$prefix/")) return root + normalized.removePrefix(prefix)
            }
            require(normalized == root || normalized.startsWith("$root/")) { "File is outside the remote workspace" }
            return normalized
        }
    }
}
