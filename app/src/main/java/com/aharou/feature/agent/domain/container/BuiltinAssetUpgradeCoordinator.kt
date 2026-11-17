package com.aharou.feature.agent.domain.container

import android.content.Context
import com.aharou.BuildConfig
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.context.BuiltinContextEntries
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A version decision precedes every builtin write; retained files, not this APK, are the runtime source. */
class BuiltinAssetUpgradeCoordinator internal constructor(
    private val configDir: File,
    private val versionCode: Int,
    existingInstall: Boolean,
    private val installAssets: () -> Unit = {},
    private val skipContextDefaults: () -> Unit = {},
    private val historicalSeeds: () -> String = { "" },
) {
    enum class Choice { FRESH, PENDING, KEEP, APPLY }
    data class State(val versionCode: Int, val choice: Choice, val missingPreviousSkills: Boolean)

    private val receipt = File(configDir, ".builtin-asset-upgrade-v1")
    private val completedDirectories = runCatching {
        val lines = receipt.readLines()
        if (lines.firstOrNull() == versionCode.toString()) lines.drop(2).filter { it in setOf("docs", "skills", "agents", "teams") }.toMutableSet()
        else mutableSetOf()
    }.getOrElse { mutableSetOf() }
    private val initialChoice = run {
        val previous = runCatching { receipt.readLines() }.getOrNull()
        if (previous?.firstOrNull() == versionCode.toString()) {
            previous.getOrNull(1)?.let { value -> Choice.entries.firstOrNull { it.name == value } } ?: Choice.PENDING
        } else if (existingInstall || receipt.exists()) Choice.PENDING else Choice.FRESH
    }
    private val mutableState = MutableStateFlow(State(versionCode, initialChoice,
        initialChoice != Choice.FRESH && !File(configDir, "builtin-skills").walkTopDown()
            .onEnter { !Files.isSymbolicLink(it.toPath()) }
            .any { it.isFile && it.name == "SKILL.md" && !Files.isSymbolicLink(it.toPath()) }))
    val state: StateFlow<State> = mutableState.asStateFlow()
    @Volatile private var applying = false
    val canUpgrade: Boolean get() = applying || state.value.choice in setOf(Choice.FRESH, Choice.APPLY)

    init {
        // Recording a pending version never changes an asset or the native-memory receipt.
        runCatching { persist(initialChoice) }.onFailure {
            mutableState.value = mutableState.value.copy(choice = Choice.PENDING)
            FileLogger.w("BuiltinAssetUpgrade", "Cannot record builtin asset version", it)
        }
    }

    @Synchronized
    fun choose(overwrite: Boolean) {
        if (state.value.choice != Choice.PENDING) return
        // Persist APPLY only after extraction succeeds. A failed attempt may have written some files;
        // keep the prompt and durable PENDING receipt, without claiming those writes were rolled back.
        if (overwrite) {
            applying = true
            try {
                installAssets()
                persist(Choice.APPLY)
            } finally { applying = false }
            mutableState.value = state.value.copy(choice = Choice.APPLY)
        } else {
            skipContextDefaults()
            persist(Choice.KEEP)
            mutableState.value = state.value.copy(choice = Choice.KEEP)
        }
    }

    @Synchronized
    fun installIfAllowed() {
        if (canUpgrade) installAssets()
    }

    @Synchronized
    internal fun extractIfAllowed(extract: () -> Unit) {
        if (canUpgrade) extract()
    }

    @Synchronized
    internal fun extractDirectory(directory: String, extract: () -> Unit) {
        require(directory in setOf("docs", "skills", "agents", "teams"))
        if (!canUpgrade || directory in completedDirectories) return
        extract()
        completedDirectories += directory
        try { persist(state.value.choice) } catch (failure: Exception) {
            completedDirectories -= directory
            throw failure
        }
    }

    /** Missing old files stay missing. Never substitute current APK bytes for a retained installation. */
    @Synchronized
    internal fun selectedFiles(directory: String): Map<String, ByteArray> {
        require(directory in setOf("docs", "agents", "teams", "skills"))
        val root = File(configDir, if (directory == "skills") "builtin-skills" else directory)
        if (!root.isDirectory || Files.isSymbolicLink(configDir.toPath()) || Files.isSymbolicLink(root.toPath())) return emptyMap()
        val files = linkedMapOf<String, ByteArray>()
        root.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }.filter {
            it.isFile && !Files.isSymbolicLink(it.toPath())
        }.forEach { file ->
            check(file.canonicalPath.startsWith(root.canonicalPath + File.separator))
            files[file.relativeTo(root).invariantSeparatorsPath] = file.readBytes()
        }
        return files
    }

    internal fun historicalHashes(directory: String): Map<String, Set<String>> {
        require(directory in setOf("agents", "teams", "skills"))
        val local = File(configDir, "builtin-seed-state/$directory.json")
        val sources = listOfNotNull(historicalSeeds().takeIf { it.isNotBlank() }, local.takeIf { it.isFile }?.readText())
        val result = linkedMapOf<String, Set<String>>()
        for (text in sources) {
            val files = kotlinx.serialization.json.Json.parseToJsonElement(text).let {
                (it as kotlinx.serialization.json.JsonObject)["files"] as kotlinx.serialization.json.JsonObject
            }
            for ((name, hashes) in files) if (name.startsWith("$directory/")) {
                val key = name.removePrefix("$directory/")
                val values = (hashes as kotlinx.serialization.json.JsonArray).map {
                    (it as kotlinx.serialization.json.JsonPrimitive).content.also { value -> require(value.matches(Regex("[0-9a-f]{64}"))) }
                }.toSet()
                result[key] = result[key].orEmpty() + values
            }
        }
        return result
    }

    private fun persist(choice: Choice) {
        check(!Files.isSymbolicLink(configDir.toPath()) && !Files.isSymbolicLink(receipt.toPath()))
        check(configDir.isDirectory || configDir.mkdirs())
        val temporary = File.createTempFile(".builtin-upgrade-", ".tmp", configDir)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(("$versionCode\n${choice.name}\n" + completedDirectories.sorted().joinToString("\n")).toByteArray())
                output.fd.sync()
            }
            Files.move(temporary.toPath(), receipt.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    companion object {
        @Volatile private var instance: BuiltinAssetUpgradeCoordinator? = null

        @Synchronized
        fun initialize(context: Context): BuiltinAssetUpgradeCoordinator {
            instance?.let { return it }
            // This runs before Hilt's onCreate injection can create fresh-install directories.
            val config = ContainerInstaller.migrateLegacyDir(context)
            val memory = File(context.filesDir, "aharou-global/memory")
            val updatedInstall = runCatching {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                info.lastUpdateTime > info.firstInstallTime
            }.getOrDefault(false)
            val existing = config.exists() || memory.exists() || updatedInstall
            val coordinator = BuiltinAssetUpgradeCoordinator(config, BuildConfig.VERSION_CODE, existing,
                installAssets = { ContainerInstaller.updateBuiltinAssets(context) },
                skipContextDefaults = {
                    BuiltinContextEntries(File(memory, ".context-defaults-v1")) {
                        context.assets.open("context-library/builtin.json").bufferedReader().use { it.readText() }
                    }.rememberSkippedDefaults()
                }, historicalSeeds = {
                    context.assets.open("builtin-seed-history.json").bufferedReader().use { it.readText() }
                })
            instance = coordinator
            if (coordinator.state.value.missingPreviousSkills) {
                FileLogger.w("BuiltinAssetUpgrade", "Previous builtin skills are missing; current APK is not used as a fallback")
            }
            return coordinator
        }
    }
}
