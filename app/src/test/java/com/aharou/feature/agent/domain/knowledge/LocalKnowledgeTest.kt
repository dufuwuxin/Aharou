package com.aharou.feature.agent.domain.knowledge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aharou.feature.agent.domain.container.ContainerInstaller
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalKnowledgeTest {
    private lateinit var repository: KnowledgeRepository
    private lateinit var sources: KnowledgeSourceRepository
    private lateinit var catalog: KnowledgeCatalog

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val home = File(context.filesDir, "aharou")
        home.deleteRecursively()
        val installer = mockk<ContainerInstaller>()
        every { installer.aharouDir } returns home
        sources = KnowledgeSourceRepository(installer)
        catalog = KnowledgeCatalog(context, sources)
        repository = KnowledgeRepository(context, catalog, sources)
    }

    @Test
    fun createLocal_isImmediatelySearchableAndPersisted() {
        val id = repository.createLocal("Travel notes", "Offline reference", "# Kyoto\nTemple opening hours")
        assertTrue(repository.isInstalled(id))
        assertTrue(catalog.load().sources.getValue(id).isLocal)
        assertEquals("Offline reference", sources.all().getValue(id).displayDescription("en"))
        assertEquals("Temple opening hours", repository.search("opening").single().snippet.substringAfter("Kyoto "))
        assertEquals(id, repository.search("opening").single().sourceId)
        assertEquals("index.md", repository.documents(id).single().path)
    }

    @Test
    fun duplicateName_isRejectedWithoutOverwriting() {
        val id = repository.createLocal("Notes", "", "original")
        assertThrows(IllegalArgumentException::class.java) { repository.createLocal(" notes ", "", "replacement") }
        assertEquals("original", File(repository.sourceDir(id), "index.md").readText())
        assertEquals(1, sources.all().size)
    }

    @Test
    fun addDocument_rejectsTraversalAndDuplicateWithoutChangingExisting() {
        val id = repository.createLocal("Notes", "", "first")
        repository.addLocalDocument(id, "chapter.md", "# Recipe\nSaffron rice")
        assertEquals(2, repository.documents(id).size)
        assertEquals("chapter.md", repository.search("Saffron").single().path)
        assertThrows(IllegalArgumentException::class.java) { repository.addLocalDocument(id, "../escape.md", "bad") }
        assertThrows(IllegalArgumentException::class.java) { repository.addLocalDocument(id, "/escape.md", "bad") }
        assertThrows(IllegalArgumentException::class.java) { repository.addLocalDocument(id, "chapter.md", "bad") }
        assertEquals("# Recipe\nSaffron rice", File(repository.sourceDir(id), "chapter.md").readText())
    }

    @Test
    fun createLocal_rejectsBlankInputAndReportsStorageFailure() {
        assertThrows(IllegalArgumentException::class.java) { repository.createLocal(" ", "", "body") }
        assertThrows(IllegalArgumentException::class.java) { repository.createLocal("Notes", "", " ") }
        repository.rootDir().parentFile!!.mkdirs()
        repository.rootDir().writeText("not a directory")
        assertThrows(java.io.IOException::class.java) { repository.createLocal("Notes", "", "body") }
        assertTrue(sources.all().isEmpty())
    }

    @Test
    fun metadataFailure_rollsBackNewDocumentsAndDoesNotPretendSuccess() {
        val parent = repository.rootDir().parentFile!!
        parent.mkdirs()
        File(parent, "knowledge-sources.json").writeText("invalid json")
        assertThrows(kotlinx.serialization.SerializationException::class.java) {
            repository.createLocal("Notes", "", "not saved")
        }
        assertTrue(repository.rootDir().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun sourceWriteFailure_removesAlreadyWrittenFirstDocument() {
        val failingSources = mockk<KnowledgeSourceRepository>()
        every { failingSources.addLocal(any(), any()) } throws java.io.IOException("read-only metadata")
        val failingRepository = KnowledgeRepository(ApplicationProvider.getApplicationContext(), catalog, failingSources)
        assertThrows(java.io.IOException::class.java) { failingRepository.createLocal("Notes", "", "body") }
        assertTrue(failingRepository.rootDir().listFiles().orEmpty().isEmpty())
        assertTrue(sources.all().isEmpty())
    }

    @Test
    fun localSource_cannotBeReplacedByGitOrDuplicateAnExistingDisplayName() {
        val id = repository.createLocal("Notes", "", "original")
        assertThrows(IllegalArgumentException::class.java) {
            sources.add(id, KnowledgeSourceDef(repo = "owner/repository"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.createLocal(catalog.load().sources.getValue("aharou-kb").displayName("en"), "", "duplicate")
        }
        assertTrue(sources.all().getValue(id).isLocal)
    }

    @Test
    fun localSources_areNeverSyncedAndRemoteSourcesCannotReceiveLocalDocuments() = runTest {
        val id = repository.createLocal("Notes", "", "retain me")
        assertEquals(null, repository.sync(id))
        assertEquals(null, repository.remoteDigest(id))
        assertEquals("retain me", File(repository.sourceDir(id), "index.md").readText())
        assertThrows(IllegalArgumentException::class.java) {
            repository.addLocalDocument("aharou-kb", "new.md", "not local")
        }
        assertThrows(IllegalArgumentException::class.java) { repository.sourceDir("../outside") }
        assertFalse(repository.search("missing").isNotEmpty())
    }
}
