package com.aharou.core.translation

import android.content.Context
import android.system.Os
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class TranslationModelFiles @Inject constructor(@param:ApplicationContext private val context: Context) {
    data class Artifact(val path: String, val size: Long, val sha256: String)
    private data class Package(val model: String, val revision: String, val baseUrl: String, val artifacts: List<Artifact>)
    private val packages = mapOf("zh" to readPackage("model-manifest.json", "opus-mt-en-zh"),
        "en" to readPackage("model-manifest-zh-en.json", "opus-mt-zh-en"))
    private val root = File(context.filesDir, "translation_models")
    val model: String get() = packages.getValue("zh").model
    val artifacts: List<Artifact> get() = packages.getValue("zh").artifacts

    private fun readPackage(name: String, expectedModel: String): Package {
        val manifest = JSONObject(context.assets.open("translation/$name").bufferedReader().use { it.readText() })
        val model = manifest.getString("model")
        val revision = manifest.getString("revision")
        val baseUrl = manifest.getString("baseUrl")
        val artifacts = manifest.getJSONArray("files").let { files ->
            List(files.length()) { index ->
                val item = files.getJSONObject(index)
                Artifact(item.getString("path"), item.getLong("size"), item.getString("sha256"))
            }
        }
        require(revision.matches(Regex("[a-f0-9]{40}")))
        require(model == expectedModel)
        require(baseUrl == "https://huggingface.co/Xenova/$expectedModel/resolve/$revision/")
        require(artifacts.isNotEmpty() && artifacts.map { it.path }.distinct().size == artifacts.size)
        artifacts.forEach {
            require(it.path.matches(Regex("(?:onnx/)?[a-zA-Z0-9_.-]+")) && !it.path.contains(".."))
            require(it.size > 0 && it.sha256.matches(Regex("[a-f0-9]{64}")))
        }
        return Package(model, revision, baseUrl, artifacts)
    }

    private fun installed(target: String): File {
        val pack = packages.getValue(target)
        return File(root, "${pack.model}/${pack.revision}")
    }
    private val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(20, TimeUnit.MINUTES)
        .followSslRedirects(false).build()

    suspend fun isInstalled(target: String = "zh"): Boolean = withContext(Dispatchers.IO) {
        val installed = installed(target)
        val artifacts = packages.getValue(target).artifacts
        installed.isDirectory && artifacts.all { valid(File(installed, it.path), it) }
    }

    suspend fun install(target: String = "zh") = withContext(Dispatchers.IO) {
        val pack = packages.getValue(target)
        val installed = installed(target)
        val artifacts = pack.artifacts
        val baseUrl = pack.baseUrl
        if (isInstalled(target)) return@withContext
        check(root.mkdirs() || root.isDirectory)
        val staging = File(root, "staging-${UUID.randomUUID()}")
        check(staging.mkdir())
        try {
            for (artifact in artifacts) {
                currentCoroutineContext().ensureActive()
                val output = File(staging, artifact.path)
                check(output.parentFile?.mkdirs() == true || output.parentFile?.isDirectory == true)
                val call = client.newCall(Request.Builder().url(baseUrl + artifact.path).build())
                try {
                    call.awaitResponse().use { response ->
                        check(response.isSuccessful) { "Model download HTTP ${response.code}" }
                        val body = response.body ?: error("Missing model body")
                        check(body.contentLength() == -1L || body.contentLength() == artifact.size)
                        body.byteStream().use { input ->
                            output.outputStream().use { file ->
                                val buffer = ByteArray(64 * 1024)
                                var count = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    count += read
                                    check(count <= artifact.size) { "Oversized model artifact" }
                                    file.write(buffer, 0, read)
                                }
                                file.fd.sync()
                            }
                        }
                    }
                } finally {
                    call.cancel()
                }
                check(valid(output, artifact)) { "Model checksum mismatch" }
            }
            currentCoroutineContext().ensureActive()
            // All files are closed, synced and verified before publishing on the same filesystem.
            if (installed.exists()) check(installed.deleteRecursively())
            check(installed.parentFile?.mkdirs() == true || installed.parentFile?.isDirectory == true)
            Os.rename(staging.absolutePath, installed.absolutePath)
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    fun openArtifact(path: String, target: String = "zh"): java.io.InputStream? {
        if (packages.getValue(target).artifacts.none { it.path == path }) return null
        val file = File(installed(target), path)
        return if (file.isFile) file.inputStream() else null
    }

    private suspend fun valid(file: File, artifact: Artifact): Boolean {
        if (!file.isFile || file.length() != artifact.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == artifact.sha256
    }
}

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}
