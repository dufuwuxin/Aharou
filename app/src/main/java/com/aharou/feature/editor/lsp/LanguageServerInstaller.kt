package com.aharou.feature.editor.lsp

import com.aharou.core.util.shellQuote
import com.aharou.feature.agent.domain.container.CommandEngine
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.WorkspacePathMapper
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Historical spec categories retained for existing callers; installation always uses CI artifacts. */
enum class InstallKind { APK, NPM, PIP, COMPOSER, RUSTUP, RELEASE }

data class ServerSpec(
    val id: String,
    val command: String,
    val displayName: String,
    val installKind: InstallKind,
    val packageName: String? = null,
    val releaseRepo: String? = null,
    val releaseVersion: String? = null,
    val releaseAssetFor: ((arch: String) -> String)? = null,
    val releaseEntryRelPath: String? = null
)

enum class LanguageExtension(val spec: ServerSpec) {
    LUA(ServerSpec("lua", "lua-language-server", "Lua Language Server", InstallKind.APK,
        packageName = "lua-language-server", releaseRepo = "LuaLS/lua-language-server", releaseVersion = "3.19.1",
        releaseAssetFor = { arch -> "lua-language-server-3.19.1-$arch.tar.gz" }, releaseEntryRelPath = "bin/lua-language-server")),
    PYTHON(ServerSpec("python", "pylsp", "Python Language Server", InstallKind.PIP, packageName = "python-lsp-server")),
    TYPESCRIPT(ServerSpec("typescript", "typescript-language-server", "TypeScript Language Server", InstallKind.NPM,
        packageName = "typescript-language-server,typescript")),
    SHELL(ServerSpec("shell", "bash-language-server", "Bash Language Server", InstallKind.NPM, packageName = "bash-language-server")),
    YAML(ServerSpec("yaml", "yaml-language-server", "YAML Language Server", InstallKind.NPM, packageName = "yaml-language-server")),
    VUE(ServerSpec("vue", "vue-language-server", "Vue Language Server", InstallKind.NPM, packageName = "@vue/language-server,typescript")),
    RUST(ServerSpec("rust", "rust-analyzer", "Rust Analyzer", InstallKind.RUSTUP));

    val id: String get() = spec.id
    val command: String get() = spec.command
    val displayName: String get() = spec.displayName
}

sealed interface ExtensionStatus {
    object NotInstalled : ExtensionStatus
    data class Installed(val version: String) : ExtensionStatus
    data class Unavailable(val reason: String) : ExtensionStatus
    data class Unknown(val detail: String) : ExtensionStatus
}

data class InstallResult(
    val success: Boolean,
    val exitCode: Int?,
    val output: String,
    val status: ExtensionStatus
)

/** App-side downloads and backend-private, atomic artifact installation; no package manager fallback. */
@Singleton
class LanguageServerInstaller @Inject constructor(
    private val commandEngine: CommandEngine,
    @Suppress("unused") private val fileAccess: FileAccessProvider,
    private val vendored: VendoredLanguageServers,
) {
    companion object {
        const val STATUS_FILE = "${WorkspacePathMapper.AHAROU_ROOT}/lsp-extensions.json"
        const val MAX_OUTPUT_CHARS = 1000
        const val UNKNOWN_VERSION = "unknown"
        private const val PROBE_TIMEOUT_MS = 15_000L
    }

    val downloadProgress = vendored.downloadProgress
    val recoveryRequired = vendored.recoveryRequired

    suspend fun probe(extension: LanguageExtension): ExtensionStatus = withContext(Dispatchers.IO) {
        val cmd = vendored.activeProgram(extension) ?: return@withContext unavailable()
        val result = commandEngine.runCommandSyncIfReady(
            "test -x ${shellQuote(cmd)} && test -x ${shellQuote("${requireNotNull(vendored.rootForCurrentBackend())}/${extension.id}/current/launch.sh")} && printf ready",
            projectPath = null, timeoutMs = PROBE_TIMEOUT_MS,
        ) ?: return@withContext unavailable()
        if (result.exitCode != 0) return@withContext ExtensionStatus.NotInstalled
        val version = vendored.installedVersion(extension) ?: return@withContext ExtensionStatus.NotInstalled
        ExtensionStatus.Installed(version)
    }

    suspend fun install(extension: LanguageExtension): InstallResult = withContext(Dispatchers.IO) {
        val result = try {
            vendored.install(extension)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val detail = tail(failure.message.orEmpty())
            return@withContext InstallResult(false, null, detail, ExtensionStatus.Unavailable(detail))
        } ?: return@withContext InstallResult(false, null, "", unavailable())
        if (result.exitCode != 0) {
            return@withContext InstallResult(false, result.exitCode, tail(result.output), ExtensionStatus.Unknown("EXTENSION_INSTALL_FAILED"))
        }
        val status = probe(extension)
        InstallResult(status is ExtensionStatus.Installed, result.exitCode, tail(result.output), status)
    }

    /** Read the backend's actual version fingerprint, never a stale global status cache. */
    suspend fun cachedStatus(extension: LanguageExtension): ExtensionStatus? = withContext(Dispatchers.IO) {
        vendored.installedVersion(extension)?.let { ExtensionStatus.Installed(it) }
    }

    private fun unavailable(): ExtensionStatus.Unavailable = ExtensionStatus.Unavailable(
        commandEngine.notReadyHint()?.takeIf { it.isNotBlank() } ?: "容器未就绪",
    )

    private fun tail(output: String): String =
        if (output.length <= MAX_OUTPUT_CHARS) output else "..." + output.takeLast(MAX_OUTPUT_CHARS)
}
