package com.aharou.feature.plugin.domain.market

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.aharou.core.net.AppProxy
import com.aharou.feature.agent.domain.skill.SkillImportReport
import com.aharou.feature.agent.domain.skill.SkillRepository
import com.aharou.feature.agent.domain.skill.SkillScope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl

internal data class OperitDownload(val url: String, val name: String, val sha256: String?, val subPath: String? = null)

internal class OperitMarketRepository internal constructor(
    private val context: Context,
    private val skills: SkillRepository,
    private val client: OkHttpClient,
) {
    @Inject constructor(@ApplicationContext context: Context, skills: SkillRepository) : this(context, skills,
        OkHttpClient.Builder().proxySelector(AppProxy.selector)
            .proxyAuthenticator(AppProxy.okHttpAuthenticator).connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build())

    private val requestMutex = Mutex()
    private var lastRequestAt = 0L

    suspend fun page(type: String, page: Int): OperitPage = withContext(Dispatchers.IO) {
        val text = text("${OperitCatalog.BASE}/${OperitCatalog.path(type, page)}")
        OperitCatalog.parse(text, type, page)
    }

    suspend fun resolve(entry: OperitEntry, asset: OperitAsset?): OperitDownload = withContext(Dispatchers.IO) {
        if (asset != null) {
            require(asset in entry.latestAssets)
            publicUrl(asset.url)
            OperitDownload(asset.url, safeName(asset.assetName), asset.sha256)
        } else {
            // An old asset is never substituted for the latest release.
            require(entry.latestAssets.isEmpty())
            val source = entry.source ?: error("missing_source")
            require(source.kind == "github_repo")
            val repo = OperitGitHub.parse(source.url)
            val ref = repo.ref ?: Json.parseToJsonElement(text("https://api.github.com/repos/${repo.owner}/${repo.repo}"))
                .jsonObject["default_branch"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: error("missing_default_branch")
            val url = "https://codeload.github.com/${repo.owner}/${repo.repo}/zip/".toHttpUrl()
                .newBuilder().addPathSegment(ref).build().toString()
            OperitDownload(url, safeName("${repo.repo}-source.zip"), null, repo.subPath)
        }
    }

    fun canDownload(entry: OperitEntry): Boolean = entry.latestAssets.isNotEmpty() || runCatching {
        require(entry.source?.kind == "github_repo")
        OperitGitHub.parse(entry.source.url)
    }.isSuccess

    fun canImport(entry: OperitEntry, asset: OperitAsset?): Boolean = entry.type == "skill" &&
        if (asset != null) asset in entry.latestAssets &&
            (asset.assetName.endsWith(".zip", true) || asset.assetName.endsWith(".md", true))
        else entry.latestAssets.isEmpty() && canDownload(entry)

    suspend fun save(entry: OperitEntry, asset: OperitAsset?, uri: Uri) = withContext(Dispatchers.IO) {
        var staged: File? = null
        var completed = false
        try {
            val download = resolve(entry, asset)
            val ready = stage(download)
            staged = ready
            currentCoroutineContext().ensureActive()
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                ready.inputStream().use { copyBounded(it, output, MAX_DOWNLOAD) }
            } ?: error("document_unavailable")
            currentCoroutineContext().ensureActive()
            completed = true
        } finally {
            staged?.delete()
            if (!completed) {
                // Providers may not support deletion; truncate as an additional best effort.
                runCatching { context.contentResolver.openOutputStream(uri, "wt")?.close() }
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            }
        }
    }

    suspend fun importSkill(entry: OperitEntry, asset: OperitAsset?): SkillImportReport = withContext(Dispatchers.IO) {
        require(canImport(entry, asset))
        val download = resolve(entry, asset)
        val staged = stage(download)
        var validated: File? = null
        try {
            val fallback = safeName(entry.title.ifBlank { entry.id }).take(64)
            if (download.name.endsWith(".md", true)) {
                require(staged.length() <= MAX_TEXT)
                currentCoroutineContext().ensureActive()
                skills.importMarkdown(staged.readText(), fallback, SkillScope.GLOBAL)
            } else {
                val archive = File.createTempFile("operit-skills-", ".zip", context.cacheDir)
                validated = archive
                staged.inputStream().use { input ->
                    archive.outputStream().use { output -> scopeArchive(input, output, download.subPath) }
                }
                currentCoroutineContext().ensureActive()
                archive.inputStream().use { skills.importZip(it, fallback, SkillScope.GLOBAL) }
            }
        } finally {
            validated?.delete()
            staged.delete()
        }
    }

    private suspend fun stage(download: OperitDownload): File {
        val file = File.createTempFile("operit-download-", ".part", context.cacheDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            request(download.url) { input ->
                file.outputStream().use { output -> copyBounded(input, output, MAX_DOWNLOAD, digest) }
            }
            verifyHash(digest.digest(), download.sha256)
            return file
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    }

    private suspend fun text(url: String): String = request(url) { input ->
        val output = java.io.ByteArrayOutputStream()
        copyBounded(input, output, MAX_TEXT)
        output.toString("UTF-8")
    }

    private suspend fun <T> request(url: String, consume: suspend (InputStream) -> T): T = coroutineScope {
        var target = url
        repeat(6) {
            currentCoroutineContext().ensureActive()
            publicUrl(target)
            val request = Request.Builder().url(target).header("User-Agent", "Aharou-OperitMarket/1.0")
                .header("Referer", "https://operit.app/").build()
            requestMutex.withLock {
                val now = System.nanoTime() / 1_000_000
                val wait = 700L - (now - lastRequestAt)
                if (wait > 0) delay(wait)
                lastRequestAt = System.nanoTime() / 1_000_000
            }
            val call = client.newCall(request)
            val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                // Keep cancellation wired through the complete response-body consumption.
                call.execute().use { response ->
                    if (response.code in listOf(301, 302, 303, 307, 308)) {
                        target = response.header("Location")?.let { response.request.url.resolve(it)?.toString() }
                            ?: error("invalid_redirect")
                    } else {
                        check(response.isSuccessful) { "market_http_failure" }
                        val body = response.body ?: error("empty_response")
                        return@coroutineScope body.byteStream().use { consume(it) }
                    }
                }
            } finally { cancellation.cancel() }
        }
        error("redirect_limit")
    }

    companion object {
        private const val MAX_DOWNLOAD = 64L * 1024 * 1024
        private const val MAX_TEXT = 8L * 1024 * 1024
        fun safeName(raw: String): String = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.').take(120).ifBlank { "operit-download.zip" }

        fun publicUrl(raw: String) {
            val url = raw.toHttpUrl()
            require(url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty() && url.port == 443)
            require(url.host.isNotBlank() && url.host != "localhost" && !url.host.endsWith(".localhost") && !url.host.endsWith(".local"))
            if (':' in url.host || url.host.all { it.isDigit() || it == '.' }) {
                val address = java.net.InetAddress.getByName(url.host)
                val uniqueLocal = address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc
                require(!uniqueLocal && !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress &&
                    !address.isSiteLocalAddress && !address.isMulticastAddress)
            }
        }

        fun verifyHash(actual: ByteArray, expected: String?) {
            if (expected.isNullOrBlank()) return
            require(Regex("[0-9a-fA-F]{64}").matches(expected))
            check(actual.joinToString("") { "%02x".format(it) }.equals(expected, true)) { "checksum_mismatch" }
        }

        fun archivePath(raw: String): String {
            require(raw.isNotBlank() && !raw.startsWith('/') && '\\' !in raw && ':' !in raw && '\u0000' !in raw)
            val parts = raw.trimEnd('/').split('/')
            require(parts.all { it.isNotBlank() && it != ".." && it != "." })
            return parts.joinToString("/")
        }

        suspend fun copyBounded(input: InputStream, output: OutputStream, limit: Long, digest: MessageDigest? = null): Long {
            var total = 0L
            val buffer = ByteArray(16 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= limit) { "size_limit" }
                digest?.update(buffer, 0, count)
                output.write(buffer, 0, count)
            }
            return total
        }

        // Validate every entry before the legacy importer, which otherwise skips malicious paths.
        // Repository archives must stay inside their single GitHub wrapper and requested subtree.
        suspend fun scopeArchive(input: InputStream, output: OutputStream, subPath: String?) {
            val subtree = subPath?.takeIf { it.isNotEmpty() }?.let(::archivePath).orEmpty()
            var root: String? = null
            var total = 0L
            var entries = 0
            var written = 0
            val seen = mutableSetOf<String>()
            ZipInputStream(input).use { zip -> ZipOutputStream(output).use { result ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    require(++entries <= 2000)
                    val path = archivePath(entry.name)
                    if (subPath != null) {
                        val top = path.substringBefore('/')
                        if (root == null) root = top
                        require(root == top)
                    }
                    if (!entry.isDirectory) {
                        val relative = if (subPath != null) path.substringAfter('/', "") else path
                        val selected = subtree.isEmpty() || relative.startsWith("$subtree/")
                        if (selected && relative.isNotEmpty()) {
                            val name = if (subtree.isEmpty()) relative else relative.removePrefix("$subtree/")
                            require(seen.add(name))
                            result.putNextEntry(ZipEntry(archivePath(name)))
                            total += copyBounded(zip, result, MAX_DOWNLOAD - total)
                            result.closeEntry()
                            written++
                        } else {
                            total += copyBounded(zip, object : OutputStream() { override fun write(b: Int) = Unit
                                override fun write(b: ByteArray, off: Int, len: Int) = Unit }, MAX_DOWNLOAD - total)
                        }
                    } else {
                        total += copyBounded(zip, object : OutputStream() { override fun write(b: Int) = Unit
                            override fun write(b: ByteArray, off: Int, len: Int) = Unit }, MAX_DOWNLOAD - total)
                    }
                    zip.closeEntry()
                }
                require(written > 0) { "empty_subtree" }
            } }
        }
    }
}
