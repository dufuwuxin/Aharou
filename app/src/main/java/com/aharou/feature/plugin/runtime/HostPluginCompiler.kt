package com.aharou.feature.plugin.runtime

import android.content.Context
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.agent.domain.container.LinuxContainerEngine
import com.aharou.feature.plugin.domain.model.PluginManifest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

@Singleton
class HostPluginCompiler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val engine: LinuxContainerEngine,
    private val installer: ContainerInstaller,
    private val loader: HostPluginLoader,
) {
    /** The returned directory is unpublished; the manager owns commit/rollback and cleanup. */
    suspend fun compile(root: File, manifest: PluginManifest): File = withContext(Dispatchers.IO) {
        val spec = requireNotNull(manifest.hostRuntime) { "Not a host plugin" }
        require(spec.compileCommand.isNotBlank()) { "No compileCommand declared; import a precompiled plugin instead" }
        val plugins = File(installer.aharouDir, "plugins").apply { mkdirs() }
        val stage = Files.createTempDirectory(plugins.toPath(), ".compile-").toFile()
        val log = File(root, "data/compile-output.txt").apply { parentFile?.mkdirs() }
        try {
            copyPayload(File(root, "payload"), File(stage, "payload"))
            File(root, "manifest.json").copyTo(File(stage, "manifest.json"), overwrite = true)
            copyAsset("plugin-sdk", File(stage, "sdk"))
            require(File(stage, "payload").canonicalFile.parentFile == stage.canonicalFile) { "Staged payload escaped compilation directory" }
            val artifact = loader.payloadFile(stage, if (spec.runtime == "dex") spec.artifact else spec.entry)
            check(!artifact.exists() || artifact.delete()) { "Cannot remove previous staged artifact" }
            if (!engine.isContainerInstalled()) engine.ensureInstalled()
            val directory = "/root/.aharou/plugins/${stage.name}"
            val command = "cd ${quote(directory)} && export AHAROU_PLUGIN_SDK=${quote("$directory/sdk")} && " + spec.compileCommand
            val result = engine.runCommandSyncWithExit(command, null, 600_000L)
            log.writeText("exitCode=${result.exitCode}\n${result.output}", Charsets.UTF_8)
            check(result.exitCode == 0) { "Plugin compilation failed (exit ${result.exitCode}); see data/compile-output.txt" }
            val stagedManifest = Json { ignoreUnknownKeys = true }.decodeFromString<PluginManifest>(
                File(stage, "manifest.json").readText(Charsets.UTF_8),
            )
            require(stagedManifest == manifest) { "Compilation must preserve manifest identity and runtime declaration" }
            loader.validate(stage, spec)
            stage
        } catch (cancelled: CancellationException) {
            cleanupStage(stage)
            throw cancelled
        } catch (failure: Throwable) {
            log.appendText("\nvalidation: ${failure.message}")
            cleanupStage(stage)
            throw failure
        }
    }

    private fun copyPayload(source: File, destination: File) {
        require(Files.isDirectory(source.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Plugin payload root must be a directory, not a symbolic link" }
        Files.walk(source.toPath()).use { paths ->
            paths.forEach { path ->
                val target = destination.toPath().resolve(source.toPath().relativize(path))
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(target)
                else {
                    Files.createDirectories(target.parent)
                    Files.copy(path, target, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun cleanupStage(stage: File) {
        Files.walk(stage.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun copyAsset(path: String, target: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(path).use { source -> target.outputStream().use { source.copyTo(it) } }
        } else {
            target.mkdirs()
            children.forEach { copyAsset("$path/$it", File(target, it)) }
        }
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
