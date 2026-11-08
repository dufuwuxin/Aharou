package com.aharou.feature.editor.lsp

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LanguageExtensionArtifactTest {
    private val hash = "a".repeat(64)
    private val revision = "b".repeat(40)
    private fun header() = mapOf(
        "schemaVersion" to JsonPrimitive(1),
        "platform" to JsonPrimitive(LanguageExtensionArtifacts.PLATFORM),
        "sourceSetSha256" to JsonPrimitive(hash),
    )
    private fun source(extension: LanguageExtension) = buildJsonObject {
        put("id", extension.id); put("command", extension.command)
        put("repository", "https://github.com/example/source"); put("revision", revision)
        put("version", "1.2.3"); put("sourceSha256", hash); put("adapterSha256", hash)
    }
    private fun catalogRaw(): String = JsonObject(header() + mapOf(
        "releaseTag" to JsonPrimitive("language-extensions-$hash"),
        "extensions" to JsonArray(LanguageExtension.entries.map(::source)),
    )).toString()
    private fun manifestRaw(): String = JsonObject(header() + mapOf(
        "extensions" to JsonArray(LanguageExtension.entries.map { extension ->
            JsonObject(source(extension) + mapOf(
                "fileName" to JsonPrimitive("${extension.id}-linux-arm64.tar.gz"),
                "sha256" to JsonPrimitive(hash), "sizeBytes" to JsonPrimitive(10L),
            ))
        }),
    )).toString()
    private fun reject(block: () -> Unit) {
        try { block(); fail("invalid artifact contract accepted") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }
    private fun mutate(raw: String, key: String, value: JsonPrimitive): String {
        val root = Json.parseToJsonElement(raw).jsonObject
        val entries = root.getValue("extensions").jsonArray.toMutableList()
        entries[0] = JsonObject(entries[0].jsonObject + (key to value))
        return JsonObject(root + ("extensions" to JsonArray(entries))).toString()
    }

    @Test fun exactCatalogAndManifestProduceOnlyFixedOwnRepositoryUrls() {
        val catalog = LanguageExtensionArtifacts.catalog(catalogRaw())
        assertEquals("https://github.com/dufuwuxin/Aharou/releases/download/language-extensions-$hash/manifest.json", catalog.manifestUrl)
        val artifacts = LanguageExtensionArtifacts.manifest(manifestRaw(), catalog)
        assertEquals(7, artifacts.size)
        assertEquals("lua-linux-arm64.tar.gz", artifacts.getValue("lua").fileName)
    }

    @Test fun schemaPlatformIdentityAndSourceMismatchFailClosed() {
        val catalog = LanguageExtensionArtifacts.catalog(catalogRaw())
        for ((key, value) in listOf(
            "id" to JsonPrimitive("../../escape"), "command" to JsonPrimitive("sh"),
            "revision" to JsonPrimitive("c".repeat(40)), "version" to JsonPrimitive("new"),
            "sourceSha256" to JsonPrimitive("c".repeat(64)),
            "fileName" to JsonPrimitive("../lua-linux-arm64.tar.gz"),
            "sha256" to JsonPrimitive("not-a-hash"), "sizeBytes" to JsonPrimitive(0L),
            "sizeBytes" to JsonPrimitive(LanguageExtensionArtifacts.MAX_ARTIFACT_BYTES + 1),
            "sizeBytes" to JsonPrimitive("10"),
        )) reject { LanguageExtensionArtifacts.manifest(mutate(manifestRaw(), key, value), catalog) }
        reject { LanguageExtensionArtifacts.manifest(manifestRaw().replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""), catalog) }
        reject { LanguageExtensionArtifacts.manifest(manifestRaw().replace(LanguageExtensionArtifacts.PLATFORM, "linux-x64"), catalog) }
        val root = Json.parseToJsonElement(manifestRaw()).jsonObject
        val entries = root.getValue("extensions").jsonArray
        reject { LanguageExtensionArtifacts.manifest(JsonObject(root + ("extensions" to JsonArray(entries.drop(1)))).toString(), catalog) }
        reject { LanguageExtensionArtifacts.manifest(JsonObject(root + ("extensions" to JsonArray(entries.dropLast(1) + entries.first()))).toString(), catalog) }
        reject { LanguageExtensionArtifacts.catalog(catalogRaw().replace("language-extensions-$hash", "other-tag")) }
        reject { LanguageExtensionArtifacts.catalog(catalogRaw().replace("\"sourceSetSha256\":\"$hash\"", "\"sourceSetSha256\":\"bad\"")) }
        reject { LanguageExtensionArtifacts.manifest(manifestRaw().replace("\"sizeBytes\":10", "\"sizeBytes\":536870912"), catalog) }
    }

    @Test fun installedMetadataRequiresCurrentSourceSetAndArtifactOrigin() {
        val catalog = LanguageExtensionArtifacts.catalog(catalogRaw())
        val metadata = JsonObject(header() + source(LanguageExtension.LUA) + mapOf(
            "origin" to JsonPrimitive("ci-artifact"), "sha256" to JsonPrimitive(hash),
        )).toString()
        assertEquals("1.2.3", LanguageExtensionArtifacts.installed(metadata, catalog, LanguageExtension.LUA))
        assertNull(LanguageExtensionArtifacts.installed(metadata.replace("ci-artifact", "vendored-source"), catalog, LanguageExtension.LUA))
        assertNull(LanguageExtensionArtifacts.installed(metadata.replace("\"sourceSetSha256\":\"$hash\"", "\"sourceSetSha256\":\"${"c".repeat(64)}\""), catalog, LanguageExtension.LUA))
        reject { LanguageExtensionArtifacts.installed("{\"version\":\"1.2.3\"}", catalog, LanguageExtension.LUA) }
    }

    private fun artifact(file: File): LanguageExtensionArtifacts.Artifact = LanguageExtensionArtifacts.Artifact(
        LanguageExtensionArtifacts.catalog(catalogRaw()).sources.getValue("lua"),
        "lua-linux-arm64.tar.gz", LanguageExtensionArtifacts.digest(file), file.length(),
    )
    private fun client(code: Int, bytes: ByteArray, range: String? = null, inspect: (Request) -> Unit = {}): OkHttpClient {
        val client = mockk<OkHttpClient>()
        every { client.newCall(any()) } answers {
            val request = firstArg<Request>()
            inspect(request)
            val call = mockk<Call>(relaxed = true)
            every { call.enqueue(any()) } answers {
                val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(code).message("fixture").body(bytes.toResponseBody()).apply {
                        if (range != null) header("Content-Range", range)
                    }.build()
                firstArg<Callback>().onResponse(call, response)
            }
            call
        }
        return client
    }

    @Test fun hashesSizesAndResume206And200ResetAreVerified() = runBlocking {
        val directory = Files.createTempDirectory("extension-test").toFile()
        try {
            val reference = File(directory, "expected").apply { writeText("abcdef") }
            val artifact = artifact(reference)
            val part = File(directory, "artifact.part").apply { writeText("abc") }
            assertFalse(LanguageExtensionArtifacts.verified(part, artifact))
            downloadExtensionArtifact(client(206, "def".toByteArray(), "bytes 3-5/6") {
                assertEquals("bytes=3-", it.header("Range"))
            }, "https://github.com/fixture", part, artifact) { }
            assertEquals("abcdef", part.readText())
            part.writeText("xxx")
            downloadExtensionArtifact(client(200, "abcdef".toByteArray()), "https://github.com/fixture", part, artifact) { }
            assertTrue(LanguageExtensionArtifacts.verified(part, artifact))
            part.writeText("xxxxxx")
            assertFalse(LanguageExtensionArtifacts.verified(part, artifact))
            try {
                downloadExtensionArtifact(client(200, "badbad".toByteArray()), "https://github.com/fixture", part, artifact) { }
                fail("hash mismatch accepted")
            } catch (_: IllegalStateException) { }
            assertFalse(part.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun range416ResetsAndRetriesAndWrongRangeOrOversizeFail() = runBlocking {
        val directory = Files.createTempDirectory("extension-range").toFile()
        try {
            val reference = File(directory, "expected").apply { writeText("abcdef") }
            val artifact = artifact(reference)
            val part = File(directory, "artifact.part").apply { writeText("abc") }
            var requests = 0
            val client = mockk<OkHttpClient>()
            every { client.newCall(any()) } answers {
                val request = firstArg<Request>()
                requests++
                client(if (requests == 1) 416 else 200, if (requests == 1) byteArrayOf() else "abcdef".toByteArray()).newCall(request)
            }
            downloadExtensionArtifact(client, "https://github.com/fixture", part, artifact) { }
            assertEquals(2, requests)
            assertTrue(LanguageExtensionArtifacts.verified(part, artifact))
            for ((code, bytes, range) in listOf(Triple(206, "def", "bytes 1-3/6"), Triple(200, "too-long", null))) {
                part.writeText("abc")
                try {
                    downloadExtensionArtifact(client(code, bytes.toByteArray(), range), "https://github.com/fixture", part, artifact) { }
                    fail("invalid response accepted")
                } catch (_: IllegalStateException) { }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun cancellationCancelsCallAndRetainsPartialFile() = runBlocking {
        val directory = Files.createTempDirectory("extension-cancel").toFile()
        try {
            val reference = File(directory, "expected").apply { writeText("abcdef") }
            val artifact = artifact(reference)
            val part = File(directory, "artifact.part").apply { writeText("abc") }
            val call = mockk<Call>(relaxed = true)
            val client = mockk<OkHttpClient>()
            val started = CompletableDeferred<Unit>()
            every { client.newCall(any()) } returns call
            every { call.enqueue(any()) } answers { started.complete(Unit); Unit }
            val download = launch { downloadExtensionArtifact(client, "https://github.com/fixture", part, artifact) { } }
            started.await()
            download.cancelAndJoin()
            verify(exactly = 1) { call.cancel() }
            assertEquals("abc", part.readText())
        } finally { directory.deleteRecursively() }
    }
}
