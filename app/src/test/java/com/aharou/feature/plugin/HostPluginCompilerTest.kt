package com.aharou.feature.plugin

import com.aharou.feature.plugin.runtime.HostPluginLoader
import com.aharou.feature.plugin.domain.model.HostRuntimeSpec
import java.io.File
import android.content.Context
import android.content.res.AssetManager
import com.aharou.feature.plugin.runtime.HostPluginCompiler
import com.aharou.feature.plugin.domain.model.PluginManifest
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.agent.domain.container.LinuxContainerEngine
import com.aharou.feature.agent.domain.container.CommandResult
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HostPluginCompilerTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun invalidDexAndPayloadEscapeAreRejectedBeforeLoading() {
        val loader = HostPluginLoader()
        File(temp.root, "payload").mkdirs()
        File(temp.root, "payload/classes.dex").writeText("not dex")
        assertTrue(runCatching { loader.validate(temp.root, HostRuntimeSpec("dex", "example.Plugin", "classes.dex")) }.isFailure)
        assertTrue(runCatching { loader.validate(temp.root, HostRuntimeSpec("js", "../manifest.json")) }.isFailure)
    }
    @Test fun payloadRootLinkNeverDeletesExternalActiveArtifact() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val installer = mockk<ContainerInstaller>()
        every { installer.aharouDir } returns temp.root
        val engine = mockk<LinuxContainerEngine>()
        val root = File(temp.root, "plugins/root-link").apply { mkdirs() }
        val outside = File(temp.root, "outside-active").apply { mkdirs() }
        val entry = File(outside, "plugin.js").apply { writeText("active entry bytes") }
        java.nio.file.Files.createSymbolicLink(File(root, "payload").toPath(), outside.toPath())
        val manifest = PluginManifest(id = "root-link", hostRuntime = HostRuntimeSpec("js", "plugin.js", compileCommand = "unused-compiler"))
        File(root, "manifest.json").writeText(Json.encodeToString(manifest))
        val compiler = HostPluginCompiler(context, engine, installer, HostPluginLoader())
        assertTrue(runCatching { compiler.compile(root, manifest) }.isFailure)
        assertEquals("active entry bytes", entry.readText())
        coVerify(exactly = 0) { engine.runCommandSyncWithExit(any(), any(), any()) }
        assertFalse(File(temp.root, "plugins").listFiles().orEmpty().any { it.name.startsWith(".compile-") })
    }

    @Test fun failingCommandCapturesOutputAndNeverReplacesInstalledPayload() = runBlocking {
        val context = mockk<Context>()
        val assets = mockk<AssetManager>()
        every { context.assets } returns assets
        every { assets.list("plugin-sdk") } returns arrayOf("fixture.txt")
        every { assets.list("plugin-sdk/fixture.txt") } returns emptyArray()
        every { assets.open("plugin-sdk/fixture.txt") } answers { "sdk fixture".byteInputStream() }
        val installer = mockk<ContainerInstaller>()
        every { installer.aharouDir } returns temp.root
        val engine = mockk<LinuxContainerEngine>()
        every { engine.isContainerInstalled() } returns true
        coEvery { engine.runCommandSyncWithExit(any(), any(), any()) } returns CommandResult("fixture stderr", 7)
        val root = File(temp.root, "plugins/fixture").apply { mkdirs() }
        File(root, "payload").mkdirs()
        File(root, "payload/plugin.js").writeText("old generation")
        File(root, "manifest.json").writeText("{}")
        val manifest = PluginManifest(id = "fixture", hostRuntime = HostRuntimeSpec("js", "plugin.js", compileCommand = "fixture-compiler"))
        File(root, "manifest.json").writeText(Json.encodeToString(manifest))
        val outside = File(temp.root, "outside").apply { mkdirs() }
        val outsideFile = File(outside, "keep.txt").apply { writeText("outside fixture") }
        java.nio.file.Files.createSymbolicLink(File(root, "payload/input-link").toPath(), outside.toPath())
        coEvery { engine.runCommandSyncWithExit(any(), any(), any()) } coAnswers {
            val stage = File(temp.root, "plugins").listFiles().orEmpty().single { it.name.startsWith(".compile-") }
            assertTrue(java.nio.file.Files.isSymbolicLink(File(stage, "payload/input-link").toPath()))
            java.nio.file.Files.createSymbolicLink(File(stage, "created-link").toPath(), outside.toPath())
            CommandResult("fixture stderr", 7)
        }
        val compiler = HostPluginCompiler(context, engine, installer, HostPluginLoader())
        assertTrue(runCatching { compiler.compile(root, manifest) }.isFailure)
        assertEquals("old generation", File(root, "payload/plugin.js").readText())
        assertEquals("outside fixture", outsideFile.readText())
        assertTrue(File(root, "data/compile-output.txt").readText().contains("fixture stderr"))
        assertFalse(File(temp.root, "plugins").listFiles().orEmpty().any { it.name.startsWith(".compile-") })
        coVerify { engine.runCommandSyncWithExit(match { it.contains("AHAROU_PLUGIN_SDK") && it.endsWith("fixture-compiler") }, null, 600_000L) }
        coEvery { engine.runCommandSyncWithExit(any(), any(), any()) } returns CommandResult("exit zero without artifact", 0)
        assertTrue(runCatching { compiler.compile(root, manifest) }.isFailure)
        assertEquals("old generation", File(root, "payload/plugin.js").readText())
        assertEquals("outside fixture", outsideFile.readText())
    }

}
