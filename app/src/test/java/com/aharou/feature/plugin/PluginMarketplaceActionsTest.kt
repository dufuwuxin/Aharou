package com.aharou.feature.plugin

import android.content.Context
import com.aharou.feature.agent.domain.skill.*
import com.aharou.feature.plugin.data.ConfigPluginFields
import com.aharou.feature.plugin.data.PluginRecordStore
import com.aharou.feature.plugin.domain.PluginManager
import com.aharou.feature.plugin.domain.market.OperitDownload
import com.aharou.feature.plugin.domain.market.PluginMarketplaceActions
import com.aharou.feature.plugin.domain.model.PluginManifest
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.core.config.*
import io.mockk.*
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PluginMarketplaceActionsTest {
    @get:Rule val temp = TemporaryFolder()
    private val context = mockk<Context>(relaxed = true)
    private val skills = mockk<SkillRepository>()
    private val manager = mockk<PluginManager>()
    private val files = mockk<FileAccessProvider>()
    private fun actions(): PluginMarketplaceActions {
        every { context.cacheDir } returns temp.root
        return PluginMarketplaceActions(context, skills, manager, files)
    }
    private fun archive(name: String, entries: Map<String, String>): File = temp.newFile(name).also { file ->
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (path, text) ->
            zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
        } }
    }
    @Test fun nativePackageInvokesImportThenInstallAndPropagatesInstallFailure() = runBlocking {
        val zip = archive("fixture.txplugin", mapOf("manifest.json" to "{}", "payload/file" to "fixture"))
        val manifest = mockk<PluginManifest>()
        every { manifest.id } returns "fixture"
        coEvery { manager.importPackage(any()) } returns Result.success(manifest)
        coEvery { manager.install("fixture") } returns Result.failure(IllegalStateException("fixture_install_failed"))
        val result = runCatching { actions().installStaged(zip, OperitDownload("https://example.org/plugin.txplugin", "plugin.txplugin", null), SkillScope.GLOBAL) }
        assertTrue(result.isFailure)
        coVerifyOrder { manager.importPackage(any()); manager.install("fixture") }
        verify(exactly = 0) { skills.importZip(any(), any(), any(), any()) }
    }
    @Test fun nativeSkillImportsWithRequestedProjectScopeAndRejectsDuplicateFailure() = runBlocking {
        val zip = archive("skill.zip", mapOf("SKILL.md" to "---\nname: fixture\n---\nbody"))
        every { skills.importZip(any(), any(), SkillScope.PROJECT, false) } returns SkillImportReport(emptyList(), fatal = SkillImportError.NAME_CONFLICT)
        assertTrue(runCatching { actions().installStaged(zip, OperitDownload("https://example.org/skill.zip", "skill.zip", null), SkillScope.PROJECT) }.isFailure)
        coVerify(exactly = 0) { manager.install(any()) }
    }
    @Test fun cliOnlyArchiveIsUnsupportedAndNeverReachesInstaller() = runBlocking {
        val zip = archive("cli.zip", mapOf("package.json" to "{}", "install.sh" to "do not run"))
        assertEquals("unsupported", PluginMarketplaceActions.detectArchive(zip))
        val failure = runCatching { actions().installStaged(zip, OperitDownload("https://example.org/cli.zip", "cli.zip", null), SkillScope.GLOBAL) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("DSH CLI runtime is not provided"))
        coVerify(exactly = 0) { manager.importPackage(any()) }
        verify(exactly = 0) { skills.importZip(any(), any(), any(), any()) }
    }
    @Test fun traversalArchiveIsNotClassifiedAsInstallable() {
        val zip = archive("bad.zip", mapOf("../manifest.json" to "{}"))
        assertTrue(runCatching { PluginMarketplaceActions.detectArchive(zip) }.isFailure)
    }
    @Test fun configQueryIsNormalButDownloadAndInstallRemainSensitive() {
        val registry = ConfigRegistry.isolated()
        ConfigPluginFields(context, manager, mockk<PluginRecordStore>(), mockk(relaxed = true), actions()).registerInto(registry)
        assertEquals(ConfigRisk.NORMAL, registry.collection("plugin_market.queries")?.risk)
        assertEquals(ConfigRisk.SENSITIVE, registry.collection("plugin_market.actions")?.risk)
        assertFalse(registry.collection("plugins")!!.addable)
    }
}
