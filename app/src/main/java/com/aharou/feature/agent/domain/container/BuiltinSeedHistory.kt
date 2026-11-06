package com.aharou.feature.agent.domain.container

import android.content.res.AssetManager
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Content evidence, not filenames, distinguishes installed seeds from user files. */
internal class BuiltinSeedHistory(private val configDir: File, private val directory: String) {
    private val stateFile get() = resolve("builtin-seed-state/$directory.json")
    private val digestPattern = Regex("[0-9a-f]{64}")

    fun extract(assets: AssetManager, files: Map<String, ByteArray>): Int {
        check(!Files.isSymbolicLink(configDir.toPath())) { "Config directory is a symbolic link" }
        val historical = if (assets.list("")?.contains("builtin-seed-history.json") == true) {
            assets.open("builtin-seed-history.json").bufferedReader().use { parse(it.readText(), history = true) }
        } else emptyMap()
        val state = if (exists(stateFile)) {
            check(stateFile.isFile) { "Builtin seed state is not a file" }
            parse(stateFile.readText(), history = false).mapValues { it.value.toMutableSet() }.toMutableMap()
        } else mutableMapOf()
        val known = (historical.keys + state.keys).associateWith { historical[it].orEmpty() + state[it].orEmpty() }
        val current = files.mapKeys { "$directory/${it.key}" }
        // Validate all managed paths before the first write. Unrelated user files are never traversed.
        (known.keys + current.keys).forEach { name ->
            val target = resolve(name)
            check(!exists(target) || target.isFile) { "Builtin seed target is not a file: $name" }
        }
        var written = 0
        for ((name, bytes) in current) {
            val target = resolve(name)
            val previous = if (exists(target)) target.readBytes() else null
            val hash = sha256(bytes)
            if (previous != null && !previous.contentEquals(bytes) && sha256(previous) !in known[name].orEmpty()) continue
            if (previous == null || !previous.contentEquals(bytes)) {
                writeAtomically(name, bytes, previous)
                written++
            }
            if (state.getOrPut(name) { mutableSetOf() }.add(hash)) saveState(state)
        }
        for ((name, hashes) in known) {
            if (name in current) continue
            val target = resolve(name)
            if (!exists(target)) continue
            val bytes = target.readBytes()
            val hash = sha256(bytes)
            if (hash !in hashes) continue
            val relative = name.removePrefix("$directory/")
            val archiveName = "builtin-archive/$directory/$hash/$relative"
            val archive = resolve(archiveName)
            // Even an identical existing archive is a long-lived backup, not our temporary file.
            check(!exists(archive)) { "Builtin seed archive already exists: $archiveName" }
            createParent(archiveName)
            check(resolve(name).readBytes().contentEquals(bytes)) { "Builtin seed changed before archival: $name" }
            check(!exists(resolve(archiveName))) { "Builtin seed archive appeared: $archiveName" }
            Files.move(resolve(name).toPath(), resolve(archiveName).toPath())
        }
        return written
    }

    private fun parse(text: String, history: Boolean): Map<String, Set<String>> {
        val value = Json.parseToJsonElement(text).jsonObject
        check(value["version"]?.jsonPrimitive?.intOrNull == 1) { "Unsupported builtin seed history version" }
        if (history) {
            val sources = requireNotNull(value["sources"]).jsonArray
            check(sources.all { it.jsonPrimitive.isString && Regex("[0-9a-f]{40}").matches(it.jsonPrimitive.content) }) {
                "Invalid builtin seed history sources"
            }
        }
        val result = linkedMapOf<String, Set<String>>()
        for ((name, hashes) in requireNotNull(value["files"]).jsonObject) {
            check(name.substringBefore('/') in setOf("agents", "teams")) { "Invalid builtin seed namespace" }
            validateRelative(name)
            val values = hashes.jsonArray.map {
                check(it.jsonPrimitive.isString && digestPattern.matches(it.jsonPrimitive.content)) { "Invalid builtin seed digest" }
                it.jsonPrimitive.content
            }.toSet()
            check(values.isNotEmpty()) { "Empty builtin seed evidence" }
            if (name.startsWith("$directory/")) result[name] = values
        }
        return result
    }

    private fun saveState(state: Map<String, Set<String>>) {
        val json = buildJsonObject {
            put("version", 1)
            put("files", JsonObject(state.toSortedMap().mapValues { (_, hashes) ->
                JsonArray(hashes.sorted().map { JsonPrimitive(it) })
            }))
        }.toString()
        val target = stateFile
        val previous = if (exists(target)) target.readBytes() else null
        if (previous?.contentEquals(json.toByteArray()) == true) return
        writeAtomically("builtin-seed-state/$directory.json", json.toByteArray(), previous)
    }

    private fun writeAtomically(name: String, bytes: ByteArray, previous: ByteArray?) {
        createParent(name)
        val target = resolve(name)
        val temporary = File.createTempFile(".aharou-asset-", ".tmp", target.parentFile)
        try {
            temporary.writeBytes(bytes)
            val checked = resolve(name)
            check(if (previous == null) !exists(checked) else checked.isFile && checked.readBytes().contentEquals(previous)) {
                "Builtin seed target changed before writing: $name"
            }
            if (name.startsWith("builtin-seed-state/")) {
                // Do not downgrade state publication to a potentially partial copy on unsupported filesystems.
                Files.move(temporary.toPath(), checked.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } else if (previous == null) {
                Files.move(temporary.toPath(), checked.toPath())
            } else {
                try {
                    Files.move(temporary.toPath(), checked.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), checked.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        } finally { temporary.delete() }
    }

    private fun createParent(name: String) {
        val parent = requireNotNull(resolve(name).parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create builtin seed parent" }
        resolve(name)
    }

    private fun validateRelative(name: String) {
        require(name.isNotBlank() && !name.startsWith('/') && '\\' !in name &&
            name.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Invalid builtin seed path" }
    }

    private fun resolve(name: String): File {
        validateRelative(name)
        check(!Files.isSymbolicLink(configDir.toPath())) { "Config directory is a symbolic link" }
        var current = configDir
        for (part in name.split('/')) {
            check(!exists(current) || current.isDirectory) { "Builtin seed parent is not a directory" }
            current = File(current, part)
            check(!Files.isSymbolicLink(current.toPath())) { "Builtin seed path is a symbolic link" }
        }
        check(current.canonicalPath.startsWith(configDir.canonicalPath + File.separator)) { "Builtin seed path escaped config directory" }
        return current
    }

    private fun exists(file: File) = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    companion object {
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
