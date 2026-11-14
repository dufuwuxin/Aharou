package com.aharou.feature.editor.lsp

import com.aharou.feature.agent.domain.container.CommandEngine
import com.aharou.feature.agent.domain.container.CommandResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Only the explicit install path may provision this fixed runtime package list. */
internal object ExtensionRuntimeBootstrap {
    suspend fun prepare(commands: CommandEngine, checkBackend: () -> Unit): CommandResult? {
        currentCoroutineContext().ensureActive()
        checkBackend()
        val result = commands.runCommandSyncIfReady(SCRIPT, timeoutMs = 600_000L)
        currentCoroutineContext().ensureActive()
        checkBackend()
        return result
    }

    internal val SCRIPT = """
        set -eu
        case "$(uname -m)" in aarch64|arm64) ;; *) echo EXTENSION_REQUIRES_ARM64 >&2; exit 64 ;; esac
        os=$(cat /etc/os-release)
        printf '%s\n' "${'$'}os" | grep -Eq '^ID=(ubuntu|"ubuntu")${'$'}' || { echo EXTENSION_REQUIRES_UBUNTU_24_04 >&2; exit 65; }
        printf '%s\n' "${'$'}os" | grep -Eq '^VERSION_ID=(24\.04|"24\.04")${'$'}' || { echo EXTENSION_REQUIRES_UBUNTU_24_04 >&2; exit 65; }
        runtime_ready() {
            python3.12 -c 'import ctypes, ssl, sys; assert sys.version_info[:2] == (3, 12); ctypes.CDLL("libstdc++.so.6"); ctypes.CDLL("libatomic.so.1")' >/dev/null 2>&1 || return 1
            test "$(dpkg-query -W -f='${'$'}{Status}' ca-certificates 2>/dev/null)" = 'install ok installed'
        }
        if runtime_ready; then exit 0; fi
        test "$(id -u)" = 0 || { echo 'EXTENSION_RUNTIME_REQUIRES_ROOT: install python3.12 libstdc++6 libatomic1 ca-certificates as administrator' >&2; exit 77; }
        export DEBIAN_FRONTEND=noninteractive
        apt-get -o Acquire::Retries=2 -o DPkg::Lock::Timeout=60 update
        apt-get -o Acquire::Retries=2 -o DPkg::Lock::Timeout=60 install -y --no-install-recommends python3.12 libstdc++6 libatomic1 ca-certificates
        runtime_ready || { echo EXTENSION_RUNTIME_NOT_READY >&2; exit 78; }
    """.trimIndent()
}

internal fun pinnedExtensionCommands(
    delegate: CommandEngine,
    run: suspend (String, Long) -> CommandResult?
): CommandEngine = object : CommandEngine by delegate {
    override suspend fun runCommandSyncIfReady(command: String, projectPath: String?, timeoutMs: Long): CommandResult? =
        run(command, timeoutMs)
}
