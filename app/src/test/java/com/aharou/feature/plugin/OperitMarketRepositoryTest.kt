package com.aharou.feature.plugin

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.aharou.feature.agent.domain.skill.SkillImportReport
import com.aharou.feature.agent.domain.skill.SkillRepository
import com.aharou.feature.agent.domain.skill.SkillScope
import com.aharou.feature.plugin.domain.market.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OperitMarketRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private val context = mockk<Context>()
    private val skills = mockk<SkillRepository>()
    private val client = mockk<OkHttpClient>()
    private val resolver = mockk<ContentResolver>()
    private val uri = mockk<Uri>()

    private fun repository(body: ByteArray): OperitMarketRepository {
        every { context.cacheDir } returns temp.root
        every { context.contentResolver } returns resolver
        every { client.newCall(any()) } answers {
            val request = firstArg<Request>()
            mockk<Call>().also { call ->
                every { call.cancel() } returns Unit
                every { call.execute() } returns Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(200).message("fixture").body(body.toResponseBody()).build()
            }
        }
        return OperitMarketRepository(context, skills, client)
    }
    private fun item(name: String, body: ByteArray) = OperitEntry("fixture", "skill", "Fixture",
        latestVersion = OperitVersion("v2", "2"), assets = listOf(OperitAsset("asset", "v2",
            url = "https://plugins.example.org/$name", sha256 = MessageDigest.getInstance("SHA-256")
                .digest(body).joinToString("") { "%02x".format(it) }, assetName = name)))

    @Test fun saveWritesVerifiedBytesToSelectedDocumentAndCleansTemporaryFiles() = runBlocking {
        val bytes = "fixture download".toByteArray()
        val repo = repository(bytes)
        val entry = item("fixture.md", bytes)
        val output = ByteArrayOutputStream()
        every { resolver.openOutputStream(uri, "wt") } returns output
        repo.save(entry, entry.latestAssets.single(), uri)
        assertArrayEquals(bytes, output.toByteArray())
        assertTrue(temp.root.listFiles().orEmpty().isEmpty())
        verify(exactly = 0) { skills.importMarkdown(any(), any(), any(), any()) }
    }

    @Test fun checksumFailureNeverWritesDownloadedBytesAndRemovesStaging() = runBlocking {
        val bytes = "fixture download".toByteArray()
        val repo = repository(bytes)
        val entry = item("fixture.md", bytes)
        val invalid = entry.assets.single().copy(sha256 = "0".repeat(64))
        val output = ByteArrayOutputStream()
        every { resolver.openOutputStream(uri, "wt") } returns output
        try {
            repo.save(entry.copy(assets = listOf(invalid)), invalid, uri)
            fail("Must reject mismatched hash")
        } catch (_: IllegalStateException) { }
        assertEquals(0, output.size())
        assertTrue(temp.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun markdownImportUsesExistingGlobalConsumerWithoutOverwriting() = runBlocking {
        val bytes = "---\nname: fixture\ndescription: fixture\n---\nfixture".toByteArray()
        val repo = repository(bytes)
        val entry = item("fixture.md", bytes)
        every { skills.importMarkdown(any(), any(), SkillScope.GLOBAL, false) } returns SkillImportReport(listOf("fixture"))
        assertEquals(listOf("fixture"), repo.importSkill(entry, entry.assets.single()).imported)
        verify(exactly = 1) { skills.importMarkdown(bytes.toString(Charsets.UTF_8), any(), SkillScope.GLOBAL, false) }
        verify(exactly = 0) { resolver.openOutputStream(any(), any()) }
        assertTrue(temp.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun zipImportScopesActualArchiveAndNeverRequestsOverwrite() = runBlocking {
        val bytes = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("fixture/SKILL.md"))
            zip.write("fixture".toByteArray()); zip.closeEntry()
        } }.toByteArray()
        val repo = repository(bytes)
        val entry = item("fixture.zip", bytes)
        every { skills.importZip(any(), any(), SkillScope.GLOBAL, false) } answers {
            val zip = ZipInputStream(firstArg())
            assertEquals("fixture/SKILL.md", zip.nextEntry.name)
            assertEquals("fixture", zip.readBytes().toString(Charsets.UTF_8))
            assertNull(zip.nextEntry)
            SkillImportReport(emptyList())
        }
        assertTrue(repo.importSkill(entry, entry.assets.single()).imported.isEmpty())
        verify(exactly = 1) { skills.importZip(any(), any(), SkillScope.GLOBAL, false) }
        assertTrue(temp.root.listFiles().orEmpty().isEmpty())
    }
}
