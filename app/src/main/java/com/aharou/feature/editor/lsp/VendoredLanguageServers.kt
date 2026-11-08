package com.aharou.feature.editor.lsp

import android.content.Context
import com.aharou.core.net.AppProxy
import com.aharou.core.util.shellQuote
import com.aharou.feature.agent.domain.container.CommandEngine
import com.aharou.feature.agent.domain.container.ContainerProfileResolver
import com.aharou.feature.agent.domain.container.RemoteSshConnection
import com.aharou.feature.settings.data.repository.ExecutionMode
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.WorkspacePathMapper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal object LanguageExtensionArtifacts {
    const val PLATFORM = "linux-arm64-ubuntu24.04"
    const val MAX_ARTIFACT_BYTES = 512L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 1536L * 1024 * 1024
    private val hex = Regex("[0-9a-f]{64}")
    data class Source(val id: String, val command: String, val revision: String, val version: String, val sourceSha256: String)
    data class Catalog(val sourceSetSha256: String, val releaseTag: String, val sources: Map<String, Source>) {
        val releaseUrl: String get() = "https://github.com/dufuwuxin/Aharou/releases/download/$releaseTag"
        val manifestUrl: String get() = "$releaseUrl/manifest.json"
    }
    data class Artifact(val source: Source, val fileName: String, val sha256: String, val sizeBytes: Long)

    private fun text(node: JsonObject, name: String): String {
        val value = node[name] as? JsonPrimitive
        require(value != null && value.isString && value.content.isNotBlank()) { "EXTENSION_INVALID_$name" }
        return value.content
    }
    private fun header(node: JsonObject): String {
        val schema = node["schemaVersion"] as? JsonPrimitive
        require(schema != null && !schema.isString && schema.intOrNull == 1) { "EXTENSION_INVALID_SCHEMA" }
        require(text(node, "platform") == PLATFORM) { "EXTENSION_INVALID_PLATFORM" }
        return text(node, "sourceSetSha256").also { require(hex.matches(it)) { "EXTENSION_INVALID_SOURCE_SET" } }
    }
    fun catalog(raw: String): Catalog {
        val root = Json.parseToJsonElement(raw).jsonObject
        val fingerprint = header(root)
        val tag = text(root, "releaseTag")
        require(tag == "language-extensions-$fingerprint") { "EXTENSION_INVALID_RELEASE_TAG" }
        val entries = root.getValue("extensions").jsonArray.map { it.jsonObject }
        val expected = LanguageExtension.entries.associate { it.id to it.command }
        require(entries.size == expected.size && entries.map { text(it, "id") }.toSet() == expected.keys) { "EXTENSION_INVALID_IDS" }
        val sources = entries.associate { node ->
            val id = text(node, "id")
            val command = text(node, "command")
            require(command == expected[id]) { "EXTENSION_COMMAND_MISMATCH" }
            require(text(node, "repository").startsWith("https://github.com/")) { "EXTENSION_INVALID_REPOSITORY" }
            require(hex.matches(text(node, "adapterSha256"))) { "EXTENSION_INVALID_ADAPTER_HASH" }
            val sourceHash = text(node, "sourceSha256")
            require(hex.matches(sourceHash)) { "EXTENSION_INVALID_SOURCE_HASH" }
            val revision = text(node, "revision")
            require(Regex("[0-9a-f]{40}").matches(revision)) { "EXTENSION_INVALID_REVISION" }
            id to Source(id, command, revision, text(node, "version"), sourceHash)
        }
        return Catalog(fingerprint, tag, sources)
    }
    fun manifest(raw: String, catalog: Catalog): Map<String, Artifact> {
        val root = Json.parseToJsonElement(raw).jsonObject
        require(header(root) == catalog.sourceSetSha256) { "EXTENSION_SOURCE_SET_MISMATCH" }
        val entries = root.getValue("extensions").jsonArray.map { it.jsonObject }
        require(entries.size == catalog.sources.size && entries.map { text(it, "id") }.toSet() == catalog.sources.keys) { "EXTENSION_INVALID_IDS" }
        val artifacts = entries.associate { node ->
            val id = text(node, "id")
            val source = catalog.sources.getValue(id)
            require(text(node, "command") == source.command && text(node, "revision") == source.revision &&
                text(node, "sourceSha256") == source.sourceSha256 && text(node, "version") == source.version) { "EXTENSION_SOURCE_MISMATCH" }
            val name = text(node, "fileName")
            require(name == "$id-linux-arm64.tar.gz") { "EXTENSION_INVALID_FILENAME" }
            val hash = text(node, "sha256")
            require(hex.matches(hash)) { "EXTENSION_INVALID_HASH" }
            val size = node["sizeBytes"] as? JsonPrimitive
            val bytes = size?.takeUnless { it.isString }?.longOrNull
            require(bytes != null && bytes in 1..MAX_ARTIFACT_BYTES) { "EXTENSION_INVALID_SIZE" }
            id to Artifact(source, name, hash, bytes)
        }
        require(artifacts.values.sumOf { it.sizeBytes } <= MAX_TOTAL_BYTES) { "EXTENSION_TOTAL_TOO_LARGE" }
        return artifacts
    }
    fun digest(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            digest.update(buffer, 0, size)
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun verified(file: File, artifact: Artifact): Boolean =
        file.isFile && file.length() == artifact.sizeBytes && digest(file) == artifact.sha256

    fun installed(raw: String, catalog: Catalog, extension: LanguageExtension): String? {
        val node = Json.parseToJsonElement(raw).jsonObject
        val source = catalog.sources.getValue(extension.id)
        return if (header(node) == catalog.sourceSetSha256 && text(node, "id") == source.id &&
            text(node, "command") == source.command && text(node, "revision") == source.revision &&
            text(node, "sourceSha256") == source.sourceSha256 && text(node, "version") == source.version &&
            hex.matches(text(node, "sha256")) && text(node, "origin") == "ci-artifact") source.version else null
    }
}

/** Network operations keep their cancellation hook until the response body is fully consumed. */
internal suspend fun <T> extensionRequest(client: OkHttpClient, request: Request, consume: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWith(Result.failure(e))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (!continuation.isActive) throw kotlinx.coroutines.CancellationException()
                        consume(it)
                    }
                }
                continuation.resumeWith(result)
            }
        })
    }

internal suspend fun downloadExtensionArtifact(
    client: OkHttpClient,
    url: String,
    part: File,
    artifact: LanguageExtensionArtifacts.Artifact,
    progress: (Float) -> Unit,
): File = withContext(Dispatchers.IO) {
    val operation = currentCoroutineContext()
    if (LanguageExtensionArtifacts.verified(part, artifact)) return@withContext part
    if (part.length() >= artifact.sizeBytes) part.delete()
    repeat(2) {
        operation.ensureActive()
        val offset = part.length()
        val request = Request.Builder().url(url).header("Accept-Encoding", "identity").apply {
            if (offset > 0) header("Range", "bytes=$offset-")
        }.build()
        val completed = extensionRequest(client, request) { response ->
            if (response.code == 404) error("EXTENSION_ARTIFACT_NOT_PUBLISHED")
            if (response.code == 416) {
                if (LanguageExtensionArtifacts.verified(part, artifact)) true
                else { part.delete(); false }
            } else {
                check(response.code == 200 || response.code == 206) { "EXTENSION_HTTP_${response.code}" }
                val append = response.code == 206
                if (append) check(response.header("Content-Range") == "bytes $offset-${artifact.sizeBytes - 1}/${artifact.sizeBytes}") { "EXTENSION_INVALID_RANGE" }
                val body = checkNotNull(response.body) { "EXTENSION_EMPTY_BODY" }
                val expected = artifact.sizeBytes - if (append) offset else 0L
                check(body.contentLength() == -1L || body.contentLength() == expected) { "EXTENSION_SIZE_MISMATCH" }
                var count = if (append) offset else 0L
                part.parentFile?.mkdirs()
                java.io.FileOutputStream(part, append).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            operation.ensureActive()
                            val size = input.read(buffer)
                            if (size < 0) break
                            check(count + size <= artifact.sizeBytes) { "EXTENSION_TOO_LARGE" }
                            output.write(buffer, 0, size)
                            count += size
                            progress(count.toFloat() / artifact.sizeBytes)
                        }
                    }
                }
                check(count == artifact.sizeBytes) { "EXTENSION_SIZE_MISMATCH" }
                true
            }
        }
        operation.ensureActive()
        if (completed) {
            if (!LanguageExtensionArtifacts.verified(part, artifact)) {
                part.delete()
                error("EXTENSION_HASH_MISMATCH")
            }
            return@withContext part
        }
    }
    error("EXTENSION_RANGE_FAILED")
}

@Singleton
class VendoredLanguageServers @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val files: FileAccessProvider,
    private val commands: CommandEngine,
    private val profiles: ContainerProfileResolver,
    private val connection: RemoteSshConnection,
) {
    private val installLock = Mutex()
    private val mutableProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = mutableProgress.asStateFlow()
    private val mutableRecovery = MutableStateFlow<Set<String>>(emptySet())
    val recoveryRequired: StateFlow<Set<String>> = mutableRecovery.asStateFlow()
    private val client by lazy {
        OkHttpClient.Builder().proxyAuthenticator(AppProxy.okHttpAuthenticator)
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.MINUTES)
            .addNetworkInterceptor { chain ->
                val url = chain.request().url
                if (url.scheme != "https" || url.host !in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")) throw IOException("EXTENSION_UNTRUSTED_REDIRECT")
                chain.proceed(chain.request())
            }.build()
    }
    companion object {
        const val ROOT = "${WorkspacePathMapper.AHAROU_ROOT}/language-extensions"
        private const val ASSETS = "language-extensions"
        fun program(extension: LanguageExtension): String = "$ROOT/bin/${extension.command}"
    }
    private fun catalog() = context.assets.open("$ASSETS/catalog.json").bufferedReader().use { LanguageExtensionArtifacts.catalog(it.readText()) }
    fun rootForCurrentBackend(): String? =
        if (profiles.forWorkspace(null).mode == ExecutionMode.REMOTE_SSH)
            connection.remoteHome?.trimEnd('/')?.let { "$it/.aharou/language-extensions" }
        else ROOT
    fun activeProgram(extension: LanguageExtension): String? = rootForCurrentBackend()?.let { "$it/bin/${extension.command}" }

    fun installedVersion(extension: LanguageExtension): String? = try {
        val root = rootForCurrentBackend()
        if (root == null) null else LanguageExtensionArtifacts.installed(
            files.readFile("$root/${extension.id}/current/artifact.json"), catalog(), extension,
        )
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
      catch (_: Exception) { null }

    suspend fun install(extension: LanguageExtension) = withContext(Dispatchers.IO) {
        installLock.withLock {
            val profile = profiles.forWorkspace(null)
            val target = if (profile.mode == ExecutionMode.REMOTE_SSH) connection.bootstrapTarget() else null
            val root = rootForCurrentBackend() ?: return@withLock null
            fun checkBackend() {
                check(profiles.forWorkspace(null).id == profile.id &&
                    (profile.mode != ExecutionMode.REMOTE_SSH || target != null && connection.isCurrentBootstrap(target))) { "EXTENSION_BACKEND_CHANGED" }
            }
            checkBackend()
            mutableRecovery.value = mutableRecovery.value - extension.id
            if (installedVersion(extension) != null) {
                val ready = commands.runCommandSyncIfReady("test -x ${shellQuote("$root/${extension.id}/current/launch.sh")}", timeoutMs = 15_000L)
                if (ready?.exitCode == 0) return@withLock ready
            }
            // Readiness and the platform are checked before any network/download side effect.
            val preflight = commands.runCommandSyncIfReady("python3 - <<'AHAROU_PLATFORM'\nimport platform\nfrom pathlib import Path\nassert platform.machine() in ('aarch64', 'arm64'), 'EXTENSION_REQUIRES_ARM64'\ns = Path('/etc/os-release').read_text()\nassert 'ID=ubuntu' in s and 'VERSION_ID=\"24.04\"' in s, 'EXTENSION_REQUIRES_UBUNTU_24_04'\nAHAROU_PLATFORM", timeoutMs = 15_000L) ?: return@withLock null
            check(preflight.exitCode == 0) { "EXTENSION_PLATFORM_NOT_READY: ${preflight.output.takeLast(500)}" }
            val catalog = catalog()
            val raw = extensionRequest(client, Request.Builder().url(catalog.manifestUrl).build()) { response ->
                check(response.code != 404) { "EXTENSION_RELEASE_NOT_PUBLISHED" }
                check(response.isSuccessful) { "EXTENSION_MANIFEST_HTTP_${response.code}" }
                val body = checkNotNull(response.body)
                body.byteStream().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(output.size() + count <= 1024 * 1024) { "EXTENSION_MANIFEST_TOO_LARGE" }
                        output.write(buffer, 0, count)
                    }
                    output.toString("UTF-8")
                }
            }
            val artifact = LanguageExtensionArtifacts.manifest(raw, catalog).getValue(extension.id)
            val cache = File(context.cacheDir, "language-extension-artifacts/${catalog.sourceSetSha256}")
            val part = File(cache, "${artifact.fileName}.${artifact.sha256}.part")
            try {
                downloadExtensionArtifact(client, "${catalog.releaseUrl}/${artifact.fileName}", part, artifact) {
                    mutableProgress.value = mutableProgress.value + (extension.id to it)
                }
                currentCoroutineContext().ensureActive()
                checkBackend()
                val token = UUID.randomUUID().toString()
                val base = "$root/${extension.id}"
                val upload = "$base/incoming-$token.tar.gz"
                val stage = "$base/releases/${catalog.sourceSetSha256}-$token"
                part.inputStream().use { input ->
                    val operation = currentCoroutineContext()
                    val checked = object : java.io.FilterInputStream(input) {
                        override fun read(): Int { operation.ensureActive(); return super.read() }
                        override fun read(b: ByteArray, off: Int, len: Int): Int { operation.ensureActive(); return super.read(b, off, len) }
                    }
                    files.writeStream(upload, checked)
                }
                val metadata = buildJsonObject {
                    put("schemaVersion", 1); put("platform", LanguageExtensionArtifacts.PLATFORM)
                    put("sourceSetSha256", catalog.sourceSetSha256); put("origin", "ci-artifact")
                    put("id", extension.id); put("command", extension.command); put("version", artifact.source.version)
                    put("revision", artifact.source.revision); put("sourceSha256", artifact.source.sourceSha256)
                    put("sha256", artifact.sha256); put("sizeBytes", artifact.sizeBytes)
                }.toString()
                checkBackend()
                val prepared = commands.runCommandSyncIfReady(
                    "python3 - ${shellQuote(upload)} ${shellQuote(stage)} ${shellQuote(metadata)} <<'AHAROU_EXTRACT'\n$EXTRACT_SCRIPT\nAHAROU_EXTRACT",
                    timeoutMs = 300_000L,
                ) ?: return@withLock null
                if (prepared.exitCode != 0) return@withLock prepared
                currentCoroutineContext().ensureActive()
                checkBackend()
                try {
                    val committed = commands.runCommandSyncIfReady(
                        "python3 - ${shellQuote(root)} ${shellQuote(extension.id)} ${shellQuote(extension.command)} ${shellQuote(stage)} <<'AHAROU_COMMIT'\n$COMMIT_SCRIPT\nAHAROU_COMMIT",
                        timeoutMs = 15_000L,
                    )
                    currentCoroutineContext().ensureActive()
                    checkBackend()
                    if (committed == null || committed.exitCode != 0) {
                        runCatching {
                            checkBackend()
                            val restored = commands.runCommandSyncIfReady("python3 - ${shellQuote(stage)} <<'AHAROU_ROLLBACK'\n$ROLLBACK_SCRIPT\nAHAROU_ROLLBACK", timeoutMs = 15_000L)
                            check(restored?.exitCode == 0) { "EXTENSION_ROLLBACK_FAILED" }
                        }.onFailure { mutableRecovery.value = mutableRecovery.value + extension.id }
                    }
                    committed
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        runCatching {
                            checkBackend()
                            val restored = commands.runCommandSyncIfReady("python3 - ${shellQuote(stage)} <<'AHAROU_ROLLBACK'\n$ROLLBACK_SCRIPT\nAHAROU_ROLLBACK", timeoutMs = 15_000L)
                            check(restored?.exitCode == 0) { "EXTENSION_ROLLBACK_FAILED" }
                        }.onFailure { mutableRecovery.value = mutableRecovery.value + extension.id }
                    }
                    throw cancelled
                } catch (failure: Exception) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        runCatching {
                            checkBackend()
                            val restored = commands.runCommandSyncIfReady("python3 - ${shellQuote(stage)} <<'AHAROU_ROLLBACK'\n$ROLLBACK_SCRIPT\nAHAROU_ROLLBACK", timeoutMs = 15_000L)
                            check(restored?.exitCode == 0) { "EXTENSION_ROLLBACK_FAILED" }
                        }.onFailure { mutableRecovery.value = mutableRecovery.value + extension.id }
                    }
                    throw failure
                }
            } finally {
                mutableProgress.value = mutableProgress.value - extension.id
            }
        }
    }
}

private val EXTRACT_SCRIPT = """
import hashlib, json, os, pathlib, shutil, subprocess, sys, tarfile
archive, destination, raw = sys.argv[1:]
metadata = json.loads(raw)
stage = pathlib.Path(destination)
assert not stage.exists(), 'EXTENSION_STAGE_EXISTS'
with open(archive, 'rb') as stream:
    digest = hashlib.file_digest(stream, 'sha256').hexdigest()
assert digest == metadata['sha256'] and os.path.getsize(archive) == metadata['sizeBytes'], 'EXTENSION_TRANSFER_MISMATCH'
stage.mkdir(parents=True)
try:
    with tarfile.open(archive, 'r:gz') as bundle:
        members = []
        expanded = 0
        for member in bundle:
            expanded += member.size
            assert len(members) < 200000 and expanded <= 2147483648, 'EXTENSION_EXPANSION_LIMIT'
            members.append(member)
        names = set()
        for member in members:
            path = pathlib.PurePosixPath(member.name)
            assert not path.is_absolute() and '..' not in path.parts and '\\' not in member.name, 'EXTENSION_UNSAFE_PATH'
            assert member.name not in names, 'EXTENSION_DUPLICATE_PATH'
            names.add(member.name)
            assert member.isfile() or member.isdir() or member.issym() or member.islnk(), 'EXTENSION_SPECIAL_FILE'
            if member.issym() or member.islnk():
                link = pathlib.PurePosixPath(member.linkname)
                assert not link.is_absolute() and '\\' not in member.linkname, 'EXTENSION_UNSAFE_LINK'
                origin = (stage / member.name).parent if member.issym() else stage
                target = (origin / member.linkname).resolve()
                assert target.is_relative_to(stage.resolve()), 'EXTENSION_LINK_ESCAPE'
        # No archive-created links exist while regular files are written.
        for member in members:
            path = stage / member.name
            if member.isdir():
                path.mkdir(parents=True, exist_ok=True)
            elif member.isfile():
                path.parent.mkdir(parents=True, exist_ok=True)
                with bundle.extractfile(member) as source, open(path, 'xb') as output:
                    shutil.copyfileobj(source, output)
                path.chmod(member.mode & 0o755)
        for member in members:
            path = stage / member.name
            if member.issym() or member.islnk():
                path.parent.mkdir(parents=True, exist_ok=True)
                if member.issym():
                    os.symlink(member.linkname, path)
                else:
                    target = stage / member.linkname
                    assert target.is_file() and not target.is_symlink(), 'EXTENSION_INVALID_HARDLINK'
                    os.link(target, path)
    # Recheck final resolution to reject chained symlink escapes.
    for path in stage.rglob('*'):
        assert path.resolve().is_relative_to(stage.resolve()), 'EXTENSION_LINK_ESCAPE'
    for name in ('launch.sh', 'source.json', 'LICENSE'):
        assert (stage / name).is_file() and not (stage / name).is_symlink(), 'EXTENSION_MISSING_ENTRY'
    assert os.access(stage / 'launch.sh', os.X_OK), 'EXTENSION_ENTRY_NOT_EXECUTABLE'
    source = json.loads((stage / 'source.json').read_text())
    assert all(source[key] == metadata[key] for key in ('id', 'revision', 'sourceSha256', 'version')), 'EXTENSION_SOURCE_METADATA_MISMATCH'
    if metadata['id'] in ('typescript', 'shell', 'yaml', 'vue'):
        version = subprocess.check_output(['node', '--version'], text=True).strip()
        assert int(version.lstrip('v').split('.')[0]) >= 24, 'EXTENSION_REQUIRES_NODE_24'
    assert sys.version_info[:2] == (3, 12), 'EXTENSION_REQUIRES_PYTHON_3_12'
    for path in stage.rglob('*'):
        if path.is_file() and not path.is_symlink():
            with path.open('rb') as binary:
                magic = binary.read(4)
            if magic == b'\x7fELF':
                result = subprocess.run(['ldd', str(path)], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
                assert 'not found' not in result.stdout, 'EXTENSION_MISSING_SHARED_LIBRARY: ' + result.stdout
                assert result.returncode == 0 or 'not a dynamic executable' in result.stdout or 'statically linked' in result.stdout, 'EXTENSION_LDD_FAILED'
    subprocess.run(['sh', '-n', str(stage / 'launch.sh')], check=True)
    (stage / 'artifact.json').write_text(raw)
except BaseException:
    shutil.rmtree(stage)
    raise
finally:
    os.unlink(archive)
""".trimIndent()

private val COMMIT_SCRIPT = """
import fcntl, json, os, pathlib, shlex, sys, uuid
root, identifier, command, release = sys.argv[1:]
root = pathlib.Path(root)
base = root / identifier
release = pathlib.Path(release)
assert (release / 'artifact.json').is_file() and (release / 'launch.sh').is_file(), 'EXTENSION_NOT_PREPARED'
lock = open(base / '.install.lock', 'a')
fcntl.flock(lock, fcntl.LOCK_EX)
current = base / 'current'
assert not current.exists() or current.is_symlink(), 'EXTENSION_CURRENT_NOT_SYMLINK'
previous = os.readlink(current) if current.is_symlink() else None
(release / '.previous.json').write_text(json.dumps(previous))
(root / 'bin').mkdir(parents=True, exist_ok=True)
token = uuid.uuid4().hex
wrapper = root / 'bin' / ('.' + command + '-' + token)
wrapper.write_text('#!/bin/sh\nexec ' + shlex.quote(str(current / 'launch.sh')) + ' "' + chr(36) + '@"\n')
wrapper.chmod(0o755)
os.replace(wrapper, root / 'bin' / command)
pending = base / ('.current-' + token)
os.symlink(os.path.relpath(release, base), pending)
os.replace(pending, current)
""".trimIndent()

private val ROLLBACK_SCRIPT = """
import fcntl, json, os, pathlib, sys, uuid
release = pathlib.Path(sys.argv[1])
base = release.parent.parent
with open(base / '.install.lock', 'a') as lock:
    fcntl.flock(lock, fcntl.LOCK_EX)
    current = base / 'current'
    if current.is_symlink() and current.resolve() == release.resolve():
        previous = json.loads((release / '.previous.json').read_text())
        if previous is None:
            current.unlink()
        else:
            pending = base / ('.rollback-' + uuid.uuid4().hex)
            pending.symlink_to(previous)
            os.replace(pending, current)
""".trimIndent()
