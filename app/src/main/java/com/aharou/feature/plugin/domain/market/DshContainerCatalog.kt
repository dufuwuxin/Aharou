package com.aharou.feature.plugin.domain.market

import com.aharou.feature.agent.domain.container.CommandResult
import com.aharou.feature.agent.domain.container.LinuxContainerEngine
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.UUID

/** Public catalog only; never bootstraps a runtime or downloads package payloads. */
internal class DshContainerCatalog(
    private val ready: () -> Boolean,
    private val execute: suspend (String) -> CommandResult,
) {
    constructor(engine: LinuxContainerEngine) : this(
        ready = { engine.isContainerInstalled() && engine.notReadyHint() == null },
        execute = { command -> engine.runCommandSyncUnbounded(command, projectPath = null, timeoutMs = 30_000L) },
    )

    suspend fun page(page: Int): String {
        check(ready()) { "dsh_container_not_ready" }
        return decode(execute(command(page)))
    }

    companion object {
        const val MAX_BYTES = (com.aharou.feature.agent.domain.container.MAX_UNBOUNDED_CHARS - 1024) / 78 * 57
        private const val MAX_ENCODED = ((MAX_BYTES + 2) / 3) * 4

        internal fun command(page: Int): String {
            require(page in 1..10000)
            val file = "/tmp/aharou-dsh-${UUID.randomUUID()}.html"
            // curl's config and inherited proxy settings must not change this fixed public request.
            // Fold base64 output: the catalog HTML can exceed ProcessLineReader's single-line limit.
            return "command -v curl >/dev/null 2>&1 || exit 127; command -v base64 >/dev/null 2>&1 || exit 126; " +
                "umask 077; set -C; : > '$file' || exit 1; " +
                "trap 'rm -f -- \"$file\"' EXIT; " +
                "status=\$(curl -q --fail --silent --show-error --proto '=https' " +
                "--proxy '' --noproxy '*' --connect-timeout 10 --max-time 25 --max-filesize $MAX_BYTES " +
                "--user-agent 'Aharou-OperitMarket/1.0' --referer '${DshMarketRepository.HOME}' " +
                "--output '$file' --write-out '%{http_code}' '${DshMarketRepository.HOME}?page=$page') && " +
                "[ \"\$status\" = 200 ] && base64 --wrap=76 '$file'"
        }

        internal fun decode(result: CommandResult): String {
            check(result.exitCode != null) { "dsh_container_no_exit" }
            check(result.exitCode == 0) {
                when (result.exitCode) {
                    127 -> "dsh_container_curl_missing"
                    126 -> "dsh_container_base64_missing"
                    6 -> "dsh_container_dns"
                    7 -> "dsh_container_connection"
                    28 -> "dsh_container_timeout"
                    60 -> "dsh_container_certificate"
                    77 -> "dsh_container_ca_missing"
                    else -> "dsh_container_exit:${result.exitCode}"
                }
            }
            check(!result.outputTruncated) { "dsh_container_truncated" }
            check(result.output.length <= MAX_ENCODED + MAX_ENCODED / 76 + 2) { "dsh_container_too_large" }
            val encoded = result.output.filterNot { it == '\n' || it == '\r' }
            check(encoded.length <= MAX_ENCODED) { "dsh_container_too_large" }
            check(encoded.isNotEmpty() && encoded.length % 4 == 0) { "dsh_container_invalid_base64" }
            val bytes = try { Base64.getDecoder().decode(encoded) }
            catch (failure: IllegalArgumentException) { throw IllegalStateException("dsh_container_invalid_base64", failure) }
            check(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "dsh_container_too_large" }
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        }
    }
}
