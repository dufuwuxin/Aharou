package com.aharou.feature.workspace.data.provider

import android.app.Application
import android.content.pm.ProviderInfo
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.LEGACY)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.LEGACY)
class WorkspaceDocumentsProviderDeleteTest {
    @get:Rule val temp = TemporaryFolder()
    private fun provider(): WorkspaceDocumentsProvider = WorkspaceDocumentsProvider().also {
        it.attachInfo(RuntimeEnvironment.getApplication(), ProviderInfo().apply {
            authority = "fixture.documents"
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
            exported = true
            grantUriPermissions = true
        })
    }
    @Test fun deletingAnExposedDirectoryDoesNotFollowAnEmbeddedExternalSymlink() {
        val root = RuntimeEnvironment.getApplication().filesDir
        val exposed = File(root, "projects/delete-fixture").apply { mkdirs() }
        val external = temp.newFolder("outside")
        val retained = File(external, "must-survive.txt").apply { writeText("original") }
        Files.createSymbolicLink(File(exposed, "outside-link").toPath(), external.toPath())
        File(exposed, "local.txt").writeText("local")
        provider().deleteDocument(exposed.canonicalPath)
        assertFalse(exposed.exists())
        assertEquals("original", retained.readText())
    }
    @Test fun rootAndUnexposedInternalDirectoriesRemainProtected() {
        val root = RuntimeEnvironment.getApplication().filesDir
        val internal = File(root, "private-db-fixture").apply { mkdirs() }
        val marker = File(internal, "keep").apply { writeText("retained") }
        listOf(root, internal).forEach { target ->
            try { provider().deleteDocument(target.canonicalPath); fail("Must reject protected root") }
            catch (_: FileNotFoundException) { }
        }
        assertEquals("retained", marker.readText())
    }
}
